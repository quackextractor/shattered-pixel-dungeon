package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.utils.DungeonSeedCodes;
import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;

import java.util.ArrayList;
import java.util.Random;

/**
 * The pool of seeds rollouts are drawn from.
 *
 * docs.md: "Since the dungeon is procedurally generated, generalizing across seeds will be
 * challenging for the AI. To compare runs fairly or display multiple simultaneous simulations
 * (e.g., 1000 sims at once), we can lock the seed, picking the best performing run for a given
 * seed while still training across varied seeds."
 *
 * research.md's Generalizing Across Seeds lays out the schedule this implements: one seed until the
 * agent beats the first boss, then ten, then a hundred, then fully random. Expanding by unlocking
 * the next pool, rather than by resampling, means a seed that has already been trained on keeps
 * being trained on. Otherwise the agent would keep re-learning the first few seeds and never
 * generalise, which is exactly the memorisation the schedule is meant to prevent.
 */
public class SeedPool {

	private final java.util.List<String> locked = new ArrayList<>();
	private final Random rng;

	/** How many of the locked seeds are currently in rotation. */
	private int activeSeeds;

	public SeedPool( Random rng ){
		this.rng = rng;
	}

	/**
	 * Fills the locked pool with fresh seeds.
	 *
	 * @param count how many distinct seeds to hold
	 */
	public void fill( int count ){
		locked.clear();
		for (int i = 0; i < count; i++){
			locked.add( DungeonSeedCodes.encode( rng.nextLong() ) );
		}
		activeSeeds = Math.min( 1, locked.size() );
	}

	/**
	 * Sets how many locked seeds are in rotation.
	 *
	 * research.md's schedule: 1, then 10, then 100, then all random. Clamped to what was filled.
	 */
	public void activeSeeds( int count ){
		activeSeeds = Math.max( 1, Math.min( count, locked.size() ) );
	}

	public int activeSeeds(){
		return activeSeeds;
	}

	public int totalSeeds(){
		return locked.size();
	}

	/**
	 * Draws a seed for the next rollout.
	 *
	 * Most draws come from the active locked set so runs remain comparable and reproducible. A
	 * small fraction are random even while locked, which keeps the agent from overfitting the
	 * locked set before the schedule says it is ready. Without that leak the early single-seed
	 * phase produces a policy that has memorised one map and has to unlearn it later.
	 */
	public String next(){
		if (locked.isEmpty()) return "";

		if (activeSeeds >= locked.size() && rng.nextFloat() < RANDOM_FRACTION){
			return "";
		}
		if (rng.nextFloat() < RANDOM_FRACTION){
			//a random draw even during the locked phase, so the pool is sampled not memorised
			return "";
		}
		return locked.get( rng.nextInt( activeSeeds ) );
	}

	/** Fraction of rollouts that use a fully random seed regardless of phase. */
	private static final float RANDOM_FRACTION = 0.1f;

	/** Every seed currently locked, for diagnostics. */
	public java.util.List<String> seeds(){
		return locked;
	}
}