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
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs {@link ReplayPlayer} with no window, no scene and no {@link ReplayController}.
 *
 * <p>This is the gate that {@code ReplayPlayer} never had. Its roughly 560 lines decide what a recorded
 * step means, derive the mode, drain the scheduler, work out whether a turn is owed, settle, and compare
 * the result against the recording - and until now the only thing that executed them was the viewer
 * itself, which cannot be run in a test. Three faults lived there and all three were found by hand, by
 * diffing traces: a READY test missing {@code curAction == null}, a settle loop that spun 400 steps
 * inside one frame, and a drain that charged a turn to the step that arms an aim rather than the step
 * that spends it.
 *
 * <p>It exists because of what it found. Driving the player headlessly for the first time immediately
 * reported a divergence at step 3 of a 229-step recording the windowed viewer plays to step 33: the player
 * never performed the per-step preamble {@code SPDEnv.step} performs, so a throw went down the game's own
 * missile path instead of the sprite-free fallback and never landed. The window hid that, because a
 * window has a live cell selector to aim with. <b>A viewer bug that a window masks is invisible to every
 * test that needs a window</b>, which is the whole argument for this file existing.
 *
 * <p>Both halves are asserted. That a faithful recording plays clean, so the checks are not simply
 * rejecting everything; and that a recording altered in each recorded field is caught at that step, naming
 * the field. Without the second half these could be inert and still look healthy.
 *
 * <p>Playback needs a freshly built world: {@link ReplayPlayer#restart()} rewinds playback only and never
 * rebuilds the level, so each run resets the environment with the recorded seed.
 */
public class PlaybackCheck {

	private static final int CHECKS = 9;

	private static final String SEED = "PLAYBACKCHECK-A";

	private static final List< String > failures = new ArrayList<>();

	/**
	 * Runs one check and counts it failed if it produced any assertion.
	 *
	 * <p>Counting {@code failures.size()} against {@code CHECKS} looks equivalent and is not: one check
	 * that makes three assertions then reports itself as three failed checks. The mutation testing turned
	 * that up - a header fixture that omitted one field reported "3 of 8 checks failed" when exactly one
	 * check had failed.
	 */
	private static int checksFailed = 0;

	private static void check( Runnable body ){
		int before = failures.size();
		body.run();
		if (failures.size() > before) checksFailed++;
	}

	/** Ceiling on simulated frames, so a player stuck in a settle loop fails rather than hangs. */
	private static final int FRAME_BUDGET = 400000;

	private static EnvConfig config;
	private static SPDEnv env;

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-playbackcheck" ));
		HeadlessServices.disableSaving( true );

		config = new EnvConfig();
		config.turnLimitPerFloor = 200;
		env = new SPDEnv( config, HeadlessGame.install() );

		//recorded before any ReplayPlayer exists, because constructing one sets
		//Actor.manualScheduling globally and the recording path expects the engine to own the schedule
		Replay good = recordAShortRun();

		check( () -> checkAFaithfulReplayPlaysClean( good ) );
		check( () -> checkAlteredPositionIsCaught( good, 30 ) );
		check( () -> checkAlteredHealthIsCaught( good, 30 ) );
		check( () -> checkAlteredTurnIsCaught( good, 30 ) );
		check( () -> checkAlteredInventoryIsCaught( good, 30 ) );
		check( () -> checkAlteredQuickslotsAreCaught( good ) );
		check( () -> checkAStalledPlayerFailsInsteadOfHanging() );
		check( () -> checkNonDefaultConfigRoundTrips() );
		check( () -> checkDeclaredTerminationEndsPlayback( good ) );

		if (failures.isEmpty()){
			System.out.println( "[OK]     headless playback: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  headless playback: " + checksFailed
					+ " of " + CHECKS + " checks failed, " + failures.size() + " assertions" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	/** Records a short run with the scripted policy, the cheapest source of a real replay. */
	private static Replay recordAShortRun(){
		env.reset( SEED, HeroClass.WARRIOR );

		ReplayRecorder recorder = new ReplayRecorder();
		recorder.begin( SEED, "WARRIOR", 0, config.turnLimitPerFloor, config );

		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), 13 );
		int[] slot = new int[ 1 ];

		while (env.running() && recorder.replay().length() < 120){
			Action action = policy.choose( env, slot );
			recorder.record( action, slot[ 0 ], env.mode() );
			float reward = (float) env.step( action, slot[ 0 ] );
			recorder.afterStep( env.heroPosition(), reward );
		}

		return recorder.replay();
	}

	/** What one headless playback observed. */
	private static class Outcome {
		boolean diverged;
		int divergedAt;
		String haltReason = "";
		int cursor;
		int frames;
		boolean ranOutOfFrames;
	}

	/**
	 * Plays a recording to completion against a freshly built world.
	 *
	 * <p>This is the entire driver. No scene, no window, no controller - which is only possible because
	 * {@link ReplayPlayer} turned out to have no UI dependency, and which is the point.
	 */
	private static Outcome play( Replay replay ){
		env.reset( replay.seedText, heroClassOf( replay ) );

		Outcome outcome = new Outcome();
		ReplayPlayer player = new ReplayPlayer( replay, ReplayIOConfig( replay ) );
		player.speed( 16f );

		while (player.playing() && outcome.frames < FRAME_BUDGET){
			player.update( 1f / 60f );
			outcome.frames++;
		}

		ReplayPlayback playback = player.playback();
		outcome.diverged = playback.diverged();
		outcome.divergedAt = playback.divergedAt();
		outcome.haltReason = player.haltReason();
		outcome.cursor = playback.cursor();
		outcome.ranOutOfFrames = player.playing();
		return outcome;
	}

	private static EnvConfig ReplayIOConfig( Replay replay ){
		return com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO.configFor( replay );
	}

	private static HeroClass heroClassOf( Replay replay ){
		try {
			return HeroClass.valueOf( replay.heroClass );
		} catch (IllegalArgumentException e){
			return HeroClass.WARRIOR;
		}
	}

	// --------------------------------------------------------------------------- the checks

	private static void checkAFaithfulReplayPlaysClean( Replay replay ){
		Outcome outcome = play( replay );

		if ( outcome.ranOutOfFrames ){
			fail( "a faithful replay did not finish within " + FRAME_BUDGET
					+ " frames; it stopped at step " + outcome.cursor + " of " + replay.length()
					+ ". A settle loop that cannot reach READY hangs rather than failing." );
			return;
		}

		if ( outcome.diverged ){
			fail( "a faithful replay diverged at step " + outcome.divergedAt + ": " + outcome.haltReason
					+ " (halted after " + outcome.cursor + " of " + replay.length() + " steps)" );
			return;
		}

		if ( outcome.cursor < replay.length() ){
			fail( "a faithful replay played " + outcome.cursor + " of " + replay.length()
					+ " steps and then halted with: " + outcome.haltReason );
		}
	}

	/**
	 * The mutation cases.
	 *
	 * <p>Each alters one recorded field, requires the divergence to be reported <em>at the altered
	 * step</em> rather than merely somewhere later, and requires the message to describe the quantity
	 * that changed. Position, health, engine time and inventory each catch a different class: a wrong
	 * path, a frozen mechanic, a step costing the wrong amount of time, and an action applied to the
	 * wrong item.
	 */
	private static void checkAlteredPositionIsCaught( Replay replay, int at ){
		Replay.Step step = stepAt( replay, at );
		int saved = step.heroPos;
		step.heroPos = saved == Integer.MAX_VALUE ? saved - 1 : saved + 1;

		Outcome outcome = play( replay );
		step.heroPos = saved;

		requireDivergence( outcome, at, "hero at", "heroPos", replay );
	}

	private static void checkAlteredHealthIsCaught( Replay replay, int at ){
		Replay.Step step = stepAt( replay, at );
		int saved = step.heroHp;
		step.heroHp = saved <= 0 ? saved + 3 : saved - 3;

		Outcome outcome = play( replay );
		step.heroHp = saved;

		requireDivergence( outcome, at, "hp is", "heroHp", replay );
	}

	private static void checkAlteredTurnIsCaught( Replay replay, int at ){
		Replay.Step step = stepAt( replay, at );
		float saved = step.turn;
		step.turn = saved < 0f ? 5f : saved + 3f;

		Outcome outcome = play( replay );
		step.turn = saved;

		requireDivergence( outcome, at, "engine time", "turn", replay );
	}

	private static void checkAlteredInventoryIsCaught( Replay replay, int at ){
		Replay.Step step = stepAt( replay, at );
		String saved = step.inventory;
		step.inventory = saved + ",Tampered:99";

		Outcome outcome = play( replay );
		step.inventory = saved;

		requireDivergence( outcome, at, "inventory is", "inventory", replay );
	}

	/**
	 * Altered quickslot bindings must never play clean.
	 *
	 * <p>Not required to diverge, because there are two honest answers and the player has no reason to
	 * prefer one: either the bindings still resolve and the run diverges somewhere downstream, or they
	 * no longer match what the hero is carrying and the player refuses the step and says why. What is
	 * not acceptable is the third case - silently playing a step against different bindings, which is
	 * precisely what a version 1 header could not even detect, since it recorded neither the bindings
	 * nor the width they were chosen against.
	 */
	private static void checkAlteredQuickslotsAreCaught( Replay replay ){
		Replay.Step target = null;
		for (Replay.Step step : replay.steps){
			if (step.quickslots != null && !step.quickslots.isEmpty()){
				target = step;
				break;
			}
		}

		if ( target == null ){
			fail( "the recorded run contains no quickslot bindings, so the binding check could not run."
					+ " A gate that cannot reach its own case is not a passing gate." );
			return;
		}

		String saved = target.quickslots;
		int at = replay.steps.indexOf( target );

		String tampered = tamperBindings( saved );
		if ( tampered.equals( saved )){
			fail( "could not construct a quickslot mutation at step " + at + "; the recorded bindings ["
					+ saved + "] have nothing to tamper with, so the check did not run" );
			return;
		}

		target.quickslots = tampered;
		Outcome outcome = play( replay );
		target.quickslots = saved;

		if ( !outcome.diverged && outcome.cursor >= replay.length() ){
			fail( "altering the recorded quickslot bindings at step " + at + " from [" + saved + "] to ["
					+ tampered + "] was not detected; the player played all " + replay.length()
					+ " steps clean" );
		}
	}

	/**
	 * Rewrites the bindings so they cannot match the inventory.
	 *
	 * <p>Rather than swapping two entries - which is a no-op whenever the run binds the same item twice
	 * or binds nothing - this names an item the hero is not carrying, which is exactly the condition
	 * {@code Quickslots.applicable} exists to reject.
	 */
	private static String tamperBindings( String encoded ){
		return "NotInTheHeroesInventory:1";
	}

	/**
	 * A recording that cannot resolve its own slots must be refused with a reason, not played.
	 *
	 * <p>Without this, a player that silently did nothing to an unresolvable step would look identical to
	 * one that played it correctly: no divergence, no error, just a replay that quietly does not replay.
	 */
	private static void checkAStalledPlayerFailsInsteadOfHanging(){
		Replay broken = recordAShortRun();
		for (Replay.Step step : broken.steps){
			step.quickslots = "";
		}

		Outcome outcome = play( broken );

		if ( outcome.ranOutOfFrames ){
			fail( "a recording with no resolvable slot bindings hung instead of halting" );
		} else if ( !outcome.haltReason.contains( "quickslot" ) && !outcome.haltReason.contains( "slot" ) ){
			fail( "a recording with no resolvable slot bindings halted with an unexplained reason: "
					+ outcome.haltReason );
		}
	}

	/**
	 * A header written with non-default slot settings must read back as written.
	 *
	 * <p>This exists because testing it with defaults would prove nothing. A writer that quietly omitted
	 * {@code max_slots} and {@code allow_equipping} would still round-trip {@code 32} and {@code true},
	 * because those are the defaults the reader falls back to - so a default-only test passes against
	 * exactly the bug it is supposed to catch. Non-defaults are the only values that distinguish "written"
	 * from "not written and defaulted".
	 *
	 * <p>The file's own bytes are checked as well as the parsed object, because a field that reached the
	 * object by some other route would still round-trip while the header stayed uninformative.
	 */
	private static void checkNonDefaultConfigRoundTrips(){
		Replay replay = recordAShortRun();
		replay.maxSlots = 7;
		replay.allowEquipping = false;
		replay.turnLimitPerFloor = 321;

		File file = new File( System.getProperty( "java.io.tmpdir" ),
				"playbackcheck-config.replay" );
		try {
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO.write( replay, file );
		} catch (java.io.IOException e){
			fail( "could not write the header fixture: " + e );
			return;
		}

		Replay read;
		try {
			read = com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO.read( file );
		} catch (java.io.IOException e){
			fail( "could not read back the header fixture: " + e );
			return;
		}

		if ( read.maxSlots != 7 ){
			fail( "the header did not carry max_slots: wrote 7, read back " + read.maxSlots
					+ (read.maxSlots == 32 ? " - which is EnvConfig's default, so it was probably not "
					+ "written at all rather than written wrongly" : "") );
		}
		if ( read.allowEquipping ){
			fail( "the header did not carry allow_equipping: wrote false, read back true"
					+ " - which is EnvConfig's default, so it was probably not written at all" );
		}

		EnvConfig restored = com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO
				.configFor( read );
		if ( restored.maxSlots != 7 ){
			fail( "ReplayIO.configFor produced maxSlots " + restored.maxSlots + " from a header saying 7" );
		}
		if ( restored.allowEquipping ){
			fail( "ReplayIO.configFor produced allowEquipping true from a header saying false" );
		}
		if ( restored.turnLimitPerFloor != 321 ){
			fail( "ReplayIO.configFor produced turnLimitPerFloor " + restored.turnLimitPerFloor
					+ " from a header saying 321" );
		}

		//and the bytes, since an uninformative header would still round-trip
		try {
			String header = new String( java.nio.file.Files.readAllBytes( file.toPath() ),
					java.nio.charset.StandardCharsets.UTF_8 );
			if ( !header.contains( "max_slots=7" )){
				fail( "the written header contains no 'max_slots=7' line" );
			}
			if ( !header.contains( "allow_equipping=false" )){
				fail( "the written header contains no 'allow_equipping=false' line" );
			}
		} catch (java.io.IOException e){
			fail( "could not re-read the header fixture to inspect its bytes: " + e );
		}
	}

	/**
 * A recording that declares why it ended must finish as itself, not as a stall.
 *
 * <p>A run that ends on a turn or stall limit leaves a healthy hero that will never become ready, and
 * the viewer has no environment to ask. Before the header carried a termination reason it waited out
 * its 400-turn bound and reported {@code stalled - hero did not become ready within 400 turns}, which is
 * false in a way that matters: it says the recording was truncated when it was complete.
 *
 * <p>The final step's action is still applied, because the trainer applies it and only then terminates.
 * What is skipped is the drain and the comparison, since the trainer did not complete that step either
 * and there is no settled state to compare against - so the check asserts the whole thing played, the
 * cursor reached the end, and the reason named is the recorded one.
 */
private static void checkDeclaredTerminationEndsPlayback( Replay good ){
	Replay trimmed = recordAShortRun();
	trimmed.steps.subList( 60, trimmed.steps.size() ).clear();
	trimmed.termination = "TURN_LIMIT";

	Outcome outcome = play( trimmed );

	if ( outcome.ranOutOfFrames ){
		fail( "a recording declaring it ended as TURN_LIMIT hung instead of finishing" );
		return;
	}
	if ( outcome.diverged ){
		fail( "a recording declaring it ended as TURN_LIMIT diverged: " + outcome.haltReason );
		return;
	}
	if ( outcome.cursor != trimmed.length() ){
		fail( "a recording declaring it ended as TURN_LIMIT played " + outcome.cursor + " of "
				+ trimmed.length() + " steps; the final action must still be applied" );
		return;
	}
	if ( outcome.haltReason == null || !outcome.haltReason.contains( "TURN_LIMIT" )){
		fail( "a recording declaring it ended as TURN_LIMIT halted with: " + outcome.haltReason
				+ " - the recorded reason should be what it says" );
		return;
	}
	if ( outcome.haltReason.contains( "stalled" )){
		fail( "a recording declaring it ended as TURN_LIMIT reported a stall, which is the false "
				+ "report this check exists to prevent" );
	}

	//and the converse: a recording that declares nothing must not be treated as ended
	Replay silent = recordAShortRun();
	silent.steps.subList( 60, silent.steps.size() ).clear();
	silent.termination = "";
	Outcome undeclared = play( silent );
	if ( undeclared.diverged || undeclared.cursor < silent.length() ){
		//not required to reach the end - the hero may simply become ready - but it must not claim to
		//have ended early on a reason nobody recorded
		if ( undeclared.haltReason != null && undeclared.haltReason.contains( "the recording ends here" )){
			fail( "a recording with no recorded termination still ended as if it had one" );
		}
	}
}

// --------------------------------------------------------------------------- helpers

	private static Replay.Step stepAt( Replay replay, int at ){
		if ( at >= replay.steps.size() ){
			at = replay.steps.size() - 1;
		}
		return replay.steps.get( at );
	}

	private static void requireDivergence( Outcome outcome, int at, String reportedAs, String field, Replay replay ){
		if ( !outcome.diverged ){
			fail( "altering " + field + " at step " + at + " was not detected; the player played "
					+ outcome.cursor + " of " + replay.length() + " steps clean" );
			return;
		}
		if ( outcome.divergedAt != at ){
			fail( "altering " + field + " at step " + at + " was reported at step "
					+ outcome.divergedAt + " instead (" + outcome.haltReason + "). The check is only "
					+ "worth anything if it localises the change." );
			return;
		}
		if ( outcome.haltReason == null || !outcome.haltReason.contains( reportedAs )){
			fail( "altering " + field + " at step " + at + " was reported as: " + outcome.haltReason
					+ " - which does not describe the quantity that changed" );
		}
	}

	private static void fail( String message ){
		failures.add( message );
	}
}