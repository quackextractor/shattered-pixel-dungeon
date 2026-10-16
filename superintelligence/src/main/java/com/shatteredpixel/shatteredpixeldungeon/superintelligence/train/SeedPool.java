package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.utils.DungeonSeedCodes;
import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;

import java.util.ArrayList;
import java.util.Random;

public class SeedPool {

	private static final long POOL_MIX = 0x9E3779B97F4A7C15L;

	public float randomFraction = 0.10f;

	private final java.util.List<String> locked = new ArrayList<>();

	private long poolSeed;

	private long scheduleSeed;

	private long draws;

	private int activeSeeds;

	private int poolSize;

	public SeedPool( long seed ){
		this( seed, seed );
	}

	public SeedPool( long poolSeed, long scheduleSeed ){
		this.poolSeed = poolSeed;
		this.scheduleSeed = scheduleSeed;
	}

	public void fill( int count ){
		locked.clear();
		Random rng = new Random( poolSeed );
		for (int i = 0; i < count; i++){
			long seed = Math.floorMod( rng.nextLong(), DungeonSeed.TOTAL_SEEDS );
			locked.add( DungeonSeedCodes.encode( seed ) );
		}
		poolSize = locked.size();
		activeSeeds = Math.min( 1, locked.size() );
	}

	public void activeSeeds( int count ){
		activeSeeds = Math.max( 1, Math.min( count, locked.size() ) );
	}

	public int activeSeeds(){
		return activeSeeds;
	}

	public int totalSeeds(){
		return locked.size();
	}

	public String next(){
		if (locked.isEmpty()) return "";

		long index = ++draws;
		Random rng = new Random( scheduleSeed + POOL_MIX * index );

		if (activeSeeds >= locked.size() && rng.nextFloat() < randomFraction){
			return "";
		}
		if (rng.nextFloat() < randomFraction ){
			return "";
		}
		return locked.get( rng.nextInt( activeSeeds ) );
	}

	public long draws(){
		return draws;
	}

	public long poolSeed(){
		return poolSeed;
	}

	public long scheduleSeed(){
		return scheduleSeed;
	}

	public void resume( long poolSeed, long scheduleSeed, long draws, int poolSize, int activeSeeds ){
		this.poolSeed = poolSeed;
		this.scheduleSeed = scheduleSeed;
		this.draws = draws;
		this.poolSize = poolSize;
		fill( poolSize );
		if (activeSeeds > 0) activeSeeds( activeSeeds );
	}

	public int poolSize(){
		return poolSize;
	}

	public java.util.List<String> seeds(){
		return locked;
	}
}