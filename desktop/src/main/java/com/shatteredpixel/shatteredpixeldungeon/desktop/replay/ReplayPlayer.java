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

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Quickslots;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;

/**
 * Replays a recording by applying its actions to the live game.
 *
 * The injection seam is the point of the whole design. The game's own input path is
 *
 * <pre>
 *   CellSelector.onSignal(KeyEvent) -&gt; heldAction1/2/3
 *     -&gt; directionFromAction       -&gt; Dungeon.hero.handle( cell )
 *     -&gt; Dungeon.hero.next()
 * </pre>
 *
 * and {@code ActionMapper} already wraps exactly that pair, because the headless trainer needs the
 * same seam. So this class replays through the game's real action resolution rather than
 * synthesising key events: attack-versus-loot-versus-stairs-versus-menu resolves identically to a
 * human playing, which is the only way a replay is worth watching.
 *
 * Timing matters and is easy to get wrong. {@link Actor#headlessStep()} must run before testing
 * {@code hero.ready} for the same reason the headless pipeline does it: injecting an action sets
 * {@code curAction} and clears {@code ready} only inside {@code Hero.act()}, so testing readiness
 * first would see a stale {@code true} forever and the run would sit still - a stall that looks
 * exactly like a policy that has chosen not to move.
 */
public class ReplayPlayer {

	/** Seconds between steps at 1x. Without this a 1500-step replay flashes past unreadably. */
	public static final float BASE_STEP_SECONDS = 0.06f;

	/**
	 * Speed for unattended playback, set with {@code -Dspd.fast}.
	 *
	 * <p>High enough that a 1500-step replay finishes in seconds. Only one step is applied per frame, so
	 * playback stays correct and only gets quicker - there is no frame-skipping and no path taken that a
	 * 1x run would not take.
	 */
	private static final float FAST_SPEED =
			floatProperty( "spd.fast", 1f );

	private static float floatProperty( String name, float fallback ){
		String raw = System.getProperty( name );
		if (raw == null ) return fallback;
		try {
			return Float.parseFloat( raw.trim() );
		} catch (NumberFormatException e){
			return fallback;
		}
	}

	private final ReplayPlayback playback;
	private final ActionMapper mapper;

	private float sinceStep = 0;
	private float speed = FAST_SPEED;
	private boolean playing = true;

	/**
	 * Takes turn scheduling away from the game, for as long as this player exists.
	 *
	 * <p>Set in the constructor rather than at playback start, because the game spawns its scheduler
	 * thread from {@code GameScene.update()} as soon as the level loads, which is before the first frame
	 * this runs on.
	 *
	 * <p>See {@link #update(float)} for why playback cannot share the scheduler with the render thread.
	 */
	/** Set once a step has been applied and the engine has not yet settled for the next. */
	private boolean awaitingSettle = false;

	private int settledPosition = -1;

	/** Why playback stopped, for the HUD. Empty while playing. */
	private String haltReason = "";

	/**
	 * Set once playback has diverged from the recording.
	 *
	 * A diverged replay can never become correct again - the live game has already taken a different
	 * path - so resuming is refused. Without this, pressing pause after a divergence restarted
	 * playback, immediately re-detected the same divergence and halted again, printing the same
	 * message every press and looking like the control was not working.
	 */
	private boolean diverged = false;

	public ReplayPlayer( Replay replay ){
		this.playback = new ReplayPlayback( replay );
		this.mapper = new ActionMapper( new EnvConfig() );
		Actor.manualScheduling = true;
	}

	public ReplayPlayback playback(){
		return playback;
	}

	public float speed(){
		return speed;
	}

	public void speed( float multiplier ){
		//clamped so a stray keypress cannot make playback unplayable or instant
		this.speed = Math.max( 0.1f, Math.min( 16f, multiplier ) );
	}

	public boolean playing(){
		return playing;
	}

	public void playing( boolean value ){
		if (diverged && value){
			//refuse to resume: the live game has already left the recording's path
			return;
		}
		this.playing = value;
	}

	public String haltReason(){
		return haltReason;
	}

