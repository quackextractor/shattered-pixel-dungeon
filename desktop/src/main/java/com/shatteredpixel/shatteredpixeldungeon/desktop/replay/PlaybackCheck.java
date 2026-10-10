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

	private static final int CHECKS = 20;

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
		check( () -> checkADeathRunPlaysItsLastStep( good ) );
		check( () -> checkRestartDoesNotEndTheRun( good ) );
		check( () -> checkRestartKeepsPlaybackArmedAcrossARebuild( good ) );
check( () -> checkADeathRunActuallyActsOnItsLastStep() );
		check( () -> checkTheCommittedCorpusPlaysClean() );
		check( () -> checkScoreTracksGainAndLossSeparately( good ) );
		check( () -> checkCoordinatesDecodeAPosition() );
		check( () -> checkPositionsAreReportedAsCoordinates( good ) );
		check( () -> checkADivergenceMessageNamesCoordinates() );
		check( () -> checkHudTextWrapsInsteadOfRunningOffScreen() );
		check( () -> checkTheHudAnchorIsMovableAndDefaultsTopLeft() );

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

		/** Whether the hero was still standing when playback ended. See the death check. */
		boolean heroAliveAtEnd;
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
		outcome.heroAliveAtEnd = Dungeon.hero != null && Dungeon.hero.isAlive();
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

/**
 * A run that ends with the hero dead must still play every recorded step.
 *
 * <p>This check exists because three of the seventeen committed recordings failed it, and every other
 * check passed while they did. The cause was an ordering inside {@link ReplayPlayer#driveToHeroReady()}:
 * the hero-alive test ran before the test for the recording declaring its own ending, so on the final
 * step of a death run - where the hero is already dead - playback halted "hero is dead" and never reached
 * the step it should have applied. Each of those files played N-1 of N steps and stopped.
 *
 * <p>Nothing caught it because nothing was looking. {@code ReplayIO.verify} drives the environment
 * rather than the player, so {@code replay-viewer --verify} reported all three clean; and this gate
 * played its own fixture, which ended on a turn limit rather than a death, so the branch the bug lived in
 * was never taken. A corpus check is what was missing.
 *
 * <p>The hero's health is forced to zero on the last step rather than by playing until it happens to die,
 * so the check is about the branch and not about whether the scripted policy died on this run. Both
 * halves matter: a run that dies early is asserted to stop early, and a run marked DEATH whose hero is
 * still alive is asserted <em>not</em> to be cut short, which is what makes the first assertion a claim
 * about death rather than about an early halt.
 */
private static void checkADeathRunPlaysItsLastStep( Replay good ){
	Replay dying = corpusRecordingEndingIn( "DEATH" );
	if ( dying == null ){
		//Already reported by checkADeathRunActuallyActsOnItsLastStep, which reports the missing case
		//with the same wording. Reported once rather than twice for one absent file.
		return;
	}

	Outcome outcome = play( dying );

	if ( outcome.ranOutOfFrames ){
		fail( "a recording declaring DEATH hung instead of finishing" );
		return;
	}
	if ( outcome.diverged ){
		fail( "a recording declaring DEATH diverged: " + outcome.haltReason );
		return;
	}
	if ( outcome.cursor != dying.length() ){
		fail( "a recording declaring DEATH played " + outcome.cursor + " of " + dying.length()
				+ " steps and halted with: " + outcome.haltReason
				+ ". The final step's action is still applied, so a death run must play all of them." );
		return;
	}
	if ( outcome.haltReason == null || !outcome.haltReason.contains( "DEATH" )){
		fail( "a recording declaring DEATH halted with: " + outcome.haltReason
				+ " - the recorded reason should be what it says, rather than a bare death report" );
	}

	//and the converse: DEATH is not a licence to stop early on a hero that is still standing
	Replay falseDeath = recordAShortRun();
	falseDeath.termination = "DEATH";

	Outcome standing = play( falseDeath );
	if ( standing.cursor < falseDeath.length() ){
		fail( "a recording declaring DEATH whose hero is alive stopped at step " + standing.cursor
				+ " of " + falseDeath.length() + ": " + standing.haltReason
				+ ". A declared ending truncates the recording, it does not describe one already in progress." );
	}
}

