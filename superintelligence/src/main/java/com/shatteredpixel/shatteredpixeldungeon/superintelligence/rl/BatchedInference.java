package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.shared.InferenceBroker;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public final class BatchedInference implements InferenceBroker.Server, AutoCloseable {

	public static int engineCount = 3;

	private static final long QUIET_NANOS = 200_000;

	private static final long GATHER_NANOS = 2_000_000;

	private final Network weights;
	private final int stateSize, heroAt;
	private final List< Engine > engines = new ArrayList<>();
	private final List< float[] > states = new CopyOnWriteArrayList<>();
	private final ConcurrentLinkedQueue< Request > queue = new ConcurrentLinkedQueue<>();
	private final AtomicInteger pending = new AtomicInteger();
	private volatile boolean running = true;
	private volatile int open;
	private volatile long lastArrival;

	private static final class Request {
		int slot;
		byte[] grid;
		float[] inventory, hero, out;
		Thread waiter;
		volatile boolean done;
		RuntimeException failure;
	}

	private final class Engine {
		final ClDevice cl = new ClDevice( "encoder.cl", "recurrent.cl", "params.cl" );
		final Network local = new Network( weights.config, new Random( 1L ));
		final ClParameters params = new ClParameters( cl, local );
		final ClEncoder encoder = new ClEncoder( cl, local, params );
		final ClRecurrent recurrent = new ClRecurrent( cl, local, params );
		final float[] extrasRow = new float[ encoder.extra ];
		final Thread thread = new Thread( this::dispatch, "inference" );

		void load(){
			synchronized (this){
				local.copyParametersFrom( weights );
				params.upload();
			}
		}

		void dispatch(){
			List< Request > batch = new ArrayList<>();
			while (running){
				if (pending.get() == 0){
					LockSupport.park( this );
					continue;
				}
				long start = System.nanoTime();
				for (long now = start; pending.get() < open && now - start < GATHER_NANOS && now - lastArrival < QUIET_NANOS; now = System.nanoTime()){
					Thread.onSpinWait();
				}
				batch.clear();
				for (Request r; ( r = queue.poll() ) != null; ) batch.add( r );
				if (batch.isEmpty()) continue;
				pending.addAndGet( -batch.size() );
				RuntimeException failure = null;
				try {
					synchronized (this){
						run( batch );
					}
				} catch (RuntimeException e){
					failure = e;
				}
				for (Request r : batch){
					r.failure = failure;
					r.done = true;
					LockSupport.unpark( r.waiter );
				}
			}
		}

		void run( List< Request > batch ){
			int rows = batch.size();
			ByteBuffer packed = encoder.packedStage( rows );
			FloatBuffer extras = encoder.extrasStage( rows );
			float[] state0 = new float[ rows * stateSize ];
			for (int i = 0; i < rows; i++){
				Request r = batch.get( i );
				packed.put( r.grid );
				Arrays.fill( extrasRow, 0f );
				System.arraycopy( r.inventory, 0, extrasRow, 0, r.inventory.length );
				System.arraycopy( r.hero, 0, extrasRow, heroAt, r.hero.length );
				extras.put( extrasRow );
				System.arraycopy( states.get( r.slot ), 0, state0, i * stateSize, stateSize );
			}
			encoder.forwardInference( rows );
			float[] next = new float[ rows * stateSize ];
			float[] logits = recurrent.forward( 1, rows, state0, encoder.hiddenOut, next );
			int K = recurrent.K;
			for (int i = 0; i < rows; i++){
				Request r = batch.get( i );
				System.arraycopy( logits, i * K, r.out, 0, K );
				System.arraycopy( next, i * stateSize, states.get( r.slot ), 0, stateSize );
			}
		}

		void close(){
			synchronized (this){
				recurrent.close();
				encoder.close();
				params.close();
				cl.close();
			}
		}
	}

	public BatchedInference( Network source ){
		weights = new Network( source.config, new Random( 1L ));
		weights.copyParametersFrom( source );
		stateSize = weights.stateSize();
		heroAt = weights.config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT;
		for (int i = 0; i < Math.max( 1, engineCount ); i++){
			Engine engine = new Engine();
			engine.load();
			engine.thread.setDaemon( true );
			engines.add( engine );
		}
		for (Engine engine : engines) engine.thread.start();
	}

	public String deviceName(){
		return engines.get( 0 ).cl.name + ", " + engines.size() + " pipelines";
	}

	public synchronized void load( NetworkWeights source ){
		weights.copyParametersFrom( source );
		for (Engine engine : engines) engine.load();
	}

	@Override
	public synchronized int open(){
		states.add( new float[ stateSize ]);
		open = states.size();
		return open - 1;
	}

	@Override public void reset( int slot ){ Arrays.fill( states.get( slot ), 0f ); }
	@Override public void save( int slot, float[] into ){ System.arraycopy( states.get( slot ), 0, into, 0, Math.min( into.length, stateSize )); }
	@Override public void load( int slot, float[] from ){ System.arraycopy( from, 0, states.get( slot ), 0, Math.min( from.length, stateSize )); }

	@Override
	public void forward( int slot, byte[] packedGrid, float[] inventory, float[] hero, float[] out ){
		Request r = new Request();
		r.slot = slot;
		r.grid = packedGrid;
		r.inventory = inventory;
		r.hero = hero;
		r.out = out;
		r.waiter = Thread.currentThread();
		queue.add( r );
		lastArrival = System.nanoTime();
		if (pending.incrementAndGet() == 1){
			for (Engine engine : engines) LockSupport.unpark( engine.thread );
		}
		while (!r.done) LockSupport.park( this );
		if (r.failure != null) throw r.failure;
	}

	@Override
	public void close(){
		running = false;
		for (Engine engine : engines){
			LockSupport.unpark( engine.thread );
			engine.close();
		}
	}
}
