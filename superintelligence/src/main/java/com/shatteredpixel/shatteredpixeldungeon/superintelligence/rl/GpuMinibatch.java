package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

final class GpuMinibatch implements AutoCloseable {

	private final Network net;
	private final ClDevice cl;
	private final ClEncoder encoder;
	private final ClRecurrent recurrent;
	private final ClParameters params;

	private final float[][] headLogits, headProbs, headGrads;
	private final int[] headOffset;
	private final byte[] blankGrid;
	private final float[] extrasRow;

	GpuMinibatch( Network net ){
		this.net = net;
		cl = new ClDevice( "encoder.cl", "recurrent.cl", "params.cl" );
		try {
			params = new ClParameters( cl, net );
			encoder = new ClEncoder( cl, net, params );
			recurrent = new ClRecurrent( cl, net, params );
		} catch (RuntimeException e){
			cl.close();
			throw e;
		}
		int[] sizes = { net.actionCount(), net.slotCount(), net.targetCount() };
		headLogits = new float[ 3 ][];
		headProbs = new float[ 3 ][];
		headGrads = new float[ 3 ][];
		headOffset = new int[ 3 ];
		for (int h = 0, off = 0; h < 3; off += sizes[ h ], h++){
			headLogits[ h ] = new float[ sizes[ h ]];
			headProbs[ h ] = new float[ sizes[ h ]];
			headGrads[ h ] = new float[ sizes[ h ]];
			headOffset[ h ] = off;
		}
		blankGrid = new byte[ encoder.packedBytes ];
		extrasRow = new float[ encoder.extra ];
	}

	String deviceName(){
		return cl.name;
	}

	void begin(){
		params.upload();
	}

	void end(){
		params.download();
	}

	PPO.Minibatch run( PPO ppo, List< List< Transition > > chunks, int from, int to ){
		int count = to - from;
		int[] length = new int[ count ];
		int steps = 0;
		for (int i = 0; i < count; i++){
			length[ i ] = Math.min( chunks.get( from + i ).size(), ppo.chunkLength );
			steps = Math.max( steps, length[ i ] );
		}

		stage( chunks, from, count, steps, length );
		encoder.forward( steps * count );
		float[] logits = recurrent.forward( steps, count, initialStates( chunks, from, count ), encoder.hiddenOut );

		PPO.Minibatch m = new PPO.Minibatch();
		float[] dLogits = new float[ logits.length ];
		for (int i = 0; i < count; i++){
			List< Transition > chunk = chunks.get( from + i );
			PPO.Stats s = new PPO.Stats();
			s.steps = length[ i ];
			for (int t = 0; t < length[ i ]; t++) lossRow( ppo, chunk.get( t ), ( t * count + i ) * recurrent.K, logits, dLogits, s );
			m.policyLoss += s.policyLoss;
			m.valueLoss += s.valueLoss;
			m.entropy += s.entropy;
			m.kl += s.kl;
			m.clipped += s.clippedSteps;
			m.steps += s.steps;
		}

		recurrent.backward( steps, count, dLogits, encoder.hiddenOut, encoder.dHidden );
		encoder.backward( steps * count );

		m.gradNorm = params.step( count, ppo.network.gradClip );
		if (m.gradNorm > ppo.network.gradClip) m.gradClipped = 1;
		return m;
	}

	private void lossRow( PPO ppo, Transition t, int at, float[] logits, float[] dLogits, PPO.Stats s ){
		int h = t.liveHead == Policy.HEAD_SLOT ? 1 : t.liveHead == Policy.HEAD_TARGET ? 2 : 0;
		System.arraycopy( logits, at + headOffset[ h ], headLogits[ h ], 0, headLogits[ h ].length );
		float value = logits[ at + recurrent.K - 1 ];
		dLogits[ at + recurrent.K - 1 ] = ppo.lossStep( t, headLogits[ h ], headProbs[ h ], headGrads[ h ], value, s );
		System.arraycopy( headGrads[ h ], 0, dLogits, at + headOffset[ h ], headGrads[ h ].length );
	}

	private float[] initialStates( List< List< Transition > > chunks, int from, int count ){
		int size = net.stateSize();
		float[] states = new float[ count * size ];
		for (int i = 0; i < count; i++){
			float[] state = chunks.get( from + i ).get( 0 ).recurrentState;
			if (state != null) System.arraycopy( state, 0, states, i * size, size );
		}
		return states;
	}

	private void stage( List< List< Transition > > chunks, int from, int count, int steps, int[] length ){
		ByteBuffer packed = encoder.packedStage( steps * count );
		FloatBuffer extras = encoder.extrasStage( steps * count );
		int heroAt = net.config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT;
		for (int t = 0; t < steps; t++){
			for (int i = 0; i < count; i++){
				java.util.Arrays.fill( extrasRow, 0f );
				if (t >= length[ i ]){
					packed.put( blankGrid );
					extras.put( extrasRow );
					continue;
				}
				Transition step = chunks.get( from + i ).get( t );
				if (step.grid.length != encoder.packedBytes || step.gridCells != encoder.cells){
					throw new IllegalStateException( "a transition packs " + step.gridCells + " cells in " + step.grid.length
							+ " bytes, the network reads " + encoder.cells + " in " + encoder.packedBytes );
				}
				packed.put( step.grid );
				System.arraycopy( step.inventory, 0, extrasRow, 0, step.inventory.length );
				System.arraycopy( step.hero, 0, extrasRow, heroAt, step.hero.length );
				extras.put( extrasRow );
			}
		}
	}

	@Override
	public void close(){
		recurrent.close();
		encoder.close();
		params.close();
		cl.close();
	}
}