/**
 * Every committed recording must play clean through {@link ReplayPlayer}.
 *
 * <p>Added because of {@code issues.md} 2. Draining the last recorded step made the viewer compare a
 * step it had never compared before, and {@code cleric-mid} immediately failed: it diverged at its
 * final step, seven turns and eight health later than the recording says. The cause was a real gap in
 * this class - the drain had none of the trainer's resting-stall backstop, so it sat out a rest the
 * trainer had already given up on - and nothing here could see it, because every other case builds its
 * own fixture and a freshly recorded run does not end on a rest.
 *
 * <p>So the fixture is the corpus: real recordings, recorded by the trainer, covering the terminations
 * a self-recorded run reaches only by luck. {@link #viewcheck} plays the same files through the real
 * viewer, and this is its headless half - the difference being that this one can run where there is no
 * display, and so can be a gate.
 *
 * <p>Reports every failure rather than the first, because "which recordings and which steps" is the
 * whole question when a drain stops matching the trainer.
 */
private static void checkTheCommittedCorpusPlaysClean(){
	File corpus = corpusDir();
	File[] files = corpus.listFiles( ( dir, name ) -> name.endsWith( ".replay" ) );
	if ( files == null || files.length == 0 ){
		fail( "no recordings at " + corpus.getAbsolutePath()
				+ ", so the committed corpus could not be played. This check is about the real ones." );
		return;
	}

	java.util.Arrays.sort( files, java.util.Comparator.comparing( File::getName ));

	int played = 0;
	for (File file : files){
		Replay recording;
		try {
			recording = com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO.read( file );
		} catch (java.io.IOException e){
			fail( file.getName() + " could not be read: " + e.getMessage() );
			continue;
		}

		Outcome outcome = play( recording );
		played++;

		if (outcome.ranOutOfFrames){
			fail( file.getName() + " did not finish; it stopped at step " + outcome.cursor
					+ " of " + recording.length() );
			continue;
		}
		if (outcome.diverged){
			fail( file.getName() + " diverged at step " + outcome.divergedAt + ": " + outcome.haltReason );
			continue;
		}
		if (outcome.cursor != recording.length()){
			fail( file.getName() + " played " + outcome.cursor + " of " + recording.length()
					+ " steps: " + outcome.haltReason );
		}
	}

	if (played == 0 && failures.isEmpty()){
		fail( "the corpus was readable but nothing was played; the check asserted nothing" );
	}
}

/**
 * The first recording in the committed corpus declaring a given termination reason.
 *
 * <p>Used instead of a locally built one because a real death cannot be synthesised honestly. The
 * previous fixture set the recorded health to zero on the last step of a run whose hero was alive at
 * full health, which made the recording a lie: once the drain resolved that step - which is the fix
 * {@code issues.md} 2 asks for - the hero's real health no longer matched the recorded zero and the
 * comparison correctly reported a divergence. The check was passing for the wrong reason, because the
 * step it exercised was never actually compared.
 *
 * @return null when no such recording exists; the caller reports that once
 */
private static Replay corpusRecordingEndingIn( String termination ){
	File corpus = corpusDir();
	if ( !corpus.isDirectory() ) return null;

	File[] files = corpus.listFiles( ( dir, name ) -> name.endsWith( ".replay" ) );
	if ( files == null ) return null;

	java.util.Arrays.sort( files, java.util.Comparator.comparing( File::getName ));
	for (File file : files){
		Replay candidate;
		try {
			candidate = com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO.read( file );
		} catch (java.io.IOException e){
			continue;
		}
		if ( termination.equals( candidate.termination ) && candidate.steps.size() > 4){
			return candidate;
		}
	}
	return null;
}

