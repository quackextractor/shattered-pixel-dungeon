package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.function.IntSupplier;

public final class AsyncCollection implements GenerationSource {

	private static final class Batch {
		final List<TrainerWorkers.Job> jobs;
		final Episode[] results;
		final CountDownLatch done;
		volatile IOException failure;

		Batch( List<TrainerWorkers.Job> jobs ){
			this.jobs = jobs;
			results = new Episode[ jobs.size() ];
			done = new CountDownLatch( jobs.size() );
		}
	}

	private static final class Slot {
		final Batch batch;
		final int index;

		Slot( Batch batch, int index ){
			this.batch = batch;
			this.index = index;
		}
	}

	private final JobRunner pool;
	private final Function<List<Episode>, List<TrainerWorkers.Job>> planner;
	private final IntSupplier remaining;
	private final LinkedBlockingQueue<Slot> queue = new LinkedBlockingQueue<>();
	private final List<Thread> threads = new ArrayList<>();
	private volatile List<byte[]> payloads;
	private volatile int version;
	private volatile boolean closing;
	private Batch pending, ahead;
	private ResourceStats.Interval since = ResourceStats.start(), wall;

	public AsyncCollection( JobRunner pool, Function<List<Episode>, List<TrainerWorkers.Job>> planner, IntSupplier remaining ){
		this.pool = pool;
		this.planner = planner;
		this.remaining = remaining;
		for (int i = 0; i < pool.size(); i++){
			final int id = i;
			Thread thread = new Thread( () -> serve( id ), "collect-" + id );
			thread.setDaemon( true );
			threads.add( thread );
			thread.start();
		}
	}

	private void serve( int id ){
		int pushed = 0;
		while (!closing){
			Slot slot;
			try {
				slot = queue.take();
			} catch (InterruptedException e){
				return;
			}
			if (slot.batch == null) return;
			try {
				int latest = version;
				if (pushed != latest){
					pool.push( id, payloads.get( id ));
					pushed = latest;
				}
				slot.batch.results[ slot.index ] = pool.run( id, slot.batch.jobs.get( slot.index ));
			} catch (IOException e){
				slot.batch.failure = e;
			}
			slot.batch.done.countDown();
		}
	}

	private Batch submit( List<TrainerWorkers.Job> jobs ){
		Batch batch = new Batch( jobs );
		for (int i = 0; i < jobs.size(); i++) queue.add( new Slot( batch, i ));
		return batch;
	}

	@Override
	public List<Episode> await() throws IOException {
		if (pending == null){
			pending = submit( planner.apply( null ));
			if (remaining.getAsInt() > 0) ahead = submit( planner.apply( null ));
		}
		try {
			pending.done.await();
		} catch (InterruptedException e){
			Thread.currentThread().interrupt();
			throw new IOException( "interrupted while workers collected a generation", e );
		}
		if (pending.failure != null) throw pending.failure;
		List<Episode> episodes = new ArrayList<>( Arrays.asList( pending.results ));
		pending = ahead;
		ahead = pending != null && remaining.getAsInt() > 1 ? submit( planner.apply( episodes )) : null;
		wall = since.stop();
		since = ResourceStats.start();
		return episodes;
	}

	@Override
	public ResourceStats.Interval wall(){
		return wall;
	}

	@Override
	public void updated() throws IOException {
		payloads = pool.publish();
		version++;
	}

	@Override
	public void close(){
		closing = true;
		queue.clear();
		for (int i = 0; i < threads.size(); i++) queue.add( new Slot( null, -1 ));
		for (Thread thread : threads){
			try {
				thread.join();
			} catch (InterruptedException e){
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
