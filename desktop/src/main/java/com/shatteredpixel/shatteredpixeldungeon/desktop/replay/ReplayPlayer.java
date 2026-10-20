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
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.noosa.Game;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SlotAction;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Quickslots;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;

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

	/**
	 * Scheduler steps taken while the current step's drain has been waiting on a resting hero.
	 *
	 * <p>A field because the drain yields the frame, and the trainer's equivalent counter - the loop
	 * variable in {@code LevelPipeline.runToHeroReady} - spans the whole drain. Reset when a step is
	 * applied, so the count is per step rather than per run.
	 */
	private int drainSteps = 0;

	private int settledPosition = -1;

	/** Why playback stopped, for the HUD. Empty while playing. */
	private String haltReason = "";

	/**
	 * Whether the viewer is rebuilding the level, so there is legitimately no hero.
	 *
	 * <p>A restart nulls {@code Dungeon.hero} and re-enters the interlevel scene, which takes at least
	 * a fade to build floor 1. Throughout that window {@link #update(float)} still runs - it is the
	 * frame driver - and a null hero used to be read as the end of the run: the drain called
	 * {@code playback.finish()} and halted with "run ended - hero is dead", on the very first frame
	 * after {@code R}. Pressing R on a finished recording therefore appeared to do nothing at all,
	 * because the HUD was overwritten with a fresh "finished" before anything could play.
	 *
	 * <p>A flag rather than a null check, because a null hero is also what a viewer that has quit to
	 * the title screen sees, and that one really is the end. The caller states which.
	 */
	private boolean rebuilding = false;

	/**
	 * Declares that the level is being rebuilt and playback should wait for a hero.
	 *
	 * <p>Already armed by {@link #restart()}; exposed for a caller that rebuilds the level without
	 * restarting playback. Cleared by the first {@link #update(float)} that finds a hero, so a rebuild
	 * that never completes - because the player quit to the title - does not leave playback suspended
	 * forever; the normal hero-is-gone path halts instead, which is the honest report there.
	 */
	public void awaitingRebuild(){
		rebuilding = true;
	}

	/** True while the viewer is between a restart and the level that restart is waiting for. */
	public boolean rebuilding(){
		return rebuilding;
	}

	/**
	 * Set once playback has diverged from the recording.
	 *
	 * A diverged replay can never become correct again - the live game has already taken a different
	 * path - so resuming is refused. Without this, pressing pause after a divergence restarted
	 * playback, immediately re-detected the same divergence and halted again, printing the same
	 * message every press and looking like the control was not working.
	 */
	private boolean diverged = false;

	/**
	 * Replays under the settings the recording was made with.
	 *
	 * <p>The config is a parameter rather than a hardcoded {@code new EnvConfig()} because a recorded
	 * slot index is resolved through it: the slot head is a fixed-width window over the inventory, so
	 * the same index names a different item at a different width, and {@code allowEquipping} decides
	 * whether a USE equips or arms an aim. A viewer that built its own config could resolve an index
	 * differently from the recorder and then report the recording as wrong.
	 */
	public ReplayPlayer( Replay replay, EnvConfig config ){
		this.playback = new ReplayPlayback( replay );
		this.mapper = new ActionMapper( config );
		Actor.manualScheduling = true;

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.RunState.clearRunStatics();

		remainsCleared = false;

		String tracePath = System.getProperty( "spd.rngTrace" );
		if (tracePath != null && !tracePath.trim().isEmpty()){
			rngTracePath = tracePath;
			rngTrace = new com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.RngTrace();
			rngTrace.dungeonSeed( Dungeon.seed );
			com.watabou.utils.RandomTrace.enable();
			com.watabou.utils.RandomTrace.attribute();
		} else {
			rngTracePath = null;
			rngTrace = null;
		}

		String worldPath = System.getProperty( "spd.worldTrace" );
		if (worldPath != null && !worldPath.trim().isEmpty()){
			worldTracePath = worldPath;
			worldSnapshot = new com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldSnapshot()
					.cells( true );
		} else {
			worldTracePath = null;
			worldSnapshot = null;
		}
	}

	/**
	 * Per-step RNG trace, written when {@code -Dspd.rngTrace=<path>} is set.
	 *
	 * <p>Null unless asked for, so an ordinary playback costs one null check per step. The rendered trace
	 * is the golden side of {@code rngtrace}: it is the only artefact in the project produced by the game
	 * as a player sees it, and so the only one capable of disagreeing with a headless run.
	 *
* <p>Counters start at zero when the <em>first step is applied</em>, not when the player is
	 * constructed. The viewer is installed before the scene has finished building, and the level
	 * decoration in between draws roughly 2800 values - so arming at construction made every rendered
	 * trace begin mid-stream and could never match a headless one, which starts at zero by contract.
	 */
	private final com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.RngTrace rngTrace;
	private final String rngTracePath;
	private boolean rngTraceArmed = false;

	/**
	 * Per-frame record of the world, and per-step alongside it, including the cells.
	 *
	 * <p>Off unless {@code -Dspd.worldTrace} names a file. It exists because {@link #rngTrace} and the
	 * viewer trace are both keyed to a settled step, so neither can see a turn taken on a frame that
	 * applied no recorded step - which is the one thing a frame-driven viewer does that the trainer does
	 * not.
	 */
	private final com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldSnapshot worldSnapshot;
	private final String worldTracePath;

	/**
	 * Whether this player has already removed the remains its own session left.
	 *
	 * <p>A field rather than something done in {@code halt()}, because the cleanup has to survive every
	 * path that ends playback and none of them owns it: halting, finishing, diverging and quitting are four
	 * different exits, and "playback is no longer playing" is the one state all four share.
	 */
	private boolean remainsCleared = true;

	private void flushRngTrace(){
		if (rngTrace == null) return;
		try {
			rngTrace.writeTo( java.nio.file.Paths.get( rngTracePath ));
		} catch (java.io.IOException e){
			System.err.println( "[replay] could not write the rng trace to " + rngTracePath
					+ " (" + e.getClass().getSimpleName() + ")" );
		}
		System.err.println( "[replay] wrote " + rngTrace.size() + " rng trace samples to " + rngTracePath );
	}

	/**
	 * Writes the draws-by-site tally for the window before the first replayed step.
	 *
	 * <p>That window is where a headless run and a rendered run come to differ, and a bare count cannot
	 * say why. The tally names the code responsible, which is what makes the offset fixable rather than
	 * merely measurable - and the fix belongs at the site, never downstream of it.
	 */
	/**
	 * True when this is the last recorded step and the recording declares why the run ended.
	 *
	 * <p>Consulted from the drain rather than from {@link #update(float)}, which is what makes the last
	 * step of a death recording play: the step is applied, the scheduler runs until the hero has
	 * actually performed it, and only then is the recording declared complete. Asking earlier - before
	 * the hero acted - ended playback with the recorded action injected and never performed, which is
	 * the visible symptom of a death that never happens on screen.
	 *
	 * <p>Absent in versions 1 and 2 of the format, which leave the reason unknown. Such a recording is
	 * played to its last step and then ends as an ordinary completion.
	 */
	private boolean recordingEndsHere(){
		if (playback.cursor() != playback.total() - 1) return false;
		return !recordingTermination().isEmpty();
	}

	private String recordingTermination(){
		return playback.replay().termination == null ? "" : playback.replay().termination;
	}

	private void flushDrawSites( String suffix ){
		if (rngTrace == null) return;

		java.nio.file.Path out = java.nio.file.Paths.get( rngTracePath + ".sites" + suffix );
		try {
			java.nio.file.Files.write( out,
					String.join( "\n", com.watabou.utils.RandomTrace.siteReport() )
							.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
System.err.println( "[replay] wrote " + com.watabou.utils.RandomTrace.sites().size()
				+ " draw sites over " + com.watabou.utils.RandomTrace.draws() + " draws to " + out );
		} catch (java.io.IOException e){
			System.err.println( "[replay] could not write the draw-site tally to " + out
					+ " (" + e.getClass().getSimpleName() + ")" );
		}

		String prefix = rngTracePath + suffix;
		try {
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.RngOrderLog
					.writeCurrent( prefix );
		} catch (java.io.IOException e){
			System.err.println( "[replay] could not write the ordered draw logs to " + prefix
					+ " (" + e.getClass().getSimpleName() + ")" );
		}
	}

	/** Replays under the config the header carries. */
	public ReplayPlayer( Replay replay ){
		this( replay, ReplayIO.configFor( replay ) );
	}

	public ReplayPlayback playback(){
		return playback;
	}

	public float speed(){
		return speed;
	}

	/**
	 * Sets the playback speed.
	 *
	 * <p>No upper bound worth the name: one step is applied per frame however fast the multiplier is, so
	 * the only thing a large speed changes is how many frames a step takes. The cap that used to be here
	 * was 16x, and it silently clamped the gate's own speed of 40 - so the gate ran at 16x while
	 * believing it ran at 40x, and the number of frames a step consumed was not the number the gate's
	 * timeout had been sized for.
	 *
	 * <p>The ceiling that remains keeps the value finite: a speed of infinity would make
	 * {@code BASE_STEP_SECONDS / speed} zero, which is the same as a very large speed, and a NaN would
	 * make the comparison false and step on every frame.
	 */
	public void speed( float multiplier ){
		if (Float.isNaN( multiplier )) return;
		this.speed = Math.max( 0.1f, Math.min( 100000f, multiplier ) );
	}

	public boolean playing(){
		return playing;
	}

	public void playing( boolean value ){
		if (diverged && value){
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

		if (rebuilding){
			if (Dungeon.hero != null){
				rebuilding = false;
			} else {
				frameBoundary( false, elapsed, null );
				return;
			}
		}

		sinceStep += elapsed;

		if (sinceStep < BASE_STEP_SECONDS / speed){
			frameBoundary( false, elapsed, null );
			return;
		}

		sinceStep = 0;

		if (awaitingSettle){
			boolean owed = owesNoTurn();
			Drain drain = owed ? Drain.READY : driveToHeroReady();
			if (drain == Drain.READY) settle();
			frameBoundary( owed, elapsed, drain );
			return;
		}

		if (!readyToAct() && driveToHeroReady() != Drain.READY){
			frameBoundary( false, elapsed, null );
			return;
		}

		Actor holding = Actor.currentActor();
		if (holding != null && holding != Dungeon.hero) stepsAppliedMidTurn++;

		applyNextStep();

		Drain after = owesNoTurn() ? Drain.READY : driveToHeroReady();
		if (after == Drain.READY) settle();
		frameBoundary( true, elapsed, after );
	}

	/**
	 * Records the world at a frame boundary, if a snapshot is being taken.
	 *
	 * <p>Every existing viewer-side observer is keyed to a settled step, so a world change on a frame
	 * that applied no step is invisible to all of them by construction. That is the gap this closes: the
	 * suspect is a mob acting while the frame driver has yielded the frame to the animation that will
	 * resolve its own attack, and only a per-frame record can see it.
	 *
	 * <p>Sampling is on the frame's own thread, immediately after the drain, so what it captures is the
	 * world as the scheduler left it - the animation callbacks in {@code GameScene.super.update} have
	 * not run yet. A change caused by one of those therefore shows up in the <i>next</i> frame's record,
	 * which is still attributable: the record before it is the same instant in the previous frame.
	 *
	 * @param appliedStep whether this frame applied a recorded step
	 */
/**
	 * What the animation clock has actually advanced by.
	 *
	 * <p>Recorded on every frame because a pinned delta that is not reaching {@code MovieClip} is
	 * invisible from anywhere else: the player's own pacing would look correct and every animation would
	 * still run on real frame time. {@code Game.elapsed} is what animations accumulate and
	 * {@code timeTotal} is its running sum, so their per-frame difference is exactly what the animation
	 * clock saw - the only way to tell a pin that landed from one that did not.
	 *
	 * @param elapsed the delta handed to this player, after {@link FrameDelta}
	 * @param playerDelta recorded alongside so a disagreement between the two clocks is visible
	 */
	private void frameBoundary( boolean appliedStep, float elapsed, Drain drain ){
	int label = frames++;
		if (appliedStep) steppedFrames++;

		attributeTurn( Actor.now() );

		if (worldSnapshot == null) return;
		worldSnapshot.sample( "frame", label )
				.frame( "frame", label, elapsed, drain, appliedStep )
				.gate( readyToAct(), describeCurrent(), animatingCount() )
				.clock( "frame", label, elapsed, com.watabou.noosa.Game.elapsed,
						com.watabou.noosa.Game.timeTotal, com.watabou.noosa.Game.timeScale )
				.turns( lastNow, Actor.now(), lastTurnTaker, spentTurns );
	}

	/** Engine time at the previous frame boundary, so a frame record can say what it advanced by. */
	private float lastNow = Float.NaN;

	/**
	 * Who the scheduler handed the last turn to, sampled at each boundary.
	 *
	 * <p>This is the attribution the per-frame roster cannot supply. A roster record says what a mob's
	 * cooldown is now; it does not say that the cooldown changed <i>on this frame</i>, or which actor
	 * caused it. Since a frame that applied no recorded step is exactly where an unaccounted turn
	 * appears, the two facts have to be on the same record for the question "did the world move while
	 * the player was waiting" to be answerable at all.
	 *
	 * <p>Attributed to {@code Actor.currentActor()} at the boundary rather than to whoever was
	 * selected inside the drain, because a drain that takes several turns ends holding only its last.
	 */
	private String lastTurnTaker = "-";

	/** Frames on which engine time advanced without a recorded step being applied. */
	private int spentTurns;

	/**
	 * Compares engine time against the previous frame and records who moved it.
	 *
	 * <p>Called at the boundary, so a change caused by an animation callback in
	 * {@code GameScene.super.update} - which runs after the drain - is attributed to the next frame's
	 * record. That is still attributable: the record before it is the same instant a frame earlier.
	 */
	private void attributeTurn( float now ){
		if (Float.isNaN( lastNow )){ lastNow = now; return; }

		if (now != lastNow){
			Actor who = Actor.currentActor();
			lastTurnTaker = who == null ? "-" : who.getClass().getSimpleName() + "@" + who.id();
			spentTurns++;
		}

		lastNow = now;
	}

	/**
	 * Who the scheduler handed a turn to and has not yet heard back from, or "-" when nobody has.
	 *
	 * <p>{@code Actor.current} is set by {@code headlessStep} and cleared only by {@code next()}, which
	 * is what {@code onAttackComplete} calls. So a non-null value means an actor has taken a turn and has
	 * not completed it - the state the hero must not be given input in.
	 */
	private String describeCurrent(){
		Actor who = Actor.currentActor();
		return who == null ? "-" : who.getClass().getSimpleName() + "@" + who.id();
	}

	/**
	 * How many actors have a non-looping animation in flight.
	 *
	 * <p>Covers the case {@code Actor.current} cannot: a mob that attacked while another was still
	 * mid-swing has already cleared {@code current}, but its own animation has not resolved and its hit
	 * has not landed.
	 */
	private int animatingCount(){
		int n = 0;
		if (Dungeon.hero != null && Dungeon.hero.sprite != null
				&& Dungeon.hero.sprite.animationInFlight()) n++;
		if (Dungeon.level != null){
			for (Mob mob : Dungeon.level.mobs){
				if (mob.sprite != null && mob.sprite.animationInFlight()) n++;
			}
		}
		return n;
	}

	/**
	 * How many frames applied a recorded step, and how many did not.
	 *
	 * <p>The ratio is the frame-rate dependence made measurable. A recording that needs 20 frames per
	 * step is one whose playback timing is decided by animation duration rather than by the recording,
	 * and it is those recordings whose failure depends on how fast the machine renders.
	 */
	private int frames;
	private int steppedFrames;

	/**
	 * Recorded steps the environment refused, out of steps applied.
	 *
	 * <p>Not a divergence and not asserted on. It exists because the boolean {@code ActionMapper.apply}
	 * returns was being discarded on this path, which made a refused action - a move onto a
	 * non-adjacent cell, a slot index resolving to nothing - indistinguishable from a step that was
	 * applied and then lost. The trainer counts the same refusals as {@code INVALID_ACTION}, so a
	 * recording where the two disagree is a real finding, and it now shows up as a count.
	 */
	private int refusedSteps;

	public String frameReport(){
		return frames + " frames, " + steppedFrames + " applied a step, "
				+ (frames - steppedFrames) + " did not, "
				+ refusedSteps + " refused by the engine, "
				+ spentTurns + " frames advanced engine time";
	}

	private void flushWorldSnapshot(){
		if (worldSnapshot == null) return;
		try {
			worldSnapshot.writeTo( java.nio.file.Paths.get( worldTracePath ));
		} catch (java.io.IOException e){
			System.err.println( "[replay] could not write the world snapshot to " + worldTracePath
					+ " (" + e.getClass().getSimpleName() + ")" );
		}
		System.err.println( "[replay] wrote " + worldSnapshot.lines().size() + " world records to "
				+ worldTracePath + " (" + frameReport() + ")" );
	}

	/**
	 * True when the step just applied owes no turn, because the next step chooses an aim or a dialog.
	 *
	 * <p>{@code SPDEnv.settle} checks whether the engine is waiting on a cell before it ever reaches
	 * {@code runToHeroReady}, so an arming step costs no turn and the scheduler is not advanced.
	 * Draining anyway advanced it by one, and that is where the two runs parted: on step 1 of a
	 * recording whose opening steps are {@code USE / SLOT / TARGETING}, the trainer took no scheduler
	 * step at all while the viewer took one with the hero. Everything after it was a turn out of place.
	 *
	 * <p>It has to be read from the recording rather than from the engine. The live {@code CellSelector}
	 * keeps its aim listener until {@code Hero.ready()} runs, which needs the very drain being
	 * skipped, so asking it reports "an aim is pending" on the step that consumes the aim - the mirror
	 * of the fault, not of the cause. The trainer's own flag is cleared as the aim is consumed; the next
	 * recorded step's mode says the same thing, and the viewer has the recording.
	 */
	private boolean owesNoTurn(){
		Replay.Step next = playback.peekNext();
		if (next == null) return false;
		EnvMode mode = modeOf( next.mode );
		return mode == EnvMode.TARGETING || mode == EnvMode.MENU;
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
	 * Advances turns until the hero can be given input, or reports why it cannot.
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
	 *
	 * <p>Returns {@link Drain#PENDING} rather than spinning when the scheduler is waiting on something
	 * this loop cannot advance. {@code Hero.actAttack} and the other animated actions call
	 * {@code CharSprite.attack}/{@code operate} and return without calling {@code next()}, so
	 * {@code headlessStep} reports the actor still wants to act and no time passes. The completion that
	 * finally calls {@code Hero.onAttackComplete} is an animation callback, driven by the render loop -
	 * and this loop runs inside {@code GameScene.update()} ahead of it, so spinning here starves the
	 * very thing being waited on. Yielding lets the frame finish and the animation complete. The trainer
	 * never sees this because its sprites complete synchronously.
	 *
	 * <p>A genuine stall still ends the episode: {@link Drain#STALLED} after {@link #MAX_ACTOR_STEPS}.
	 */
	/**
	 * The drain, instrumented for what it is asked to do and what it actually did.
	 *
	 * <p>{@link Actor#headlessStep()} re-selects an actor on every call, with no equivalent of the park
	 * {@link Actor#process()} has: an actor that took a turn and did not spend time - a mob mid attack
	 * animation, say - is handed the turn again on the next call. Three of those cost nothing and yield
	 * the frame, so a single unresolved animation produces a burst of redundant {@code Mob.act()} calls
	 * that the real game never makes, because in the real game the render thread stops poking the
	 * scheduler while an actor holds it.
	 *
	 * <p>Counted rather than assumed. {@code Actor.headlessStep()} is what the trainer uses too, so this
	 * is a count of how often the viewer asks for a turn it cannot use, not an assertion that doing so is
	 * wrong - an earlier claim that it did not happen was refuted by watching cooldowns, which cannot
	 * change during a park.
	 */
	private int schedulerCalls;

	/** Scheduler calls that re-selected the actor already holding the scheduler. */
	private int redundantCalls;

	/** Steps applied while some actor other than the hero still held the scheduler. */
	private int stepsAppliedMidTurn;

	/** Frames on which a drain ran and returned without the hero being ready. */
	private int pendingFrames;

	/**
	 * Consecutive frames a drain yielded because a level transition was pending.
	 *
	 * <p>Separate from {@link #drainSteps}, which counts scheduler iterations rather than frames, because
	 * a transition can legitimately take a second or more: {@code InterlevelScene} generates the floor on
	 * its own thread and then asks for the game scene, so the switch is cleared and re-raised before the
	 * hero is back on a map. 900 frames is 15 seconds at 60 fps, which is generous rather than tuned -
	 * the alternative is a drain that can wait forever.
	 */
	private int switchPendingFrames;

	private static final int SWITCH_PENDING_FRAMES = 900;

	private int countSchedulerCall( Actor previous ){
		schedulerCalls++;
		Actor now = Actor.currentActor();
		if (previous != null && now == previous){
			redundantCalls++;
			String who = now == null ? "none" : now.getClass().getSimpleName();
			redundantByClass.merge( who, 1, Integer::sum );
		}
		return schedulerCalls;
	}

	/** Which classes the scheduler re-selected while already holding the scheduler. */
	private final java.util.LinkedHashMap< String, Integer > redundantByClass =
			new java.util.LinkedHashMap<>();

	public String schedulerReport(){
		StringBuilder byClass = new StringBuilder();
		for (java.util.Map.Entry< String, Integer > e : redundantByClass.entrySet()){
			if (byClass.length() > 0) byClass.append( ", " );
			byClass.append( e.getKey() ).append( ' ' ).append( e.getValue() );
		}

		return schedulerCalls + " scheduler calls, " + redundantCalls
				+ " re-selected an actor already holding the scheduler ["
				+ byClass + "], "
				+ stepsAppliedMidTurn + " steps applied while another actor held it, "
				+ pendingFrames + " frames the drain yielded";
	}

	private Drain driveToHeroReady(){
		float lastNow = Actor.now();
		HeroAction lastAction = Dungeon.hero == null ? null : Dungeon.hero.curAction;
		int blocked = 0;

		for (int steps = 0; steps < MAX_ACTOR_STEPS; steps++){

			if (Dungeon.hero == null || !Dungeon.hero.isAlive()){
				if (recordingEndsHere()) return endWhereRecordingEnds();
				playback.finish();
				halt( "run ended - hero is dead" );
				return Drain.STALLED;
			}

			if (com.watabou.noosa.Game.switchingScene()){
				pendingFrames++;
				if (++switchPendingFrames > SWITCH_PENDING_FRAMES){
					playback.finish();
					halt( "stalled - a level transition was pending for " + SWITCH_PENDING_FRAMES
							+ " frames and was never serviced" );
					return Drain.STALLED;
				}
				return Drain.PENDING;
			}
			switchPendingFrames = 0;


			//the blocked counter below measures, not which step of the recording this is.

			Actor before = Actor.currentActor();
			countSchedulerCall( before );
			boolean wantsMore = Actor.headlessStep();

			if (Dungeon.hero.curAction == null && Dungeon.hero.ready && Dungeon.hero.paralysed == 0){
				return Drain.READY;
			}

			//A resting hero with no action is the one genuine one-way door in this loop, and the
			//trainer has a backstop for it that this loop was missing.
			//
			//Hero.act() handles a resting hero with a null action by spending TIME_TO_REST and calling
			//next(). It never reaches ready(), so the hero is never ready for input again and the drain
			//waits forever. The game escapes that by having a player choose another action, and
			//ActionMapper clears resting when it is given one - which is why headless runs do not
			//normally stall on it. LevelPipeline.runToHeroReady carries the backstop for the case where
			//nothing clears it, and this loop did not, which made the viewer wait out the rest and then
			//report the step the trainer had already ended.
			//
			//Measured on cleric-mid, whose recorded termination is STALLED for exactly this reason: the
			//trainer gave up at engine time 51 with the hero on 12 HP, and the viewer sat out the rest
			//until 58 with the hero on 20 - a different world, seven turns later, reported as a
			//divergence. Nothing else caught it because this branch was only reachable on the last
			//recorded step, which until now was skipped rather than drained.
			//
			//counted here rather than in the condition, because the trainer counts every scheduler
			//step of its drain and not only the ones where the hero is resting - measured on
			//cleric-mid, where counting only the resting steps left the count at 1 and the
			//hero sat out a rest the trainer had already given up on.
			drainSteps++;

			//The condition is the trainer's verbatim: not asking for more, past the same iteration
			//count, and resting. Relaxing any part of it would end a run the trainer let run.
			//
			//The count is a field rather than the loop variable, and that is the other half of the fix.
			//This drain yields the frame after three blocked iterations, so a local counter restarts
			//every few steps and never reaches the threshold. The trainer counts every iteration of
			//one drain and never yields, so the two only agree if this counts the same way.
			if (!wantsMore && drainSteps > RESTING_STALL_STEPS && Dungeon.hero.resting){
				if (recordingEndsHere()) return endWhereRecordingEnds();
				playback.finish();
				halt( "run ended - the hero is resting and nothing is resolving it" );
				return Drain.STALLED;
			}

			//No time spent and the hero still on the action it was given: nothing this loop does can
			//change that, so the next frame is where progress has to come from.
			//
			//Yielding here rather than ending, and that is load-bearing on the last step of a death
			//recording. A hero mid-swing has not yet dealt its damage: Char.actAttack plays the animation
			//and returns without calling next(), so the hit lands in the animation callback, driven by the
			//render loop this method is yielding to. Ending the recording at this point is what truncates a
			//death - the cursor reaches the end and the hero is still standing, which is the exact symptom
			//issues.md 2 describes. Measured on huntress-mid: the hero sat at 2 HP, the last step's hit never
			//resolved, and playback reported the recording complete.
			if (wantsMore && Actor.now() == lastNow && Dungeon.hero.curAction == lastAction){
				if (++blocked >= BLOCKED_STEPS){
					pendingFrames++;
					return Drain.PENDING;
				}
			} else {
				blocked = 0;
			}

			lastNow = Actor.now();
			lastAction = Dungeon.hero.curAction;
		}

		//The drain spent its whole budget without the hero becoming ready. If the recording ends here
		//then that is the recording finishing, not a viewer stall, and the recorded reason says which.
		if (recordingEndsHere()) return endWhereRecordingEnds();

		halt( "stalled - hero did not become ready within " + MAX_ACTOR_STEPS + " turns" );
		return Drain.STALLED;
	}

	/**
	 * Ends playback because the recording is complete, naming the reason it recorded.
	 *
	 * <p>Advances past the last step first, unless something already has. It has been applied by
	 * {@link #applyNextStep()}, so leaving the cursor on it would report a recording as unplayed when
	 * every step of it was. The guard is because the cursor is not always on the last step when this is
	 * reached: a step that settles normally advances the cursor past the end, and the <i>next</i> drain -
	 * of a hero that a turn limit has left permanently unable to become ready - finds
	 * {@link #recordingEndsHere()} still true and would advance a second time. Measured on the
	 * TURN_LIMIT fixture: 61 of 60 steps played.
	 *
	 * <p>The recorded reason rather than an observation, because the recording knows why the run ended
	 * and the viewer cannot: a hero that is dead and a hero that ran out of turns both leave a hero
	 * that will never be ready again, and only one of them is a death.
	 */
	private Drain endWhereRecordingEnds(){
		if (!playback.finished()) playback.advance();
		playback.finish();
		String reason = recordingTermination();
		halt( "run ended - the recording ends here as " + ( reason.isEmpty() ? "the last step" : reason ) );
		return Drain.STALLED;
	}

	/**
	 * Consecutive no-progress scheduler steps tolerated before yielding the frame.
	 *
	 * <p>A step that spends no time and leaves the action alone means the scheduler is waiting on the
	 * render clock, so the yield is the correct answer rather than a tolerance. The count exists only
	 * because a single such step is ambiguous: the first step of a turn routinely costs nothing.
	 */
	private static final int BLOCKED_STEPS = 3;

	/**
	 * Scheduler iterations a resting hero is given before the drain calls it a stall.
	 *
	 * <p>The trainer's figure, from {@code LevelPipeline.runToHeroReady}. Matching it rather than
	 * choosing a new one is the point: the viewer is checking that it followed the recording, so the
	 * two drains have to give up at the same moment or one of them is not checking anything.
	 */
	private static final int RESTING_STALL_STEPS = 8;

	/** Outcome of one drain. */
	private enum Drain {
		/** The hero can be given input. */
		READY,
		/** Waiting on the render loop; retry on the next frame. */
		PENDING,
		/** Playback has ended. */
		STALLED
	}

	/** True when the hero is waiting for input. */
	private boolean readyToAct(){
		if (Dungeon.hero == null) return false;
		if (!Dungeon.hero.isAlive()){
			halt( "run ended - hero is dead" );
			return false;
		}
		if (Dungeon.hero.paralysed > 0) return false;
		if (Dungeon.hero.curAction != null) return false;
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
			String reason = recordingTermination();
			halt( reason.isEmpty()
					? "replay finished - all " + playback.total() + " steps played"
					: "run ended - the recording ends here as " + reason );
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

		drainSteps = 0;

		if (rngTrace != null && !rngTraceArmed){
			flushDrawSites( ".gen" );
			rngTrace.onReset();
			rngTraceArmed = true;
		}

		String unresolvable = Quickslots.prepare( step, playback.cursor(),
				playback.replay().wireDroppedStepFields() );
		if (unresolvable != null){
			halt( unresolvable );
			return;
		}

		GameScene.clearPendingCellListener();
		if (mode != EnvMode.TARGETING){
		SlotAction.clearPendingUseItem();

	}

		mapper.refreshSlots();

		boolean acted;

		if (mode == EnvMode.SLOT || mode == EnvMode.INVENTORY || mode == EnvMode.MENU
				|| mode == EnvMode.TALENT){
			acted = mapper.applySecondary( mode, action, step.slot );
		} else if (mode == EnvMode.TARGETING){
			acted = mapper.applySecondary( mode, action, step.slot );
		} else {
			acted = mapper.apply( action, step.slot );
		}

		if (!acted){
			refusedSteps++;
			if (TRACE){
				String resolved = mode == EnvMode.SLOT || mode == EnvMode.INVENTORY
						|| mode == EnvMode.TALENT
						? mapper.describeSlot( step.slot ) : "";
				System.err.println( "[trace] refused " + playback.cursor() + " "
						+ (step == null ? "?" : step.action + "/" + step.slot) + " in " + mode
						+ (resolved.isEmpty() ? "" : ": " + resolved) );
			}
			if (worldSnapshot != null){
				worldSnapshot.sample( "step", playback.cursor() )
						.accepted( false );
			}
		}

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

		if (Dungeon.level != null) playback.gridWidth( Dungeon.level.width() );

		if (playback.finished()){
			awaitingSettle = false;
			return;
		}

		if (Dungeon.hero.isAlive()){
			settledPosition = Dungeon.hero.pos;
			trace( settledPosition );
			if (!playback.checkPosition( settledPosition )
					|| !playback.checkState( Dungeon.hero.HP, Actor.now(), ReplayRecorder.inventory() )){
				if (CONTINUE_ON_DIVERGE){
					if (!diverged) reportDivergence();
					diverged = true;
				} else {
					diverged = true;
					halt( playback.status() );
				}
			}
			if (rngTrace != null) rngTrace.sample( playback.cursor() );
			if (worldSnapshot != null) worldSnapshot.sample( "step", playback.cursor() );
			playback.advance();

			if (playback.finished()) endWhereRecordingEnds();
		} else {
			endWhereRecordingEnds();
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

	/**
	 * Keeps applying and tracing recorded steps after a divergence instead of stopping on it.
	 *
	 * <p>A divergence is reported on the step where the two sides first disagree, which is rarely the step
	 * that caused it: the symptom is usually several turns downstream of the mistake, and {@link #halt()}
	 * stops the run one step before the point worth looking at. This leaves playback running so
	 * {@code spd.worldTrace} and {@code spd.rngTrace} cover the steps past the fault, which is what
	 * {@code worlddiff} needs to say anything more than where it gave up.
	 *
	 * <p>The live game is already off the recording's path and stays off it, so everything after the
	 * divergence is the live game doing whatever it does next. That is the point - it is the only way to
	 * see what the two sides actually do differently - and it is why this is off by default and never
	 * enabled by a gate.
	 *
	 * <p>Enabled with {@code -Dspd.replayContinueOnDiverge}.
	 */
	private static final boolean CONTINUE_ON_DIVERGE =
			System.getProperty( "spd.replayContinueOnDiverge" ) != null;

	/**
	 * Prints the three lines that describe a divergence, without stopping.
	 *
	 * <p>Split out of {@link #halt()} so {@link #CONTINUE_ON_DIVERGE} can report the same thing and keep
	 * going. {@code halt} is the only place that used to print these, which is why continuing past a
	 * divergence had to duplicate them rather than reuse the path.
	 */
	private void reportDivergence(){
		System.err.println( "[replay] " + playback.status() );
		System.err.println( "[replay] " + frameReport() );
		System.err.println( "[replay] " + schedulerReport() );
	}

	private void halt( String reason ){
		if (playing){
			playing = false;
			haltReason = reason;
			System.err.println( "[replay] halted: " + reason );
			System.err.println( "[replay] " + frameReport() );
			System.err.println( "[replay] " + schedulerReport() );
			forgetRemains();
		flushRngTrace();
		flushWorldSnapshot();
		flushDrawSites( ".all" );

			boolean diverged = playback.diverged();
			if (AUTO_CLOSE || ( AUTO_CLOSE_ON_DIVERGE && diverged ) ){
				closeWhenReportingSettles();
			}
		}
	}

	/**
	 * Removes the remains this session's own death left behind.
	 *
	 * <p>A recording that ends in death kills the hero for real, the game writes {@code bones.dat} into
	 * the player's own profile exactly as it would for a playthrough, and nothing else in the viewer ever
	 * removes it. Three recordings in the corpus end in death, and the file is read during level
	 * generation, so leaving it behind means the player's next real run opens on a heap of a scripted
	 * policy's belongings. Measured: {@code duelist-mid} in the viewer left one in
	 * {@code %APPDATA%\.shatteredpixel\Shattered Pixel Dungeon}.
	 *
	 * <p>Called from {@link #halt()}, which is the one place that stops playback - a headless driver
	 * stops calling {@code update} the moment playback stops, and a quit never reaches another frame, so
	 * a cleanup on the next frame would pass every rendered run and never run in a gate at all.
	 *
	 * <p>{@code RunState} clears the same file when a player is constructed, so entering the viewer also
	 * clears what a previous session left. This run's own death is written after that point, which is
	 * why it cannot be the same call.
	 */
	private void forgetRemains(){
		if (remainsCleared) return;
		remainsCleared = true;
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.RunState.clearRemains();
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
		rebuilding = true;
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