/**
 * A restart must leave playback running, not finished.
 *
 * <p>{@code issues.md} 1: pressing {@code R} after a recording finished left the HUD reading
 * "finished" over a rewound cursor, so the key appeared to do nothing. The cause is the ordering
 * inside {@code restart()}: the cursor is rewound, but the outgoing scene's next frame runs the drain
 * against a hero that has already been nulled, and a null hero is what ends a run everywhere else in
 * this class.
 *
 * <p>Asserted on the player rather than through {@link ReplayController}, because the controller needs
 * a live scene to install and drive - which is exactly why this needed a headless driver at all.
 */
private static void checkRestartDoesNotEndTheRun( Replay good ){
		env.reset( good.seedText, heroClassOf( good ) );
		ReplayPlayer player = new ReplayPlayer( good, ReplayIOConfig( good ) );
		player.speed( 16f );

		//play a few steps so the cursor is genuinely partway through, which is the state R is pressed in
		for (int i = 0; i < 5; i++) player.update( 1f / 60f );

		player.restart();

		if ( !player.playing() ){
			fail( "restart() left playback stopped; pressing R on a finished recording reported it as "
					+ "still finished, so the key did nothing" );
		}
		if ( player.haltReason() != null && !player.haltReason().isEmpty() ){
			fail( "restart() left a halt reason behind: \"" + player.haltReason()
					+ "\". A restarted run reports its own ending, not the previous one's." );
		}
		if ( player.playback().finished() ){
			fail( "restart() left the playback marked finished; the HUD would read 'finished' over a "
					+ "cursor rewound to 0" );
		}
		if ( player.playback().cursor() != 0 ){
			fail( "restart() left the cursor at " + player.playback().cursor() + " rather than 0" );
		}
}

/**
 * The window between a restart and the level it is waiting for must not end the run.
 *
 * <p>The other half of {@code issues.md} 1, and the part that actually caused it. A restart nulls
 * {@code Dungeon.hero} and re-enters the interlevel scene, which takes at least a fade to build floor 1;
 * the frame driver runs throughout. Without an explicit wait, the first frame of that gap read the null
 * hero as a finished run.
 *
 * <p>Driven rather than reasoned about: {@link Dungeon#hero} is a static, so it can be nulled here to
 * reproduce the gap exactly as the restart leaves it, and then restored by a fresh reset - which is
 * what the interlevel scene does.
 */
private static void checkRestartKeepsPlaybackArmedAcrossARebuild( Replay good ){
		env.reset( good.seedText, heroClassOf( good ) );
		ReplayPlayer player = new ReplayPlayer( good, ReplayIOConfig( good ) );
		player.speed( 16f );

		player.restart();

		//the gap: no level, no hero, playback must wait rather than declare the run over
		Dungeon.hero = null;
		for (int i = 0; i < 10; i++) player.update( 1f / 60f );

		if ( !player.playing() ){
			fail( "playback ended while the level was still being rebuilt: \"" + player.haltReason()
					+ "\". A restart nulls the hero before the scene switch, so a null hero there means "
					+ "'not yet', not 'over'." );
		}
		if ( player.playback().finished() ){
			fail( "the recording was marked finished during the rebuild window after a restart" );
		}

		//and once the hero is back, playback resumes of its own accord
		env.reset( good.seedText, heroClassOf( good ) );
		for (int i = 0; i < 60 && player.playback().cursor() == 0; i++) player.update( 1f / 60f );

		if ( player.playback().cursor() == 0 ){
			fail( "playback did not resume after the rebuilt level arrived; a restart leaves the viewer "
					+ "stuck on step 1" );
		}
}

/**
 * A death recording's last step must be <em>performed</em>, not merely applied.
 *
 * <p>{@code issues.md} 2: the viewer finished just before the hero died. The cursor check in
 * {@link #checkADeathRunPlaysItsLastStep} cannot see that - {@code applyNextStep} injects the recorded
 * action, so the cursor reaches the end whether or not the hero ever acts on it. What distinguishes them
 * is the world: whether the hero is standing at the end.
 *
 * <p>Run against a real recording rather than a synthetic one. The committed corpus contains runs that
 * end in death - the trainer produced them, so the last step is one that killed the hero - and playing
 * one exercises the exact path a person watching a recording hits. A fixture that forced the hero's
 * health down to force a death would be testing the fixture: editing live state behind the player's back
 * makes every later comparison fail on the edit rather than on the behaviour under test.
 *
 * <p>Skipped, loudly, when no death recording is available. A gate that quietly passes because its case
 * could not be built is worse than one that fails.
 */
