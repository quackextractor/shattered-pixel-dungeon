package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

final class GpuMinibatch implements AutoCloseable {

	private static final int STATS = 8;

	private final Network net;
	private final ClDevice cl;
	private final ClEncoder encoder;
	private final ClRecurrent recurrent;
	private final ClParameters params;
	private final long kGatherBytes, kGatherFloats, kGatherState, kLoss, kStats;
	private final int A, S, T, K, s, heroAt;
	private final float[] zeroExtras;

	private final IdentityHashMap< List< Transition >, int[] > placed = new IdentityHashMap<>();
	private final List< Integer > slotSteps = new ArrayList<>();
	private long packed, extras, states, ints, floats, masks, map, stats, rowStats;
	private int capacity, chunkCapacity, mapCapacity, slotCapacity, rowCapacity, mapUsed;
	private ByteBuffer packedHost, masksHost;
	private FloatBuffer extrasHost, statesHost, floatsHost;
	private IntBuffer intsHost, mapHost;

	GpuMinibatch( Network net ){
		this.net = net;
		cl = new ClDevice( "encoder.cl", "recurrent.cl", "params.cl", "update.cl" );
		try {
			params = new ClParameters( cl, net );
			encoder = new ClEncoder( cl, net, params );
			recurrent = new ClRecurrent( cl, net, params );
		} catch (RuntimeException e){
			cl.close();
			throw e;
		}
		kGatherBytes = cl.kernel( "gather_bytes" );
		kGatherFloats = cl.kernel( "gather_floats" );
		kGatherState = cl.kernel( "gather_state" );
		kLoss = cl.kernel( "ppo_loss" );
		kStats = cl.kernel( "loss_stats" );
		A = net.actionCount();
		S = net.slotCount();
		T = net.targetCount();
		K = recurrent.K;
		s = net.memory.size;
		heroAt = net.config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT;
		zeroExtras = new float[ encoder.extra ];
	}

	String deviceName(){
		return cl.name;
	}

	void begin( PPO ppo, List< List< Transition > > chunks, int minibatches ){
		params.upload();
		placed.clear();
		for (java.nio.Buffer b : new java.nio.Buffer[]{ packedHost, masksHost, extrasHost, statesHost, floatsHost, intsHost }){
			if (b != null) b.clear();
		}
		slotSteps.clear();
		mapUsed = 0;
		int n = 0;
		for (List< Transition > c : chunks) n += Math.min( c.size(), ppo.chunkLength );
		int rows = ppo.chunkLength * ppo.minibatchSize;
		reserve( n, chunks.size(), minibatches * ( rows + ppo.minibatchSize ), minibatches, rows );

		int[] base = new int[ chunks.size() ];
		for (int c = 0, at = 0; c < chunks.size(); at += Math.min( chunks.get( c ).size(), ppo.chunkLength ), c++){
			base[ c ] = at;
			placed.put( chunks.get( c ), new int[]{ at, c } );
		}
		float[] zeros = new float[ 2 * s ];
		java.util.stream.IntStream.range( 0, chunks.size() ).parallel().forEach( c -> {
			List< Transition > chunk = chunks.get( c );
			float[] state = chunk.get( 0 ).recurrentState;
			statesHost.put( c * 2 * s, state != null ? state : zeros, 0, 2 * s );
			for (int t = 0; t < Math.min( chunk.size(), ppo.chunkLength ); t++) stage( ppo, chunk.get( t ), base[ c ] + t );
		} );
		cl.writeAsync( packed, packedHost.limit( n * encoder.packedBytes ).position( 0 ));
		cl.writeAsync( extras, extrasHost.limit( n * encoder.extra ).position( 0 ));
		cl.writeAsync( states, statesHost.limit( chunks.size() * 2 * s ).position( 0 ));
		cl.writeAsync( ints, intsHost.limit( n * 4 ).position( 0 ));
		cl.writeAsync( floats, floatsHost.limit( n * 4 ).position( 0 ));
		cl.writeAsync( masks, masksHost.limit( n * ( K - 1 )).position( 0 ));
	}

	private void stage( PPO ppo, Transition t, int at ){
		if (t.grid.length != encoder.packedBytes || t.gridCells != encoder.cells){
			throw new IllegalStateException( "a transition packs " + t.gridCells + " cells in " + t.grid.length
					+ " bytes, the network reads " + encoder.cells + " in " + encoder.packedBytes );
		}
		packedHost.put( at * encoder.packedBytes, t.grid );
		int e = at * encoder.extra;
		extrasHost.put( e, zeroExtras );
		extrasHost.put( e, t.inventory );
		extrasHost.put( e + heroAt, t.hero );
		boolean slotted = t.liveHead == Policy.HEAD_SLOT || t.liveHead == Policy.HEAD_TARGET;
		intsHost.put( at * 4, t.liveHead );
		intsHost.put( at * 4 + 1, slotted ? t.slotIndex : t.actionIndex );
		intsHost.put( at * 4 + 2, t.teacherIndex );
		intsHost.put( at * 4 + 3, t.demonstration ? 1 : 0 );
		floatsHost.put( at * 4, t.oldLogProbability );
		floatsHost.put( at * 4 + 1, t.advantage );
		floatsHost.put( at * 4 + 2, ppo.valueTarget( t ));
		floatsHost.put( at * 4 + 3, 0f );
		byte[] mask = new byte[ K - 1 ];
		for (int j = 0; j < A; j++) mask[ j ] = (byte) ( t.actionMask[ j ] > 0.5f ? 1 : 0 );
		for (int j = 0; j < S; j++) mask[ A + j ] = (byte) ( t.slotMask[ j ] > 0.5f ? 1 : 0 );
		for (int j = 0; j < T; j++) mask[ A + S + j ] = (byte) ( t.targetMask[ j ] > 0.5f ? 1 : 0 );
		masksHost.put( at * ( K - 1 ), mask );
	}

