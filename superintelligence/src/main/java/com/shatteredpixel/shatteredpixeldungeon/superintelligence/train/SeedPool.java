package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.utils.DungeonSeedCodes;
import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;

import java.util.ArrayList;
import java.util.Random;

public class SeedPool {

	private final java.util.List<String> locked = new ArrayList<>();
	private final Random rng;

	private int activeSeeds;

	public SeedPool( Random rng ){
		this.rng = rng;
	}

	public void fill( int count ){
		locked.clear();
		for (int i = 0; i < count; i++){

			long seed = Math.floorMod( rng.nextLong(), DungeonSeed.TOTAL_SEEDS );
			locked.add( DungeonSeedCodes.encode( seed ) );
		}
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

		if (activeSeeds >= locked.size() && rng.nextFloat() < randomFraction){
			return "";
		}
		if (rng.nextFloat() < randomFraction){

			return "";
		}
		return locked.get( rng.nextInt( activeSeeds ) );
	}


	public float randomFraction = 0.10f;

	public java.util.List<String> seeds(){
		return locked;
	}
}