private static void checkADeathRunActuallyActsOnItsLastStep(){
	Replay death = corpusRecordingEndingIn( "DEATH" );
	if ( death == null ){
		File corpus = corpusDir();
		fail( "no recording in " + corpus.getAbsolutePath() + " declares termination=DEATH, so the case "
				+ "this check exists for could not be built. A gate that cannot reach its own case is not "
				+ "a passing gate." );
		return;
	}

	Outcome outcome = play( death );

	if ( outcome.ranOutOfFrames ){
		fail( "the death recording " + death.seedText + " hung instead of finishing" );
		return;
	}
	if ( outcome.cursor != death.length() ){
		fail( "the death recording " + death.seedText + " played " + outcome.cursor + " of "
				+ death.length() + " steps: " + outcome.haltReason );
		return;
	}
	if ( outcome.diverged ){
		fail( "the death recording " + death.seedText + " diverged: " + outcome.haltReason );
		return;
	}
	if ( outcome.heroAliveAtEnd ){
		fail( "the death recording " + death.seedText + " ended with the hero still standing. Its last "
				+ "recorded step was applied and then skipped before the hero acted, which is what 'the "
				+ "viewer finishes just before the hero dies' looks like on screen." );
	}
}

/** The committed corpus, or a path the build points elsewhere. Gradle resolves a relative one against the module. */
private static File corpusDir(){
	String configured = System.getProperty( "spd.replayDir" );
	if ( configured != null && !configured.trim().isEmpty()){
		return new File( configured.trim() );
	}
	return new File( "../replays" );
}

/**
 * The score the viewer shows must separate gain from loss.
 *
 * <p>{@code issues.md} 4: the recording carried a per-step reward and the viewer showed only the run's
 * total, so nothing on screen could say which action cost 100 points. The fix computes all four figures
 * from the recording, and this asserts they are the four different numbers a reward function produces -
 * a check that only compared them against themselves would pass on an implementation returning zero.
 */
private static void checkScoreTracksGainAndLossSeparately( Replay good ){
	ReplayPlayback playback = new ReplayPlayback( good );

		double expectedGain = 0, expectedLoss = 0, expectedTotal = 0;
		for (int i = 0; i <= playback.cursor() && i < good.length(); i++){
			double r = good.steps.get( i ).reward;
			expectedTotal += r;
			if ( r > 0 ) expectedGain += r;
			else if ( r < 0 ) expectedLoss += r;
		}

		if ( Math.abs( playback.score() - expectedTotal ) > 1e-6 ){
			fail( "score() reported " + playback.score() + " where the recording sums to " + expectedTotal );
		}
		if ( Math.abs( playback.gained() - expectedGain ) > 1e-6 ){
			fail( "gained() reported " + playback.gained() + " where the positive steps sum to "
					+ expectedGain );
		}
		if ( Math.abs( playback.lost() - expectedLoss ) > 1e-6 ){
			fail( "lost() reported " + playback.lost() + " where the negative steps sum to " + expectedLoss );
		}

		//The split has to be a real split. A run whose steps are all negative gains nothing, and one
		//whose steps are all positive loses nothing - an implementation that returned the net for both
		//would pass the arithmetic above on a mixed run and fail these.
		Replay descending = recordAShortRun();
		for (Replay.Step step : descending.steps){
			step.reward = -Math.abs( step.reward ) - 1;
		}
		ReplayPlayback down = new ReplayPlayback( descending );
		down.advance();
		if ( down.gained() != 0 ){
			fail( "gained() reported " + down.gained() + " on a run whose every step lost reward; a gain "
					+ "figure that cannot be zero is not a gain figure" );
		}
		if ( down.lost() >= 0 ){
			fail( "lost() reported " + down.lost() + " on a run whose every step lost reward; loss is "
					+ "reported as a negative number" );
		}

		//and the per-step delta is the step, not the running total
		Replay one = recordAShortRun();
		one.steps.get( 0 ).reward = 0.25;
		ReplayPlayback first = new ReplayPlayback( one );
		if ( Math.abs( first.stepReward() - 0.25 ) > 1e-9 ){
			fail( "stepReward() reported " + first.stepReward() + " for a first step whose reward is 0.25" );
		}
		first.advance();
		if ( Math.abs( first.score() - 0.25 ) > 1e-9 ){
			fail( "score() reported " + first.score() + " after one step worth 0.25" );
		}
}

