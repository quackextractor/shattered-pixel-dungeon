package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntSupplier;

interface GenerationSource extends AutoCloseable {

	List<Episode> await() throws IOException;

	ResourceStats.Interval wall();

	void updated() throws IOException;

	@Override
	void close();

	static GenerationSource of( PpoHyperparameters hyper, TrainerWorkers pool,
			Function<List<Episode>, List<TrainerWorkers.Job>> planner, IntSupplier remaining ){
		return hyper.asyncCollection ? new AsyncCollection( pool, planner, remaining )
				: new SyncCollection( pool, planner, remaining, hyper.overlapCollection );
	}
}
