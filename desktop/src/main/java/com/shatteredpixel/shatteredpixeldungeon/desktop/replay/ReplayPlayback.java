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

	/**
	 * Level width, for rendering a cell as coordinates.
	 *
	 * <p>Set by the viewer from the live level rather than stored per step, because it is a property of
	 * the dungeon rather than of the recording: every floor is the same size, and a recording does not
	 * say. Zero until set, and {@link #coordinates} falls back to the raw index in that case rather than
	 * dividing by a width it does not have.
	 */
	private int gridWidth = 0;

	private int expectedPos = -1;

	/** True once every recorded step has been applied, or the run ended. */
	private boolean finished = false;

	public ReplayPlayback( Replay replay ){
		this.replay = replay;
	}

	public Replay replay(){
		return replay;
	}

	/**
	 * The level width to decode cell indices with.
	 *
	 * <p>Called by the viewer once a level exists. Zero, the default, prints the raw index - which is
	 * what every report did before coordinates existed, so the fallback is a working answer rather than
	 * a broken one.
	 */
	public void gridWidth( int width ){
		this.gridWidth = width;
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
	 * The step after {@link #peek()}, or null at the end. Look-ahead only; does not touch the cursor.
	 */
	public Replay.Step peekNext(){
		int next = cursor + 1;
		if (next < 0 || next >= replay.steps.size()) return null;
		return replay.steps.get( next );
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
			//Both cells as coordinates, and both raw. A single 3-digit index says nothing about where
			//on the floor the hero is, and the reader of a failed replay is looking at a tile map.
			reason = "hero at " + coordinates( heroPos, gridWidth )
					+ ", recording says " + coordinates( expected, gridWidth );
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

	// --------------------------------------------------------------------------- score

	/**
	 * Running score through the step played so far, from the rewards the recording carries.
	 *
	 * <p>Read from the recording rather than recomputed from the live world, because a replay's job is
	 * to report what the recording says happened. The viewer had only the run's final total on screen,
	 * which cannot answer the question someone watching a recording actually has - which action cost
	 * 100 points - and cannot distinguish a run that climbed steadily to 40 from one that reached 40 and
	 * gave most of it back.
	 *
	 * <p>Up to and including the settled step, so it is the score as of the step on screen rather than
	 * one the viewer has not reached.
	 */
	public double score(){
		double total = 0;
		for (int i = 0; i <= cursor && i < replay.steps.size(); i++){
			total += replay.steps.get( i ).reward;
		}
		return total;
	}

	/**
	 * Reward the step just played, which is the delta rather than the running total.
	 *
	 * <p>{@link Replay.Step#reward} is the reward for that step, not a cumulative one - it is the value
	 * {@code ReplayRecorder.afterStep} is handed, which is what one {@code env.step} returned. Presenting
	 * it as the running score would have made every step look like the whole run so far.
	 */
	public double stepReward(){
		Replay.Step step = currentStep();
		if (step == null && cursor > 0) step = replay.steps.get( cursor - 1 );
		return step == null ? 0 : step.reward;
	}

	/**
	 * Total reward gained, summing only the positive steps.
	 *
	 * <p>The recording's own sum is a net figure: a run that gained 90 and lost 50 scores 40, and the
	 * two halves carry different information about a policy - one says the reward function is being
	 * collected, the other says the agent is being punished. A net number cannot tell them apart, which
	 * is why the ledger has a per-term table and the viewer had nothing at all.
	 *
	 * @see #lost()
	 */
	public double gained(){
		double total = 0;
		for (int i = 0; i <= cursor && i < replay.steps.size(); i++){
			double r = replay.steps.get( i ).reward;
			if (r > 0) total += r;
		}
		return total;
	}

	/** Total reward lost, summing only the negative steps. Negative, or zero before any loss. */
	public double lost(){
		double total = 0;
		for (int i = 0; i <= cursor && i < replay.steps.size(); i++){
			double r = replay.steps.get( i ).reward;
			if (r < 0) total += r;
		}
		return total;
	}

	// --------------------------------------------------------------------------- coordinates

	/**
	 * A hero cell as {@code x, y}, which is what a person reading a tile map needs.
	 *
	 * <p>{@code heroPos} is {@code y * width + x} - the engine's own packing, and correct for indexing
	 * but unreadable: 687 tells you nothing, and comparing two of them means doing the division in your
	 * head. Every divergence report in this project printed the raw index, so "hero at 687, recording
	 * says 655" was the whole of what a failed recording said about where the hero was.
	 *
	 * <p>x grows to the right and y grows downward, which is the engine's own order and therefore the
	 * one that matches the tile map and every trace in the project. y is <em>not</em> flipped to put the
	 * origin at the bottom left: that would be prettier on its own and would disagree with
	 * {@code Level} and with every recorded {@code heroPos}.
	 *
	 * <p>Returns the raw index too, because a divergence report is often being compared against a trace
	 * or a replay file, and silently changing the coordinate system would break that.
	 *
	 * @param width the level's width, or a non-positive value when there is no level to ask
	 */
	public String coordinates( int pos, int width ){
		if (pos < 0 || width <= 0) return "pos " + pos;
		return "(" + ( pos % width ) + ", " + ( pos / width ) + ") pos " + pos;
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