	/**
	 * Advances playback. Call once per rendered frame.
	 *
	 * <p>This thread owns turn scheduling while playback runs: {@link Actor#manualScheduling} stops the
	 * game from running its own scheduler thread, and turns are advanced here with
	 * {@link Actor#headlessStep()} instead.
	 *
	 * <p>That is not an optimisation, it is a correctness requirement. The game runs
	 * {@code Actor.process()} on its own thread while this runs on the render thread, and both write
	 * {@code Hero.curAction}. An action injected here can be overwritten by {@code Hero.ready()} before
	 * the hero consumes it, and the move is then lost: no turn spent, position unchanged. Playback
	 * reported that as a divergence, and which step it appeared on varied between runs of the same
	 * recording, because the outcome was decided by thread timing rather than by the recording.
	 *
	 * <p>Using {@code headlessStep()} also makes playback follow the same actor order the trainer used to
	 * record, which is what playback is checking in the first place.
	 *
	 * @param elapsed seconds since the previous frame
	 */
	public void update( float elapsed ){
		if (!playing) return;

		sinceStep += elapsed;

		if (sinceStep < BASE_STEP_SECONDS / speed) return;

		sinceStep = 0;

		//Only ever step when the hero is not already ready.
		//
		//driveToHeroReady() always advances at least one scheduler step before it tests readiness, so
		//using it as a per-frame gate advanced the game once for every frame rendered - including the
		//frames spent waiting out the pacing timer, and at high speed that is every frame, because the
		//timer is shorter than a frame. Each step handed a turn to some other actor while the hero stood
		//ready and idle, so the world's actors advanced faster than the hero's actions did.
		if (!heroIsReady() && !driveToHeroReady()) return;

		applyNextStep();

		//Settle in this same call, with no frame boundary in between.
		//
		//Applying and settling were separate frame callbacks, and the intervening GameScene.update()
		//cleared the pending Hero.curAction before the hero ever acted: the scheduler log showed
		//curAction already null on entry, with Actor.now() unmoved and the hero's position unchanged, so
		//the recorded move was silently discarded. That is what "DIVERGED ... hero at 687, recording says
		//655" was - a lost action, not a refused one.
		//
		//The trainer never has such a gap: it applies an action and drains the scheduler in one call, and
		//it reproduces its own recordings exactly. Doing the same here removes the window rather than
		//working around it.
		if (!driveToHeroReady()) return;

		settle();
	}

	/** True when the hero can be given input now. Tests only; advances nothing. */
	private boolean heroIsReady(){
		return Dungeon.hero != null
				&& Dungeon.hero.isAlive()
				&& Dungeon.hero.curAction == null
				&& Dungeon.hero.ready
				&& Dungeon.hero.paralysed == 0;
	}

	/** Ceiling on scheduler steps spent waiting for one hero turn, mirroring the headless pipeline. */
	private static final int MAX_ACTOR_STEPS = 400;

	/**
	 * Advances turns until the hero can be given input, or reports that it cannot.
	 *
	 * <p>The readiness test is the same three conditions the headless trainer uses in
	 * {@code LevelPipeline.runToHeroReady}: no pending action, ready for input, not paralysed.
	 *
	 * <p>{@code curAction == null} is the one that matters. {@code ActionMapper} sets
	 * {@code Hero.curAction} and calls {@code Hero.next()}, which clears only {@code Actor.current};
	 * {@code Hero.ready} is cleared later, inside {@code Hero.act()}, when the action is consumed. So
	 * between applying a step and the hero acting, {@code ready} is still true from the previous turn.
	 * Testing {@code ready} alone settles during that window and compares the position of a hero who
	 * has not moved yet.
	 *
	 * <p>At least one step is always taken before readiness is tested, for the same reason: testing first
	 * would see the previous turn's {@code ready} and the hero would never act.
	 */
	private boolean driveToHeroReady(){
		for (int steps = 0; steps < MAX_ACTOR_STEPS; steps++){

			if (Dungeon.hero == null || !Dungeon.hero.isAlive()){
				playback.finish();
				halt( "run ended - hero is dead" );
				return false;
			}

			Actor.headlessStep();

			if (Dungeon.hero.curAction == null && Dungeon.hero.ready && Dungeon.hero.paralysed == 0){
				return true;
			}
		}

		halt( "stalled - hero did not become ready within " + MAX_ACTOR_STEPS + " turns" );
		return false;
	}

