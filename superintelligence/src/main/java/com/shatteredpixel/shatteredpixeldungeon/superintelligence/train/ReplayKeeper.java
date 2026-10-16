package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReplayKeeper {

	private final Map< String, Replay > bestPerSeed = new LinkedHashMap<>();

	private Replay worst;

	private int considered;

	public void consider( Replay replay ){
		if (replay == null) return;
		considered++;

		Replay best = bestPerSeed.get( replay.seedText );
		if (best == null || replay.score > best.score){
			bestPerSeed.put( replay.seedText, replay );
		}

		if (worst == null || replay.score < worst.score) worst = replay;
	}

	public List< Replay > keep(){
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
}