package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which of a run's recordings are worth keeping: the best per seed, and the single worst.
 *
 * <p>`docs.md` asks for "the best performing run of each major generation", and that is the
 * best-per-seed rule. The rule was right and its input was not: the trainer used to record only every
 * third episode, so "best per seed" meant <em>best of the one in three that happened to be
 * recorded</em>. A 15-generation run whose one floor-2 episode scored -1,276 - four times anything else
 * it did - did not have that episode in the catalog at all, because that episode was one of the two
 * thirds nobody sent.
 *
 * <p>The recording itself was never the saving. `Worker.runEpisode` builds the complete replay for
 * every episode it plays and then threw two of every three away before writing the frame, so the sample
 * paid the whole cost and saved only the transmission.
 *
 * <p>The worst is kept alongside the best because the catalog is a diagnostic, not a scoreboard. When
 * every episode stalls, the thing worth watching is the one that stalled least, and when one run is
 * being compared against another the other end of the range is the half that says which way it moved.
 * Per-seed it would double the listing for no gain - the catalogue collapses two files sharing a seed
 * into one row - so it is one recording for the whole run.
 */
public class ReplayKeeper {

	private final Map< String, Replay > bestPerSeed = new LinkedHashMap<>();

	private Replay worst;

	private int considered;

	/** Offers an episode. A null replay - an episode the worker did not send - is ignored. */
	public void consider( Replay replay ){
		if (replay == null) return;
		considered++;

		Replay best = bestPerSeed.get( replay.seedText );
		if (best == null || replay.score > best.score){
			bestPerSeed.put( replay.seedText, replay );
		}

		if (worst == null || replay.score < worst.score) worst = replay;
	}

	/**
	 * What to write, best per seed in first-seen order, plus the worst if it is not already one of them.
	 *
	 * <p>Ordered rather than hashed, so two runs of the same command write the same files in the same
	 * order.
	 */
	public List< Replay > keep(){
		List< Replay > kept = new ArrayList<>( bestPerSeed.values() );
		if (worst != null && !kept.contains( worst )) kept.add( worst );
		return kept;
	}

	/** Episodes offered, so a caller can report what fraction the sample kept. */
	public int considered(){
		return considered;
	}

	public int kept(){
		return keep().size();
	}

	/** The run's single worst episode, or null. Exposed for a report rather than for a caller to pick from. */
	public Replay worst(){
		return worst;
	}

	/** The per-seed view, for a caller that wants to count rather than write. */
	public Collection< Replay > bestPerSeed(){
		return bestPerSeed.values();
	}
}