/**
 * A cell index has to decode to the coordinates a person is looking at.
 *
 * <p>{@code issues.md} 5: positions were reported as a single number. The packing is
 * {@code y * width + x}, so the decode is fixed by the engine, and this pins both the arithmetic and the
 * direction - y downward, matching the tile map, rather than the bottom-left origin that is prettier and
 * would disagree with every trace in the project.
 */
private static void checkCoordinatesDecodeAPosition(){
	ReplayPlayback playback = new ReplayPlayback( new Replay() );

		//32 is the width every standard floor is built at.
		playback.gridWidth( 32 );

		String topLeft = playback.coordinates( 0, 32 );
		String topRight = playback.coordinates( 31, 32 );
		String belowTopLeft = playback.coordinates( 32, 32 );

		if ( !topLeft.startsWith( "(0, 0)" ) ){
			fail( "cell 0 decoded as " + topLeft + " rather than (0, 0)" );
		}
		if ( !topRight.startsWith( "(31, 0)" ) ){
			fail( "cell 31 decoded as " + topRight + " rather than (31, 0) - x must grow to the right" );
		}
		if ( !belowTopLeft.startsWith( "(0, 1)" ) ){
			fail( "cell 32 decoded as " + belowTopLeft + " rather than (0, 1) - y must grow downward, "
					+ "which is the engine's own order and the one the tile map uses" );
		}

		//the raw index is kept, because a divergence report is often compared against a trace or a
		//recording file and silently dropping it would break that comparison
		if ( !topRight.contains( "31" ) || topRight.endsWith( "(31, 0)" ) ){
			fail( "the coordinate rendering dropped the raw cell index: " + topRight
					+ ". Reports are compared against traces by index." );
		}

		//and with no width it degrades to the index rather than dividing by nothing
		if ( !playback.coordinates( 687, 0 ).equals( "pos 687" ) ){
			fail( "with no known width a cell should render as its raw index; got "
					+ playback.coordinates( 687, 0 ) );
		}
		if ( !playback.coordinates( -1, 32 ).equals( "pos -1" ) ){
			fail( "a negative cell should render as its raw index; got " + playback.coordinates( -1, 32 ) );
		}
}

/**
 * A position divergence has to name coordinates, not a bare index.
 *
 * <p>The arithmetic behind {@link #coordinates} is covered above; what this covers is that a failure
 * report actually uses it, which is the part that was missing when every divergence said "hero at 687".
 * Altering a recorded position and requiring the message to carry the decoded form.
 */
private static void checkPositionsAreReportedAsCoordinates( Replay good ){
		Replay tampered = recordAShortRun();
		Replay.Step step = stepAt( tampered, 30 );
		step.heroPos = step.heroPos + 1;

		Outcome outcome = play( tampered );

		if ( !outcome.diverged ){
			fail( "altering a recorded position went undetected, so the coordinate reporting could not "
					+ "be exercised" );
			return;
		}
		if ( outcome.haltReason == null || !outcome.haltReason.contains( "hero at" ) ){
			fail( "the divergence was reported as: " + outcome.haltReason
					+ " - which does not name the position at all" );
			return;
		}
		if ( !outcome.haltReason.matches( ".*hero at \\(\\d+, \\d+\\).*" ) ){
			fail( "the divergence reported a bare index rather than coordinates: " + outcome.haltReason
					+ ". A cell number tells a reader nothing about where on the floor the hero is." );
		}
}

