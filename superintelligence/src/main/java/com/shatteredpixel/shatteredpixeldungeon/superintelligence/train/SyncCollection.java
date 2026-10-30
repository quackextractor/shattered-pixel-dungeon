package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntSupplier;

final class SyncCollection implements GenerationSource {

	private final TrainerWorkers pool;
	private final Function<List<Episode>, List<TrainerWorkers.Job>> planner;
	private final IntSupplier remaining;
	private final boolean overlap;
	private PendingGeneration next;
	private ResourceStats.Interval wall;

	SyncCollection( TrainerWorkers pool, Function<List<Episode>, List<TrainerWorkers.Job>> planner, IntSupplier remaining,
			boolean overlap ){
		this.pool = pool;
		this.planner = planner;
		this.remaining = remaining;
		this.overlap = overlap;
	}

	@Override
	public List<Episode> await() throws IOException {
		if (next == null) next = new PendingGeneration( pool, planner.apply( null ));
		List<Episode> episodes = next.await();
		wall = next.wall;
		next = null;
		if (overlap && remaining.getAsInt() > 0) next = new PendingGeneration( pool, planner.apply( episodes ));
		return episodes;
	}

	@Override
	public ResourceStats.Interval wall(){
		return wall;
	}

	@Override
	public void updated() throws IOException {
		if (next != null) next.await();
		pool.pushWeights();
	}

	@Override
	public void close(){
	}
}
