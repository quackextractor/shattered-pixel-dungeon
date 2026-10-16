package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Checkpoint;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.SeedPool;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SeedScheduleCheck {

	private static final int CHECKS = 5;

	private static final int POOL = 100;

	public static void main( String[] args ){
		checkDrawsAreAPlainFunctionOfTheirIndex();
		checkTheSameRunDrawsTheSameSequence();
		checkADifferentSeedDrawsADifferentSequence();
		checkAResumedPoolContinuesTheInterruptedSequence();
		checkAResumedPoolDoesNotRepeatGenerationZero();

		System.exit( GateReport.report( "seed schedule", CHECKS ));
	}

	private static void fail( String message ){
		GateReport.fail( message );
	}


	private static SeedPool pool( long seed, int draws ){
		SeedPool pool = new SeedPool( seed ^ 0xBF58476D1CE4E5B9L, seed ^ 0x94D049BB133111EBL );
		pool.fill( POOL );
		for (int i = 0; i < draws; i++) pool.next();
		return pool;
	}

	private static List< String > draw( SeedPool pool, int n ){
		List< String > out = new ArrayList<>( n );
		for (int i = 0; i < n; i++) out.add( pool.next() );
		return out;
	}


	private static void checkDrawsAreAPlainFunctionOfTheirIndex(){
		SeedPool forward = pool( 20261016L, 0 );
		SeedPool offset = pool( 20261016L, 500 );

		List< String > fromStart = draw( forward, 600 );
		List< String > fromOffset = draw( offset, 100 );

		if (!fromStart.subList( 500, 600 ).equals( fromOffset )){
			fail( "a pool with 500 draws already made does not produce the same next 100 seeds as a"
					+ " pool that has made none. A draw has to be a function of its index alone, or a"
					+ " resume cannot continue the sequence at all." );
			return;
		}

		System.out.println( "  a draw is a function of its index: 500 draws of head start produce"
				+ " the same 100 seeds as none" );
	}


	private static void checkTheSameRunDrawsTheSameSequence(){
		List< String > a = draw( pool( 777L, 0 ), 64 );
		List< String > b = draw( pool( 777L, 0 ), 64 );

		if (!a.equals( b )){
			fail( "two pools built from the same seed drew different sequences, so a run cannot be"
					+ " repeated from its own seed" );
			return;
		}

		int random = 0;
		for (String s : a) if (s.isEmpty()) random++;
		if (random == 0 ){
			fail( "64 draws produced no random seed at all, so train.random_seed_rate is not being"
					+ " applied and the schedule cannot be checked against a leaked-seed run" );
			return;
		}

		System.out.println( "  the same seed draws the same 64 seeds, " + random + " of them random"
				+ " under train.random_seed_rate=0.10" );
	}


	private static void checkADifferentSeedDrawsADifferentSequence(){
		List< String > a = draw( pool( 1L, 0 ), 64 );
		List< String > b = draw( pool( 2L, 0 ), 64 );

		if (a.equals( b )){
			fail( "two pools built from different trainer seeds drew the same 64 seeds" );
			return;
		}

		System.out.println( "  two trainer seeds draw different sequences" );
	}


	private static void checkAResumedPoolContinuesTheInterruptedSequence(){
		SeedPool original = pool( 4242L, 0 );
		List< String > whole = draw( original, 200 );

		SeedPool interrupted = pool( 4242L, 0 );
		draw( interrupted, 120 );

		long poolSeed = interrupted.poolSeed();
		long scheduleSeed = interrupted.scheduleSeed();
		long draws = interrupted.draws();
		int active = interrupted.activeSeeds();

		SeedPool resumed = new SeedPool( poolSeed, scheduleSeed );
		resumed.resume( poolSeed, scheduleSeed, draws, POOL, active );

		if (resumed.draws() != draws ){
			fail( "a resumed pool reports " + resumed.draws() + " draws against the " + draws
					+ " it was resumed at, so it restarts the sequence" );
			return;
		}

		List< String > tail = draw( resumed, 80 );
		if (!whole.subList( 120, 200 ).equals( tail )){
			fail( "a pool resumed after 120 draws does not produce draws 121 to 200 of the sequence it"
					+ " was part of, so a resumed run sees seeds the uninterrupted run never saw and"
					+ " then misses seeds it did" );
			return;
		}

		System.out.println( "  a pool resumed after 120 draws continues at draw 121 and reproduces the"
				+ " uninterrupted sequence for the next 80" );
	}


	private static void checkAResumedPoolDoesNotRepeatGenerationZero(){
		File dir = new File( System.getProperty( "java.io.tmpdir" ), "spd-seedcheck" );
		dir.mkdirs();
		File file = new File( dir, "weights.bin" );

		SeedPool original = pool( 909L, 0 );
		draw( original, 150 );

		try {
			Checkpoint.save( file, new com.shatteredpixel.shatteredpixeldungeon.superintelligence
							.rl.Network( new com.shatteredpixel.shatteredpixeldungeon.superintelligence
							.env.EnvConfig(), new java.util.Random( 1L )),
					new com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig(),
					30, 909L,
					original.poolSeed(), original.scheduleSeed(), original.draws(),
					POOL, original.activeSeeds() );

			com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig config =
					new com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig();
			Checkpoint.Meta meta = Checkpoint.load( file,
					new com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network( config,
							new java.util.Random( 2L ) ), config );

			SeedPool fresh = new SeedPool( 909L ^ 0xBF58476D1CE4E5B9L, 909L ^ 0x94D049BB133111EBL );
			fresh.fill( POOL );
			List< String > generationZero = draw( fresh, 32 );

			SeedPool resumed = new SeedPool( meta.seedPoolSeed, meta.seedScheduleSeed );
			resumed.resume( meta.seedPoolSeed, meta.seedScheduleSeed, meta.seedDraws, meta.seedPoolSize,
					meta.activeSeeds );
			List< String > afterResume = draw( resumed, 32 );

			if (generationZero.equals( afterResume )){
				fail( "a run resumed from generation " + meta.generation + " opens with the same "
						+ generationZero.size() + " seeds a fresh run opens with. The schedule is still"
						+ " restarting at the checkpoint's own seed rather than continuing." );
				return;
			}
		} catch (java.io.IOException e){
			fail( "could not write and read a checkpoint: " + e.getMessage() );
			return;
		}

		System.out.println( "  a checkpoint's Meta carries the schedule, so a resumed run does not open"
				+ " with the seeds generation 0 opened with" );
	}
}