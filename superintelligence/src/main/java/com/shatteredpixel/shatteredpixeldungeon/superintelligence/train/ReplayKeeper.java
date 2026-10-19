package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;

import java.io.File;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReplayKeeper {

	public static final int DEFAULT_SEEDS = 256;

	private final int maxSeeds;

	private final Map< String, Replay > bestPerSeed = new LinkedHashMap<>();

	private Replay worst;

	private int considered;

	public ReplayKeeper(){
		this( DEFAULT_SEEDS );
	}

	public ReplayKeeper( int maxSeeds ){
		this.maxSeeds = Math.max( 1, maxSeeds );
	}

	public synchronized void consider( Replay replay ){
		if (replay == null) return;
		considered++;

		if (worst == null || replay.score < worst.score) worst = replay;

		Replay best = bestPerSeed.get( replay.seedText );
		if (best != null && replay.score <= best.score) return;
		if (best == null && bestPerSeed.size() >= maxSeeds){
			Replay lowest = lowestBest();
			if (replay.score <= lowest.score) return;
			bestPerSeed.remove( lowest.seedText );
		}
		bestPerSeed.put( replay.seedText, replay );
	}

	private Replay lowestBest(){
		Replay lowest = null;
		for (Replay r : bestPerSeed.values()){
			if (lowest == null || r.score < lowest.score) lowest = r;
		}
		return lowest;
	}

	public synchronized List< Replay > keep(){
		List< Replay > kept = new ArrayList<>( bestPerSeed.values() );
		if (worst != null && !kept.contains( worst )) kept.add( worst );
		return kept;
	}

	public int considered(){
		return considered;
	}

	public int kept(){
		return keep().size();
	}

	public Replay worst(){
		return worst;
	}

	public Collection< Replay > bestPerSeed(){
		return bestPerSeed.values();
	}

	public int write( File dir, int generation, String group ){
		int saved = 0;
		for (Replay replay : keep()){
			replay.generation = generation;
			replay.group = group;

			File file = uniqueFile( dir, replay.seedText );
			try {
				ReplayIO.write( replay, file );
				saved++;
			} catch (IOException e){
				System.err.println( "[WARN] could not save replay for seed "
						+ replay.seedText + ": " + e.getMessage() );
			}
		}
		return saved;
	}

	private static File uniqueFile( File dir, String seed ){
		String stem = seed.isEmpty() ? "random" : Worker.safeFileName( seed );
		File file = new File( dir, stem + ".dat" );
		int run = 2;
		while (file.exists() && run < 10000){
			file = new File( dir, stem + "-r" + run + ".dat" );
			run++;
		}
		return file;
	}
}
