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

		//A viewer plays one recording per construction, but a batch run builds players back to back in
		//one process, and a player inherits whatever the previous one left armed. Clearing the same set
		//the trainer's reset clears is what keeps a second file in a batch from starting in TARGETING
		//and diverging at step 0. See RunState.
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.RunState.clearRunStatics();

		String tracePath = System.getProperty( "spd.rngTrace" );
		if (tracePath != null && !tracePath.trim().isEmpty()){
			rngTracePath = tracePath;
			rngTrace = new com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.RngTrace();
			rngTrace.dungeonSeed( Dungeon.seed );
			com.watabou.utils.RandomTrace.enable();
			//attribution on from construction to the first step, because the window being asked about is
			//exactly the one before it: the renderer draws here that the headless path does not
			com.watabou.utils.RandomTrace.attribute();
		} else {
			rngTracePath = null;
			rngTrace = null;
		}

		String worldPath = System.getProperty( "spd.worldTrace" );
		if (worldPath != null && !worldPath.trim().isEmpty()){
			worldTracePath = worldPath;
			//Cells are off by default here: a frame record is per frame, so a floor-sized cell dump would
			//be tens of thousands of records for a 150-step recording and would drown the frames that
			//matter. The roster is what identifies an actor, and it is on every record.
			worldSnapshot = new com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldSnapshot()
					.cells( false );
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
	 * Per-frame record of the world, and per-step alongside it.
	 *
	 * <p>Off unless {@code -Dspd.worldTrace} names a file. It exists because {@link #rngTrace} and the
	 * viewer trace are both keyed to a settled step, so neither can see a turn taken on a frame that
	 * applied no recorded step - which is the one thing a frame-driven viewer does that the trainer does
	 * not.
	 */
	private final com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldSnapshot worldSnapshot;
	private final String worldTracePath;

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
	 * <p>Absent in versions 1 and 2 of the format, which leave the reason unknown and are played to
	 * their last step as before.
	 */
	private boolean recordingEndsHere(){
		if (playback.cursor() != playback.total() - 1) return false;
		String reason = recordingTermination();
		//DEATH is included, and it used to be excluded on the claim that the hero-alive check covered
		//it. It does not: that check is an observation, and it fires on the last step of a death run
		//before this function was ever consulted, so excluding DEATH handed the final step to the
		//observation and stopped playback one step early. A death run still ends here like any other,
		//and saying so is more useful than saying the hero is dead - the recording knows why, and the
		//step it declares finished is one the trainer applied.
		return !reason.isEmpty();
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

		if (sinceStep < BASE_STEP_SECONDS / speed){
			//Sampled on every frame, including the ones the pacing timer skips. Those are precisely the
			//frames that apply no recorded step, so they are the only place a world change nothing
			//accounted for can appear - and no step-keyed observer sees them, by construction.
			frameBoundary( false, elapsed, null );
			return;
		}

		sinceStep = 0;

		//A step applied on an earlier frame has to settle before another one is applied, or the step
		//whose comparison settle() performs is never compared at all.
		if (awaitingSettle){
			boolean owed = owesNoTurn();
			Drain drain = owed ? Drain.READY : driveToHeroReady();
			if (drain == Drain.READY) settle();
			frameBoundary( owed, elapsed, drain );
			return;
		}

		//Only ever step when the hero is not already ready.
		//
		//driveToHeroReady() always advances at least one scheduler step before it tests readiness, so
		//using it as a per-frame gate advanced the game once for every frame rendered - including the
		//frames spent waiting out the pacing timer, and at high speed that is every frame, because the
		//timer is shorter than a frame. Each step handed a turn to some other actor while the hero stood
		//ready and idle, so the world's actors advanced faster than the hero's actions did.
		if (!readyToAct() && driveToHeroReady() != Drain.READY){
			frameBoundary( false, elapsed, null );
			return;
		}

		//Counted here, at the only point a recorded step is injected, because that is the question the
		//real game answers before it hands a turn to anyone: GameScene.update only pokes the scheduler
		//when Actor.processing() is false, so a player is never given input while an actor holds it. The
		//viewer consults only the hero, and the hero reads ready for as long as it is idle - which
		//includes the whole of another actor's attack animation.
		Actor holding = Actor.currentActor();
		if (holding != null && holding != Dungeon.hero) stepsAppliedMidTurn++;

		applyNextStep();

		//Settle in this same call where the turn resolves immediately.
		//
		//Applying and settling were separate frame callbacks, and the intervening GameScene.update()
		//cleared the pending Hero.curAction before the hero ever acted: the scheduler log showed
		//curAction already null on entry, with Actor.now() unmoved and the hero's position unchanged, so
		//the recorded move was silently discarded. That is what "DIVERGED ... hero at 687, recording says
		//655" was - a lost action, not a refused one.
		//
		//An animated action cannot settle here: its completion is an animation callback, so the drain
		//reports PENDING and awaitingSettle carries the step to the frame that resolves it. That is the
		//one case where a frame boundary falls between applying a step and the hero acting, which is why
		//it is bounded rather than general - see DriveToHeroReady's note on GameScene.cancel().
		//Held in a named local so the frame record can report what the drain concluded. It used to be
		//an inline condition, which left nothing to record - and what the drain returned is the only
		//thing that distinguishes "the animation finished this frame" from "the frame was skipped".
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
		//Both counters advance whether or not a snapshot is being written. They used to sit after the
		//snapshot's null check, which made the reported frame count zero whenever tracing was off - and
		//the frame ratio is the one number that says how timing-dependent a playback is, so it has to be
		//true on a normal run and not only on an instrumented one.
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

	private int countSchedulerCall( Actor previous ){
		schedulerCalls++;
		Actor now = Actor.currentActor();
		if (previous != null && now == previous){
			redundantCalls++;
			//By class, because the count alone says how much repetition happened and not what was
			//repeating. An actor that never spends time is one whose turn is being deferred to an
			//animation, and which of them it is decides whether that is a mob waiting out its swing or a
			//mob whose swing never completes.
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

			//Checked before the hero-alive test below, which is the ordering that used to cost a step.
			//
			//On the last recorded step of a DEATH run the hero is already dead by the time playback
			//arrives here, so the hero-alive check fired first, called playback.finish() and halted
			//"hero is dead" - and this branch was never reached. The result was a recording that played
			//N-1 of N steps and then stopped, on a file the headless verifier called clean, because
			//ReplayIO.verify drives the env rather than this loop and never had the bug. Three of the
			//seventeen corpus files ended in death; all three stopped one step short.
			//
			//A viewer that stops one step early is not reporting the recording faithfully, and "the
			//recording is complete but the hero is dead" is a different message from "the recording ends
			//here", so the recorded reason is the one worth printing. DEATH is excluded from
			//recordingEndsHere() only because a run can die without the recording saying so; when it
			//does say so, this branch is what should handle it.
			if (recordingEndsHere()){
				playback.advance();
				playback.finish();
				halt("run ended - the recording ends here as " + recordingTermination());
				return Drain.STALLED;
			}

			if (Dungeon.hero == null || !Dungeon.hero.isAlive()){
				playback.finish();
				halt( "run ended - hero is dead" );
				return Drain.STALLED;
			}

			//Park while another actor's turn is genuinely unresolved.
			//
			//REFUTED BY MEASUREMENT, and kept here because the measurement is the useful part. The idea
			//was that Actor.headlessStep() re-selects an actor that is still finishing its turn, which
			//Actor.process() would refuse to do, and that this is why the viewer hands out turns the
			//trainer does not. Measured on warrior-long before the park: 290 of 303 scheduler calls did
			//re-select the actor already holding the scheduler. Two things that looks like are not it.
			//
			//Re-selecting does not restart the animation. CharSprite.attack calls MovieClip.play, which
			//returns immediately when the same non-looped animation is already in flight, so the ~3 calls
			//per frame cost three Mob.act() evaluations and no restart.
			//
			//And Actor.current cannot tell a finished turn from an unfinished one. It is cleared only by
			//Actor.next(), which Hero.act and Buff.act never call - Hero.act calls ready() and Buff.act
			//diactivates, both returning without it. Actor.process hides this by setting current = null at
			//the top of every iteration; headlessStep does not, so Actor.current is "who acted last", not
			//"who is mid-turn". Parking on it wedges immediately, and both times for an actor that had
			//already finished: first Regeneration@2, a Buff, then Sentry@5 / Piranha@5 / Rat@5 / Snake@5,
			//every one reporting cooldown 1.0 - which is attackDelay(), so they had spent their turn and
			//Actor.current was simply stale.
			//
			//So Actor.current is not usable as a turn-ownership gate from the viewer. What remains is
			//that the scheduler re-evaluates an actor three times a frame, which the real game does not do
			//and which is worth counting rather than assuming harmless.

			//The last recorded step, and the recording says why the run ended. The trainer applied this
			//step's action and *then* terminated, so the action is still applied here - what is skipped
			//is the drain and the comparison, because the trainer did not complete this step either and
			//there is no settled state to compare against. Draining it instead made the viewer wait 400
			//turns for a hero that would never be ready and then report "stalled", which is false: the
			//recording was complete, not truncated.

			Actor before = Actor.currentActor();
			countSchedulerCall( before );
			boolean wantsMore = Actor.headlessStep();

			if (Dungeon.hero.curAction == null && Dungeon.hero.ready && Dungeon.hero.paralysed == 0){
				return Drain.READY;
			}

			//No time spent and the hero still on the action it was given: nothing this loop does can
			//change that, so the next frame is where progress has to come from.
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

		halt( "stalled - hero did not become ready within " + MAX_ACTOR_STEPS + " turns" );
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
		//curAction, not just ready.
		//
		//Applying a step sets curAction and calls next(), and Hero.ready is only cleared inside
		//Hero.act() when the action is consumed. So between the two there is a window where the hero
		//is ready from the previous turn and already holding the new action - and testing ready alone
		//walked straight through it, applying the following step over the pending one.
		//
		//That is what a trace caught: at step 8 the trainer's hero held PickUp, carried from the
		//INTERACT recorded at step 7, while the viewer held Move, carried from step 6 - two actions
		//behind. The trainer's READY test has always required curAction == null for this reason.
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

		//armed here, not at construction: see rngTraceArmed. Everything above this line is setup the
		//headless run does not do, and none of it belongs in the trace.
		if (rngTrace != null && !rngTraceArmed){
			//flushed before the reset, because onReset zeroes the counters the report quotes. Attribution
			//stays on past here and the tally is written again at halt: the window from construction to the
			//first step answers "what drew during generation", and only the whole playback answers "what
			//drew while the recording was applied", which is where a window-only divergence actually lives.
			flushDrawSites( ".gen" );
			rngTrace.onReset();
			rngTraceArmed = true;
		}

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

		//The trainer's per-step preamble, in the same order as SPDEnv.step:199-208. Without it the
		//viewer and the trainer do not perform the same state transition, and the window hides which.
		//
		//clearPendingCellListener is the one that matters. SlotAction.use sets GameScene's aim
		//listener (:93-98), and ActionMapper.resolveTarget prefers that listener over the sprite-free
		//castAt fallback. SPDEnv clears it every step, so the trainer's throws always fall back to
		//castAt. The viewer kept it, so the throw went through the game's own missile path, which
		//recycles a sprite from hero.sprite.parent - there is no parent with no scene, the throw died,
		//and the turn never advanced. Headless probe on RF: "USE/0 in TARGETING: engine time is 0.0,
		//recording says 1.0", at the third step of a recording the windowed viewer plays to 33.
		GameScene.clearPendingCellListener();
		if (mode != EnvMode.TARGETING){
//The pending item survives a step that is consuming an aim; dropping it leaves the
		//following TARGETING step with nothing to act on.
		SlotAction.clearPendingUseItem();

		//A dialog is deliberately NOT cleared here, and the asymmetry with the line above is the point.
		//A dialog belongs to the *next* step, which is the one recorded in MENU to answer it - clearing
		//it on entry is the fault that made MENU unreachable from the trainer's side, and mirroring it
		//here would break the viewer's menu steps the same way. A dialog can only outlive a run, never
		//a step, and that case is RunState's, at construction.
	}

		//before the dispatch, not after: SPDEnv refreshes here, so the slot index has to resolve
		//against the slots the step is about to be applied to.
		mapper.refreshSlots();

		//Whether the environment accepted the action, which was discarded here.
		//
		//ActionMapper.apply and applySecondary both return a boolean saying whether the action was
		//taken, and SPDEnv reads it to note INVALID_ACTION. Playback read it as nothing, so a recorded
		//step the engine refused - a move onto a non-adjacent cell, a slot index that resolves to no
		//item, an unreachable target - was applied silently and the divergence it caused surfaced
		//later as a position or health mismatch with no mention of the step that actually failed.
		//
		//A refusal is not itself a divergence: the trainer recorded the same refusal, and the recording
		//stores the position the hero really ended up at, which is where he is too. So this counts
		//refusals and puts the count in the halt message and the frame report, rather than failing on
		//them. A recording whose refusals do not match the trainer's is still a real finding, and it
		//is now a number rather than an absence.
		boolean acted;

		//the follow-up half of a two-step action
		if (mode == EnvMode.SLOT || mode == EnvMode.INVENTORY || mode == EnvMode.MENU){
			acted = mapper.applySecondary( mode, action, step.slot );
		} else if (mode == EnvMode.TARGETING){
			acted = mapper.applySecondary( mode, action, step.slot );
		} else {
			acted = mapper.apply( action, step.slot );
		}

		if (!acted){
			refusedSteps++;
			if (worldSnapshot != null){
				worldSnapshot.sample( "step", playback.cursor() )
						.accepted( false );
			}
		}

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
			//sampled after the comparison but before advancing, so a step that diverged is still traced:
			//the point of a trace is usually to see what the world was doing where it stopped agreeing
			if (rngTrace != null) rngTrace.sample( playback.cursor() );
			//before advance(), so the label is the step this record settled rather than the next one. The
			//headless producer labels the same way, off the same cursor, which is what lets the two files
			//be diffed by key.
			if (worldSnapshot != null) worldSnapshot.sample( "step", playback.cursor() );
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
			System.err.println( "[replay] " + frameReport() );
			System.err.println( "[replay] " + schedulerReport() );
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
