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

	/** Where the hero actually was when it diverged. -1 until it does. */
	private int actualAt = -1;

	/** What disagreed, naming the quantity. Empty until it does. */
	private String reason = "";

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

	/**
	 * Returns to the first step.
	 *
	 * Used by the viewer's restart, which also rebuilds the level: the live game cannot be unwound
	 * on its own, so a restart has to be a genuine fresh start rather than a cursor reset that
	 * leaves the map sitting wherever the run ended.
	 */
	public void rewind(){
		cursor = 0;
		expectedPos = -1;
		divergedAt = -1;
		actualAt = -1;
		finished = false;
	}

	public void finish(){
		finished = true;
		expectedPos = -1;
		actualAt = -1;
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
		if (expected >= 0 && heroPos != expected){
			divergedAt = cursor;
			actualAt = heroPos;
			reason = "hero at " + heroPos + ", recording says " + expected;
			return false;
		}
		return true;
	}

	/**
	 * Checks the live hero against the rest of the state the recording carries.
	 *
	 * <p>Position alone is not enough to decide whether a run is still matching. A hero who is starving
	 * stands exactly where a fed hero stands, and one who is a turn out of step is still on the right
	 * cell - so a recording that stored only a position could not see either, and the viewer would carry
	 * on for hundreds of steps before something unrelated broke.
	 *
	 * <p>Called after {@link #checkPosition} passes, so the reported reason names the earliest thing
	 * that actually differed.
	 *
	 * @return true if health, engine time and inventory all still match
	 */
	public boolean checkState( int heroHp, float turn, String inventory ){
		if (divergedAt >= 0) return false;

		Replay.Step step = currentStep();
		if (step == null) return true;

		if (step.heroHp >= 0 && heroHp != step.heroHp){
			divergedAt = cursor;
			reason = "hp is " + heroHp + ", recording says " + step.heroHp;
			return false;
		}

		if (step.turn >= 0 && turn != step.turn){
			divergedAt = cursor;
			reason = "engine time is " + turn + ", recording says " + step.turn;
			return false;
		}

		if (!step.inventory.isEmpty() && !step.inventory.equals( inventory )){
			divergedAt = cursor;
			reason = "inventory is [" + inventory + "], recording says [" + step.inventory + "]";
			return false;
		}

		return true;
	}

	private Replay.Step currentStep(){
		return cursor < replay.steps.size() ? replay.steps.get( cursor ) : null;
	}

	/** Where the hero actually was when playback diverged, or -1. */
	public int actualPos(){
		return actualAt;
	}

	/** Hero class from the recording, falling back rather than throwing on an unknown name. */
	public HeroClass heroClass(){
		try {
			return HeroClass.valueOf( replay.heroClass );
		} catch (IllegalArgumentException e){
			return HeroClass.WARRIOR;
		}
	}

	/**
	 * One-line summary for the HUD.
	 *
	 * <p>Names the action and the mode as well as the two positions. "expected hero at 834" on its own
	 * says a mismatch happened and nothing about what was being attempted, which is the part that
	 * distinguishes a stale recording from a broken viewer - the recorded action tells you which of
	 * the two you are looking at.
	 */
	public String status(){
		if (diverged()){
			Replay.Step step = replay.steps.get( divergedAt );
			return "DIVERGED at step " + ( divergedAt + 1 ) + " - " + step.action + "/" + step.slot
					+ " in " + step.mode + ": " + reason;
		}
		if (finished) return "finished - " + total() + " steps";
		return "step " + ( cursor + 1 ) + " / " + total();
	}
}