	/** True when the hero is waiting for input. */
	private boolean readyToAct(){
		if (Dungeon.hero == null) return false;
		if (!Dungeon.hero.isAlive()){
			halt( "run ended - hero is dead" );
			return false;
		}
		if (Dungeon.hero.paralysed > 0) return false;
		return Dungeon.hero.ready;
	}

	/**
	 * Applies one recorded step.
	 *
	 * A step can need two calls: the first action may open a menu, a slot choice, or a targeting
	 * prompt, and the recording stores the follow-up on the next step. The recording's own
	 * {@code mode} field says which case this is, so the recorded mode is used rather than the live
	 * one - if they disagree the recording is already wrong and the divergence check will say so.
	 */
	private void applyNextStep(){
		Replay.Step step = playback.peek();
		if (step == null){
			playback.finish();
			//halt() rather than a bare finish(): finishing is not by itself an exit, and nothing else
			//calls halt on the normal path. Reaching the end of the recording therefore left the window
			//open with playback stopped, waiting for a keypress that a batch run has no way to send - so
			//a run that had finished successfully looked identical to one that had hung. This is what made
			//"no divergence reported" indistinguishable from "still going".
			halt( "replay finished - all " + playback.total() + " steps played" );
			return;
		}

		Action action;
		try {
			action = Action.valueOf( step.action );
		} catch (IllegalArgumentException e){
			halt( "unknown action in recording: " + step.action );
			return;
		}

		EnvMode mode = modeOf( step.mode );

		//Restore the bindings this step was recorded under, before the slot index is resolved.
		//
		//A recorded slot index only means something alongside the quickslot bindings in force when it was
		//chosen: refreshSlots puts bound items into their bound slots and fills the rest from the
		//backpack, so the same index names a different item under different bindings. Measured on
		//KXY-JHB-LXK, a recorded DROP on slot 0 resolved to the VelvetPouch in the trainer and to the
		//Waterskin in the viewer - the trainer had the Waterskin bound to quickslot 1, the viewer had no
		//bindings. The wrong item was dropped, which changed Backpack.capacity from 21 to 20, and every
		//later INTERACT resolved against a different inventory.
		//
		//Restored per step, not once: equipping or unequipping rebinds a slot mid-run.
		if (mode == EnvMode.SLOT || mode == EnvMode.INVENTORY){
			if (step.quickslots == null || step.quickslots.isEmpty()){
				halt( "step " + playback.cursor() + " names slot " + step.slot
						+ " but records no quickslot bindings; it was recorded before bindings were stored,"
						+ " so the index cannot be resolved" );
				return;
			}
			if (!Quickslots.applicable( step.quickslots )){
				halt( "step " + playback.cursor() + " records quickslots [" + step.quickslots
						+ "] that do not match what the hero is carrying; cannot resolve slot "
						+ step.slot );
				return;
			}
			Quickslots.restore( step.quickslots );
		}

		//the follow-up half of a two-step action
		if (mode == EnvMode.SLOT || mode == EnvMode.INVENTORY || mode == EnvMode.MENU){
			mapper.applySecondary( mode, action, step.slot );
		} else if (mode == EnvMode.TARGETING){
			mapper.applySecondary( mode, action, step.slot );
		} else {
			mapper.apply( action, step.slot );
		}

		mapper.refreshSlots();

		//Advance only after settle() has compared the landing cell. advance() clears the playback's
		//expected position, so advancing first meant checkPosition() always saw "no expectation" and
		//silently passed - the on-screen divergence check could never fire.
		awaitingSettle = true;
	}

	/**
	 * Called once the hero has taken the applied step, so it has resolved.
	 *
	 * <p>Compares where the hero landed against the recording. Runs no scheduler work; the caller has
	 * already advanced turns until the hero was ready again.
	 *
	 * <p>Nothing clears {@code Hero.resting} here. A resting hero is still flagged ready, so a recorded
	 * REST resolves after the single turn the trainer also spends on it. An earlier version forced the
	 * flag clear, which made REST cost zero turns and stopped matching the trainer.
	 */
	private void settle(){
		if (Dungeon.hero == null) return;

		if (Dungeon.hero.isAlive()){
			settledPosition = Dungeon.hero.pos;
			trace( settledPosition );
			if (!playback.checkPosition( settledPosition )
					|| !playback.checkState( Dungeon.hero.HP, Actor.now(), ReplayRecorder.inventory() )){
				diverged = true;
				halt( playback.status() );
			}
			playback.advance();
		} else {
			playback.finish();
			halt( "run ended" );
		}

		awaitingSettle = false;
	}