/**
 * Both halves of a position divergence must be readable.
 *
 * <p>One is enough to locate the step; the other is what says the recording and the viewer disagree
 * about which of two cells, which is the whole question. A message that decoded only the live position
 * would read as a well-specified error and answer nothing.
 */
private static void checkADivergenceMessageNamesCoordinates(){
	Replay tampered = recordAShortRun();
	Replay.Step step = stepAt( tampered, 30 );
	step.heroPos = step.heroPos + 1;

	Outcome outcome = play( tampered );

	if ( !outcome.diverged ){
		fail( "altering a recorded position went undetected" );
		return;
	}

	int matches = 0;
	java.util.regex.Matcher m =
			java.util.regex.Pattern.compile( "\\(\\d+, \\d+\\)" ).matcher( outcome.haltReason );
	while (m.find()) matches++;

	if ( matches < 2 ){
		fail( "the divergence message named coordinates " + matches + " time(s) where the live and "
				+ "recorded positions are both needed: " + outcome.haltReason );
	}
}

// --------------------------------------------------------------------------- helpers

	/**
	 * The HUD anchor is movable, cycles through every corner, and defaults to top left.
	 *
	 * <p>Asserted on the geometry rather than on the drawn result, because the drawn result needs a
	 * window. What can go wrong here is arithmetic - a right-anchored line measuring from the wrong
	 * edge, or the help line stacking off-screen at the anchors that now sit at the top - and all of
	 * it is reachable from the anchor alone.
	 */
	private static void checkTheHudAnchorIsMovableAndDefaultsTopLeft(){
		final float cameraW = 640;
		final float cameraH = 400;
		final float margin = 4;

		if (ReplayController.DEFAULT_ANCHOR != ReplayController.Anchor.TOP_LEFT){
			fail( "the HUD defaults to " + ReplayController.DEFAULT_ANCHOR
					+ "; it should default to TOP_LEFT. Bottom left puts the block over the hero, which is "
					+ "where a recording spends most of its time sitting still." );
		}

		//every corner must place text inside the window, for the widest line the HUD can produce
		float widest = cameraW - 2 * margin;
		for (ReplayController.Anchor at : ReplayController.Anchor.values()){
			for (float lineWidth : new float[]{ 0f, widest }){
				float x = ReplayController.anchorX( at, cameraW, lineWidth );
				if (x < margin - 0.01f){
					fail( at + " put a line of width " + lineWidth + " at x=" + x
							+ ", which runs off the left edge of a " + cameraW + "-wide camera" );
				}
				if (x + lineWidth > cameraW - margin + 0.01f){
					fail( at + " put a line of width " + lineWidth + " at x=" + x
							+ ", which runs off the right edge of a " + cameraW + "-wide camera (ends at "
							+ (x + lineWidth) + ")" );
				}
			}

			//the block must grow inward, so its far end stays on screen however tall it gets
			float top = ReplayController.anchorStartY( at, cameraH, 0 );
			boolean growsDown = top < cameraH / 2f;
			if (growsDown != !at.bottom()){
				fail( at + " starts at y=" + top + ", which does not match its own bottom=" + at.bottom()
						+ ". A top anchor has to grow downward and a bottom anchor upward, or the block "
						+ "grows off the screen in the direction it is anchored." );
			}
			if (top < 0 || top > cameraH){
				fail( at + " starts at y=" + top + ", which is outside a " + cameraH + "-tall camera" );
			}
		}

		//cycling must reach all four corners and come back round, so the hotkey is not a dead end
		java.util.Set<ReplayController.Anchor> seen = new java.util.HashSet<>();
		ReplayController.Anchor at = ReplayController.DEFAULT_ANCHOR;
		for (int i = 0; i < ReplayController.Anchor.values().length; i++){
			seen.add( at );
			at = at.next();
		}
		if (seen.size() != ReplayController.Anchor.values().length){
			fail( "cycling the anchor visited only " + seen.size() + " of "
					+ ReplayController.Anchor.values().length + " corners" );
		}
		if (at != ReplayController.DEFAULT_ANCHOR){
			fail( "cycling the anchor did not return to " + ReplayController.DEFAULT_ANCHOR
					+ " after one full cycle; it came back to " + at );
		}

		//the help line stacks inward from the same corner, pushed clear of the HUD block. At a bottom
		//anchor that puts it above the HUD; at a top anchor, below it.
		for (ReplayController.Anchor corner : ReplayController.Anchor.values()){
			float hudTop = ReplayController.anchorStartY( corner, cameraH, 0 );
			float helpTop = ReplayController.anchorStartY( corner, cameraH, 100 );
			if (helpTop == hudTop){
				fail( corner + " placed the help line at the same y as the HUD, so the two would "
						+ "overlap instead of stacking" );
			}
			if (Math.abs( helpTop - hudTop ) < 90){
				fail( corner + " placed the help line only " + Math.abs( helpTop - hudTop )
						+ "px from the HUD; the 100px inset was not honoured" );
			}
		}
	}

	/**
	 * The HUD must wrap, not run off the screen.
	 *
	 * <p>{@link com.watabou.noosa.BitmapText} cannot wrap: its font has no newline glyph, so the
	 * {@code \n} the HUD has always used renders as a blank and the text keeps going right. That was
	 * survivable while the HUD was short and stopped being survivable once the score line made the
	 * string longer than an ordinary window.
	 *
	 * <p>Width is measured with a fixed per-character width here rather than the real font, because
	 * this runs headlessly where there is no font and every real measurement is zero - a check that
	 * could only pass is worse than no check. The property under test is the wrapping algorithm, not
	 * the pixel font: no line comes out wider than it was allowed to be.
	 */
	private static void checkHudTextWrapsInsteadOfRunningOffScreen(){
		final float charW = 6f;
		final float maxWidth = 200f;
		java.util.function.ToDoubleFunction<String> widthOf = s -> s.length() * charW;

		String hud = "step 40/97 pos 4,7\nseed ABCD-1234 hero WARRIOR\n"
				+ "recorded depth 3   actions 40/200   engine time 41.2\n"
				+ "score 12.34   last step +0.125   +18.5 / -6.2   (final 11.02)\n"
				+ "speed 1.0x   playing";

		ArrayList<String> lines = ReplayController.wrapText( hud, maxWidth, widthOf );

		if (lines.isEmpty()){
			fail( "wrapping the HUD produced no lines at all" );
			return;
		}

		for (String line : lines){
			if (widthOf.applyAsDouble( line ) > maxWidth){
				fail( "HUD line is wider than the screen it must fit in: '" + line + "' is "
						+ widthOf.applyAsDouble( line ) + " against a limit of " + maxWidth );
			}
		}

		//the break must be real, not just a truncation: every word of the original has to survive
		String joined = String.join( " ", lines ).replaceAll( "\\s+", " " ).trim();
		String original = hud.replaceAll( "\\s+", " " ).trim();
		if (!joined.equals( original )){
			fail( "wrapping the HUD changed its content.\n         wrapped:   " + joined
					+ "\n         original: " + original );
		}

		//an explicit \n must still force a break, which is the whole reason wrapText splits on it
		ArrayList<String> breaks = ReplayController.wrapText( "aaa\nbbb", maxWidth, widthOf );
		if (breaks.size() != 2){
			fail( "an explicit newline did not force a line break; got " + breaks.size()
					+ " lines from 2, so multi-line HUD text would be joined back together" );
		}

		//a word too wide to break on spaces is kept whole rather than dropped - dropping it would
		//silently hide the seed, the one value that says which recording is on screen
		ArrayList<String> wide = ReplayController.wrapText( "hi SUPERLONGSINGLETOKEN1234567890", maxWidth, widthOf );
		StringBuilder rebuilt = new StringBuilder();
		for (String line : wide) rebuilt.append( line ).append( ' ' );
		if (!rebuilt.toString().trim().equals( "hi SUPERLONGSINGLETOKEN1234567890" )){
			fail( "a word wider than the line was dropped instead of kept whole: got '" + rebuilt + "'" );
		}
	}

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

