package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ChunkReservoir {

	private final int capacity;
	private final int chunkLength;
	private final Random rng;

	private final List< List< Transition > > chunks = new ArrayList<>();

	private long seen;
	private long offered;
	private long replacements;

	public ChunkReservoir( int capacity, int chunkLength, long seed ){
		this.capacity = Math.max( 1, capacity );
		this.chunkLength = Math.max( 1, chunkLength );
		this.rng = new Random( seed );
	}


	public long offered(){ return offered; }
	public int chunkCount(){ return chunks.size(); }
	public long replacements(){ return replacements; }


	public long retainedSteps(){
		long total = 0;
		for ( List< Transition > chunk : chunks ) total += chunk.size();
		return total;
	}


	public long dropped(){
		return Math.max( 0, offered - retainedSteps() );
	}


	public List< Transition > offer( List< Transition > incoming ){
		for ( List< Transition > chunk : split( incoming )){
			offerChunk( chunk );
		}
		return retained();
	}


	public List< Transition > retained(){
		List< Transition > out = new ArrayList<>( chunks.size() * chunkLength );
		for ( List< Transition > chunk : chunks ){
			out.addAll( chunk );
		}
		return out;
	}


	/**
	 * Drops every retained chunk and starts again, keeping the seeded sequence.
	 *
	 * <p>Used when a generation is replayed into the same reservoir: the replacement decisions a
	 * second pass makes must not depend on what the first pass happened to keep.
	 */
	public void reset(){
		chunks.clear();
		seen = 0;
		offered = 0;
		replacements = 0;
	}


	public List< List< Transition > > retainedChunks(){
		return new ArrayList<>( chunks );
	}


	private void offerChunk( List< Transition > chunk ){
		seen++;
		offered += chunk.size();

		if (chunks.size() < capacity){
			chunks.add( chunk );
			return;
		}

		long slot = nextLongBounded( seen );
		if (slot >= capacity ) return;

		chunks.set( (int) slot, chunk );
		replacements++;
	}


	private long nextLongBounded( long bound ){
		if (bound <= 0) return 0;
		long r = rng.nextLong() % bound;
		return r < 0 ? r + bound : r;
	}


	public static List< List< Transition > > split( List< Transition > transitions ){
		List< List< Transition > > out = new ArrayList<>();
		if (transitions == null || transitions.isEmpty()) return out;

		List< Transition > current = new ArrayList<>();
		int episode = Integer.MIN_VALUE;
		int step = Integer.MIN_VALUE;

		for (Transition t : transitions){
			boolean adjacent = t.episodeId >= 0
					&& !current.isEmpty()
					&& t.episodeId == episode
					&& t.stepIndex == step + 1;

			if (!adjacent){
				if (!current.isEmpty()) out.add( current );
				current = new ArrayList<>();
			}
			current.add( t );
			episode = t.episodeId;
			step = t.stepIndex;
		}

		if (!current.isEmpty()) out.add( current );
		return out;
	}
}