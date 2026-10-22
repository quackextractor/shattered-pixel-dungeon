package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;

import java.io.IOException;
import java.util.List;

final class PendingGeneration {

	final ResourceStats.Interval wall = ResourceStats.start();

	private final Thread thread;
	private List<Episode> episodes;
	private IOException failure;

	PendingGeneration( TrainerWorkers pool, List<TrainerWorkers.Job> jobs ){
		thread = new Thread( () -> {
			try {
				episodes = pool.dispatch( jobs );
			} catch (IOException e){
				failure = e;
			} finally {
				wall.stop();
			}
		}, "collect" );
		thread.start();
	}

	List<Episode> await() throws IOException {
		try {
			thread.join();
		} catch (InterruptedException e){
			Thread.currentThread().interrupt();
			throw new IOException( "interrupted while workers collected a generation", e );
		}
		if (failure != null) throw failure;
		return episodes;
	}
}