	void enqueue( PPO ppo, List< List< Transition > > chunks, int from, int to ){
		int count = to - from;
		int[] length = new int[ count ];
		int steps = 0, real = 0;
		for (int i = 0; i < count; i++){
			length[ i ] = Math.min( chunks.get( from + i ).size(), ppo.chunkLength );
			steps = Math.max( steps, length[ i ] );
			real += length[ i ];
		}
		int rows = steps * count;
		int rowAt = mapUsed, chunkAt = mapUsed + rows;
		for (int i = 0; i < count; i++){
			int[] where = placed.get( chunks.get( from + i ));
			for (int t = 0; t < steps; t++) mapHost.put( rowAt + t * count + i, t < length[ i ] ? where[ 0 ] + t : -1 );
			mapHost.put( chunkAt + i, where[ 1 ] );
		}
		mapUsed += rows + count;
		cl.writeAsync( map, rowAt, mapHost.slice( rowAt, rows + count ));

		int slot = slotSteps.size();
		slotSteps.add( real );
		encoder.reserve( rows );
		recurrent.reserve( rows, count );
		cl.run( kGatherBytes, (long) rows * encoder.packedBytes, packed, map, encoder.packed(), rowAt, encoder.packedBytes );
		cl.run( kGatherFloats, (long) rows * encoder.extra, extras, map, encoder.extras(), rowAt, encoder.extra );
		cl.run( kGatherState, (long) count * s, states, map, recurrent.h0(), recurrent.c0(), chunkAt, s );

		encoder.forward( rows );
		recurrent.forward( steps, count, encoder.hiddenOut );
		cl.runSized( kLoss, rows, 64, recurrent.logits(), map, ints, floats, masks, recurrent.dLogits(), rowStats, rowAt, K, A, S, T,
				ppo.clipEpsilon, ppo.entropyCoeff, ppo.demoWeight, ppo.teacherWeight, ppo.valueLossCoefficient,
				ppo.valueGradientClip, ppo.huberValues ? 1 : 0 );
		cl.run( kStats, 5, rowStats, stats, rows, slot );
		recurrent.backward( steps, count, encoder.hiddenOut, encoder.dHidden );
		encoder.backward( rows );
		params.step( count, net.gradClip, stats, slot );
	}

	List< PPO.Minibatch > end(){
		List< PPO.Minibatch > out = new ArrayList<>();
		float[] read = new float[ slotSteps.size() * STATS ];
		if (read.length > 0) cl.read( stats, read );
		params.download();
		for (int i = 0; i < slotSteps.size(); i++){
			PPO.Minibatch m = new PPO.Minibatch();
			m.policyLoss = read[ i * STATS ];
			m.valueLoss = read[ i * STATS + 1 ];
			m.entropy = read[ i * STATS + 2 ];
			m.kl = read[ i * STATS + 3 ];
			m.clipped = read[ i * STATS + 4 ];
			m.gradNorm = read[ i * STATS + 5 ];
			m.gradClipped = m.gradNorm > net.gradClip ? 1 : 0;
			m.steps = slotSteps.get( i );
			out.add( m );
		}
		placed.clear();
		return out;
	}

	private void reserve( int n, int chunks, int mapInts, int slots, int rows ){
		if (n > capacity){
			cl.release( packed, extras, ints, floats, masks );
			capacity = n;
			packed = cl.bytes( (long) n * encoder.packedBytes );
			extras = cl.floats( (long) n * encoder.extra );
			ints = cl.floats( (long) n * 4 );
			floats = cl.floats( (long) n * 4 );
			masks = cl.bytes( (long) n * ( K - 1 ));
			packedHost = BufferUtils.createByteBuffer( n * encoder.packedBytes );
			extrasHost = BufferUtils.createFloatBuffer( n * encoder.extra );
			intsHost = BufferUtils.createIntBuffer( n * 4 );
			floatsHost = BufferUtils.createFloatBuffer( n * 4 );
			masksHost = BufferUtils.createByteBuffer( n * ( K - 1 ));
		}
		if (chunks > chunkCapacity){
			cl.release( states );
			chunkCapacity = chunks;
			states = cl.floats( (long) chunks * 2 * s );
			statesHost = BufferUtils.createFloatBuffer( chunks * 2 * s );
		}
		if (mapInts > mapCapacity){
			cl.release( map );
			mapCapacity = mapInts;
			map = cl.floats( mapInts );
			mapHost = BufferUtils.createIntBuffer( mapInts );
		}
		if (slots > slotCapacity){
			cl.release( stats );
			slotCapacity = slots;
			stats = cl.floats( (long) slots * STATS );
		}
		if (rows > rowCapacity){
			cl.release( rowStats );
			rowCapacity = rows;
			rowStats = cl.floats( (long) rows * 5 );
		}
	}

	@Override
	public void close(){
		cl.release( packed, extras, states, ints, floats, masks, map, stats, rowStats );
		recurrent.close();
		encoder.close();
		params.close();
		cl.close();
	}
}