	/**
	 * Prints one line per settled step.
	 *
	 * <p>Enabled with {@code -Dspd.trace}. The expected position is included alongside the actual one,
	 * because a step where the two agree but the turn count does not is a different fault from one where
	 * the positions differ, and the position alone cannot tell them apart.
	 */
	private void trace( int pos ){
		if (!TRACE) return;
		Replay.Step s = playback.peek();
		System.err.println( "[trace] " + playback.cursor() + " "
				+ ( s == null ? "?" : s.action + "/" + s.slot ) + " "
				+ ( s == null ? "?" : s.mode ) + " pos=" + pos + " hp=" + Dungeon.hero.HP
				+ " turn=" + Actor.now() + " exp=" + playback.expectedPos() );
	}

	/** Enabled with -Dspd.trace. Off by default: this is a diagnostic, not a feature. */
	private static final boolean TRACE = System.getProperty( "spd.trace" ) != null;

	/** Set when playback has ended and the window may be closed. See halt(). */
	private boolean closingWhenSettled = false;

	/**
	 * Stops playback.
	 *
	 * <p>On a divergence the window can close itself. Playback cannot continue after the live game has
	 * left the recording's path - {@link #playing(boolean)} already refuses to resume - so an open window
	 * showing a frozen dungeon conveys nothing and has to be dismissed by hand. A harness running
	 * recordings in sequence needs that too, or it blocks on every one.
	 *
	 * <p>Enabled with {@code -Dspd.autoCloseOnDiverge}, off by default.
	 */
	private static final boolean AUTO_CLOSE_ON_DIVERGE = System.getProperty( "spd.autoCloseOnDiverge" ) != null;

	/**
	 * Closes the window when playback ends for any reason.
	 *
	 * <p>For batch verification of recordings, where the result is the exit status and the window is
	 * only in the way. Enabled with {@code -Dspd.autoClose}.
	 */
	private static final boolean AUTO_CLOSE = System.getProperty( "spd.autoClose" ) != null;

	private void halt( String reason ){
		if (playing){
			playing = false;
			haltReason = reason;
			System.err.println( "[replay] halted: " + reason );

			boolean diverged = playback.diverged();
			if (AUTO_CLOSE || ( AUTO_CLOSE_ON_DIVERGE && diverged ) ){
				closeWhenReportingSettles();
			}
		}
	}

	/**
	 * Asks the frame loop to close once this frame's reporting has finished.
	 *
	 * <p>Deferred by one frame so the HUD and the stderr line describing the divergence are written
	 * before the window goes away. Closing inside {@link #halt} would discard the message that explains
	 * why it closed.
	 */
	private void closeWhenReportingSettles(){
		closingWhenSettled = true;
	}

	/** True once playback has ended and the frame loop may close the window. */
	public boolean closing(){
		return closingWhenSettled;
	}

	/**
	 * True once playback has stopped, whether or not the window is closing.
	 *
	 * <p>Distinct from {@link #playing()} so a stopped run can be reported rather than left looking like
	 * a run still in progress.
	 */
	public boolean finished(){
		return !playing && haltReason != null && !haltReason.isEmpty();
	}

/**
	 * Resets to the first step, leaving the live game where it is.
	 *
	 * Only the playback half of a restart. The game half is the caller's, because it has to rebuild
	 * the level, and only the caller knows how the viewer was started.
	 */
	public void restart(){
		playing = true;
		sinceStep = 0;
		awaitingSettle = false;
		haltReason = "";
		diverged = false;
		playback.rewind();
	}

	private static EnvMode modeOf( String name ){
		try {
			return EnvMode.valueOf( name );
		} catch (IllegalArgumentException e){
			return EnvMode.WORLD;
		}
	}
}
