/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shatteredpixel.shatteredpixeldungeon.desktop.replay;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;

/**
 * Playback state for a replay being watched in the rendered game.
 *
 * Holds the recording and the cursor into it, and nothing else. No game state, no rendering, no
 * timing - {@link ReplayPlayer} owns all of that. Keeping this class free of engine references is
 * deliberate: it is the one part of the viewer that can be tested headlessly, and the part where an
 * off-by-one would show up as a silent desync rather than a visible glitch.
 *
 * Playback advances one recorded step at a time. Each step names an action, a secondary choice, and
 * the hero position the recording expects afterwards. That last field is what makes a divergence
 * detectable rather than merely visible: if the hero is somewhere else, something in the engine no
 * longer matches the build that made the recording.
 */
public class ReplayPlayback {

	private final Replay replay;

	/** Index of the next step to apply. */
	private int cursor = 0;

	/** Step where the live hero position first failed to match the recording, or -1. */
	private int divergedAt = -1;

	private int expectedPos = -1;

	/** True once every recorded step has been applied, or the run ended. */
	private boolean finished = false;

	public ReplayPlayback( Replay replay ){
		this.replay = replay;
	}

	public Replay replay(){
		return replay;
	}

	public int total(){
		return replay.steps.size();
	}

	public int cursor(){
		return cursor;
	}

	public boolean finished(){
		return finished;
	}

	public int divergedAt(){
		return divergedAt;
	}

	public boolean diverged(){
		return divergedAt >= 0;
	}

	/** Hero position the current step expects, or -1 when no step is pending. */
	public int expectedPos(){
		return expectedPos;
	}

	/**
	 * @return the step to apply now, or null when playback is done
	 */
	public Replay.Step peek(){
		if (finished || cursor >= replay.steps.size()) return null;
		expectedPos = replay.steps.get( cursor ).heroPos;
		return replay.steps.get( cursor );
	}

	/** Marks the pending step as applied. */
	public void advance(){
		cursor++;
		expectedPos = -1;
		if (cursor >= replay.steps.size()) finished = true;
	}

	public void finish(){
		finished = true;
		expectedPos = -1;
	}

	/**
	 * Checks the live hero position against what the recording expects.
	 *
	 * Called after a step is applied and the engine has settled. A mismatch is the single most
	 * useful signal this viewer produces: it means the recording and the current build disagree,
	 * which invalidates every replay comparison built on it.
	 *
	 * @param heroPos where the hero actually is now
	 * @return true if this step matched
	 */
	public boolean checkPosition( int heroPos ){
		if (divergedAt >= 0) return false;

		int expected = expectedPos;
		if (expected < 0 || heroPos == expected) return true;

		divergedAt = cursor;
		return false;
	}

	/** Hero class from the recording, falling back rather than throwing on an unknown name. */
	public HeroClass heroClass(){
		try {
			return HeroClass.valueOf( replay.heroClass );
		} catch (IllegalArgumentException e){
			return HeroClass.WARRIOR;
		}
	}

	/** One-line summary for the HUD. */
	public String status(){
		if (diverged()){
			return "DIVERGED at step " + ( divergedAt + 1 )
					+ " - expected hero at " + replay.steps.get( divergedAt ).heroPos;
		}
		if (finished) return "finished - " + total() + " steps";
		return "step " + ( cursor + 1 ) + " / " + total();
	}
}
