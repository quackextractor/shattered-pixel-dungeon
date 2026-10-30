package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.AsyncCollection;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Episode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.JobRunner;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TrainerWorkers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GateReport.fail;

public class AsyncCheck {

	private static final int CHECKS = 4, WORKERS = 4, JOBS = 8, GENERATIONS = 5, UPDATE_MS = 40;

	private static final class Run {
		final long start;
		final int version;

		Run( long start, int version ){
			this.start = start;
			this.version = version;
		}
	}

	private static final class FakeWorkers implements JobRunner {
		final int[] held = new int[ WORKERS ];
		final List<Run> runs = new ArrayList<>();
		final List<Long> published = new ArrayList<>();
		int publishes;

		@Override public int size(){ return WORKERS; }

		@Override
		public Episode run( int worker, TrainerWorkers.Job job ){
			long start = System.nanoTime();
			long until = start + ( 1 + job.policySeed % 7 ) * 5_000_000L;
			while (System.nanoTime() < until) Thread.onSpinWait();
			Episode e = new Episode();
			e.turns = (int) job.policySeed;
			synchronized (this){
				runs.add( new Run( start, held[ worker ] ));
			}
			return e;
		}

		@Override
		public synchronized void push( int worker, byte[] payload ){
			held[ worker ] = payload[ 0 ];
		}

		@Override
		public synchronized List<byte[]> publish(){
			publishes++;
			List<byte[]> out = new ArrayList<>();
			for (int i = 0; i < WORKERS; i++) out.add( new byte[]{ (byte) publishes } );
			return out;
		}

		synchronized void published(){
			published.add( System.nanoTime() );
		}
	}

	public static void main( String[] args ){
		try {
			check();
		} catch (java.io.IOException e){
			fail( "async collection threw " + e );
		}
		System.exit( GateReport.report( "async collection", CHECKS ));
	}

	private static void check() throws java.io.IOException {
		FakeWorkers workers = new FakeWorkers();
		AtomicInteger planned = new AtomicInteger(), current = new AtomicInteger();
		AsyncCollection source = new AsyncCollection( workers, done -> {
			int g = planned.getAndIncrement();
			List<TrainerWorkers.Job> jobs = new ArrayList<>();
			for (int i = 0; i < JOBS; i++) jobs.add( new TrainerWorkers.Job( "S", HeroClass.WARRIOR, false, g * 1000L + i ));
			return jobs;
		}, () -> GENERATIONS - current.get() - 1 );

		boolean ordered = true;
		int lateQueues = 0;
		for (int g = 0; g < GENERATIONS; g++){
			current.set( g );
			List<Episode> episodes = source.await();
			if (planned.get() < Math.min( GENERATIONS, g + 3 )) lateQueues++;
			for (int i = 0; i < JOBS; i++) ordered &= episodes.size() == JOBS && episodes.get( i ).turns == g * 1000 + i;
			long update = System.nanoTime() + UPDATE_MS * 1_000_000L;
			while (System.nanoTime() < update) Thread.onSpinWait();
			source.updated();
			workers.published();
		}
		source.close();

		if (!ordered) fail( "a generation came back with episodes missing or out of job order" );
		else System.out.println( "  " + GENERATIONS + " generations of " + JOBS + " jobs came back whole and in job order" );

		if (planned.get() != GENERATIONS) fail( planned.get() + " generations were planned for " + GENERATIONS + " awaited" );
		else System.out.println( "  exactly " + GENERATIONS + " generations were planned, none past the last" );

		int stale = 0;
		for (Run r : workers.runs){
			int before = 0;
			for (long t : workers.published) if (t < r.start) before++;
			if (r.version < before - 1) stale++;
		}
		if (stale > 0) fail( stale + " jobs ran on weights two or more publishes older than the newest when they started" );
		else System.out.println( "  every job ran on the newest weights, or the one before when a publish raced its start" );

		if (lateQueues > 0) fail( "at " + lateQueues + " of " + GENERATIONS + " updates the generation after next was not yet queued"
				+ " when the update began, so workers would wait out the " + UPDATE_MS + " ms update" );
		else System.out.println( "  every update began with the next two generations already queued for the workers" );
	}
}
