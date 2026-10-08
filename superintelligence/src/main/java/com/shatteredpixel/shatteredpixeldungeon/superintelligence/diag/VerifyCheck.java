package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Verification has to be able to fail, and has to compare against the recording rather than itself.
 *
 * <pre>gradle :superintelligence:verifycheck</pre>
 *
 * <p>Replaying a recording re-runs it through the same headless code that made it, so agreement between
 * two such runs proves the engine is deterministic and nothing else. Both sides share every
 * headless-specific fault, so a systematic difference between the trainer and the rendered game is
 * invisible to it by construction. That is not hypothetical: a run whose hunger clock was frozen by the
 * intro setting replayed perfectly - identical position at every one of 1500 steps - while the hero was
 * dying of starvation in the real game, because a starving hero stands exactly where a fed one does.
 *
 * <p>So the recorded state, not a second run, is the authority, and each field catches a class the
 * others cannot: position catches a wrong path, health catches a frozen or un-ticked mechanic, engine
 * time catches a step that costs a different amount of time, and inventory catches an action applied to
 * the wrong item.
 *
 * <p>This asserts both halves of that. That a faithful replay passes, so the checks are not simply
 * rejecting everything; and that a replay altered in each of those four fields fails at the step that was
 * altered, naming the field. Without the second half the checks could be inert and still look healthy.
 */
public class VerifyCheck {

	private static final int CHECKS = 5;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-verifycheck" ));
		HeadlessServices.disableSaving( true );

		Replay good = recordAShortRun();

		checkAFaithfulReplayPasses( good );
		checkAlteredPositionIsCaught( good );
		checkAlteredHealthIsCaught( good );
		checkAlteredTurnIsCaught( good );
		checkAlteredInventoryIsCaught( good );

		if (failures.isEmpty()){
			System.out.println( "[OK]     verification detects divergence: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  verification detects divergence: " + failures.size()
					+ " of " + CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	/** Records a short run with the scripted policy, which is the cheapest source of a real replay. */
	private static Replay recordAShortRun(){
		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 200;
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( "VERIFYCHECK-A", HeroClass.WARRIOR );

		ReplayRecorder recorder = new ReplayRecorder();
		recorder.begin( "VERIFYCHECK-A", "WARRIOR", 0, 200 );
		Replay replay = recorder.replay();

		int[] slot = new int[ 1 ];
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy policy =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy(
						env.mapper(), 7 );

		while (env.running() && replay.length() < 120){
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action action =
					policy.choose( env, slot );
			recorder.record( action, slot[ 0 ], env.mode() );
			float reward = (float) env.step( action, slot[ 0 ] );
			recorder.afterStep( env.heroPosition(), reward );
		}

		if (replay.length() < 20){
			failures.add( "recorded only " + replay.length()
					+ " steps; the checks need a run long enough to alter fields in" );
		}
		return replay;
	}

	private static void checkAFaithfulReplayPasses( Replay source ){
		String where = "an unaltered replay verifies";

		ReplayIO.Verification result = verify( source );
		if (!result.diverged) return;

		fail( where, "a faithful replay was reported as diverging at step " + result.divergedAt
				+ ": " + result.divergence );
	}

	private static void checkAlteredPositionIsCaught( Replay source ){
		checkAlterationIsCaught( source, "position",
				step -> step.heroPos = step.heroPos + 1 );
	}

	private static void checkAlteredHealthIsCaught( Replay source ){
		//The field that mattered most: a frozen hunger clock left health untouched while the hero starved
		//in the game. Alter it by one point, as any damage difference would.
		checkAlterationIsCaught( source, "hp",
				step -> { if (step.heroHp > 0) step.heroHp -= 1; } );
	}

	private static void checkAlteredTurnIsCaught( Replay source ){
		//Turns are fractional durations, so this is off by a fraction rather than a whole one.
		checkAlterationIsCaught( source, "turn",
				step -> { if (step.turn >= 0) step.turn = step.turn + 0.25f; } );
	}

	private static void checkAlteredInventoryIsCaught( Replay source ){
		checkAlterationIsCaught( source, "inventory",
				step -> { if (!step.inventory.isEmpty()) step.inventory = step.inventory + ",Ghost:1"; } );
	}

	private interface Alteration {
		void apply( Replay.Step step );
	}

	/**
	 * Alters one step and requires verification to fail at that step, naming the field.
	 *
	 * <p>Both halves matter: failing somewhere proves the check is live, but failing <em>at the altered
	 * step and for the right reason</em> is what proves it is comparing what it claims to. A check that
	 * failed on the final step for an unrelated reason would otherwise pass this.
	 */
	private static void checkAlterationIsCaught( Replay source, String field, Alteration alteration ){
		String where = "an altered " + field + " is caught";

		Replay copy = copyOf( source );
		int index = copy.steps.size() / 2;
		alteration.apply( copy.steps.get( index ) );

		ReplayIO.Verification result = verify( copy );

		if (!result.diverged){
			fail( where, "altering step " + index + " was not detected at all" );
			return;
		}
		if (result.divergedAt != index){
			fail( where, "altering step " + index + " was reported at step " + result.divergedAt
					+ " instead" );
			return;
		}
		if (!result.divergence.startsWith( field )){
			fail( where, "reported \"" + result.divergence + "\", which does not name " + field );
		}
	}

	private static Replay copyOf( Replay source ){
		Replay copy = new Replay();
		copy.seedText = source.seedText;
		copy.heroClass = source.heroClass;
		copy.turnLimitPerFloor = source.turnLimitPerFloor;
		for (Replay.Step step : source.steps){
			Replay.Step s = new Replay.Step();
			s.action = step.action;
			s.slot = step.slot;
			s.mode = step.mode;
			s.heroPos = step.heroPos;
			s.reward = step.reward;
			s.quickslots = step.quickslots;
			s.heroHp = step.heroHp;
			s.turn = step.turn;
			s.inventory = step.inventory;
			copy.steps.add( s );
		}
		return copy;
	}

	private static ReplayIO.Verification verify( Replay replay ){
		EnvConfig config = new EnvConfig();
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		return ReplayIO.verify( replay, env );
	}

	private static void fail( String where, String what ){
		failures.add( where + ": " + what );
	}
}