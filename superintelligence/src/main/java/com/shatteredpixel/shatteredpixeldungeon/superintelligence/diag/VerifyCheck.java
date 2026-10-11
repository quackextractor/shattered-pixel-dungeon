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

	private static final int CHECKS = 8;

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
		checkARecordingThatCannotResolveItsSlotsIsNotVerified( good );
		checkAHeroWithNothingBoundIsNotRefused( good );
		checkAWatchableRecordingIsNotRefused( good );

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
		if (!result.passed()){
			fail( where, result.diverged
					? "a faithful replay was reported as diverging at step " + result.divergedAt
							+ ": " + result.divergence
					: "a faithful replay was refused at step " + result.unwatchableAt + ": "
							+ result.unwatchable );
		}
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

	/**
	 * A recording whose slot index cannot be resolved is not a verified recording.
	 *
	 * <p><b>This is the case the sentinel guards could never be.</b> Every comparison in
	 * {@code ReplayIO.verify} is wrapped in a guard that skips it when the field says <i>not recorded</i>
	 * - {@code step.heroPos >= 0}, {@code !step.inventory.isEmpty()} - and an empty quickslots field is
	 * such a sentinel. So a recording whose bindings were never written verified on whatever else it
	 * carried and printed {@code [OK] replay reproduced in 655 steps}, while the viewer refused to play it
	 * at step 1. Nine of the ten recordings the trainer had written were in that state and every one of
	 * them reported success.
	 *
	 * <p>A guard written to let an unrecorded field pass cannot tell <i>not applicable</i> from
	 * <i>lost</i>, so something has to assert that the sentinel is absent. This is that something, and it
	 * is the same shape as {@code TODO.md} 1.12's warning about the sentinel itself.
	 *
	 * <p>The bindings are blanked on <em>every</em> slot-bearing step rather than one, and that is the
	 * shape the question needs. An empty binding set on a single step is a legitimate state - a hero who
	 * drops the one item bound to a quickslot has none left - and only a recording with no bindings
	 * anywhere is one made before the field existed. Measured on a fresh trainer run: 211 SLOT and
	 * INVENTORY steps, one carrying bindings and the rest empty because the policy dropped the Waterskin
	 * at step 0. Refusing those would have refused a recording that plays perfectly.
	 */
	private static void checkARecordingThatCannotResolveItsSlotsIsNotVerified( Replay source ){
		String where = "a recording that cannot resolve its slots is refused";

		int index = firstSlotBearingStep( source );
		if (index < 0){
			fail( where, "the recorded run has no SLOT or INVENTORY step, so no slot index could be made"
					+ " unresolvable. A gate that cannot reach its own case is not a passing gate." );
			return;
		}

		Replay copy = copyOf( source );
		for (Replay.Step step : copy.steps){
			if (step.mode.equals( "SLOT" ) || step.mode.equals( "INVENTORY" )) step.quickslots = "";
		}

		ReplayIO.Verification result = verify( copy );

		if ( result.passed() ){
			fail( where, "a recording with no bindings on any step still verified: "
					+ ( result.diverged ? "diverged at " + result.divergedAt
							: "reproduced in " + result.stepsVerified + " steps with no complaint" )
					+ ". A step naming a slot index cannot be played without the bindings that index was"
					+ " chosen against, and reporting it as reproduced is the claim this gate exists to"
					+ " stop making." );
			return;
		}
		if ( result.unwatchableAt != index ){
			fail( where, "removing the bindings was reported at step "
					+ result.unwatchableAt + ( result.diverged ? " as a divergence (" + result.divergence
							+ ")" : "" ) + " rather than as the first step that cannot be played" );
			return;
		}

		System.out.println( "  a recording with no bindings anywhere is refused at step " + index
				+ " instead of verifying: " + result.unwatchable );
	}

	/**
	 * A hero carrying nothing bound is a state, not a gap.
	 *
	 * <p>The converse of the case above, and the one that was wrong first. Blank a single slot-bearing
	 * step's bindings and refuse it, and every recording that drops its only bound item becomes
	 * unplayable - which is a false positive about a real state rather than a detection.
	 *
	 * <p>So the question is asked of the recording rather than of the step: bindings present somewhere and
	 * absent at this moment means nothing was bound at this moment. This builds that shape by blanking
	 * every slot step <em>after</em> the first one, and requires the run to verify.
	 */
	private static void checkAHeroWithNothingBoundIsNotRefused( Replay source ){
		String where = "an empty binding set is a state, not a missing field";

		int first = firstSlotBearingStep( source );
		if (first < 0){
			fail( where, "the recorded run has no SLOT or INVENTORY step, so this case could not run" );
			return;
		}

		Replay copy = copyOf( source );
		int blanked = 0;
		for (int i = first + 1; i < copy.steps.size(); i++){
			Replay.Step step = copy.steps.get( i );
			if (!step.mode.equals( "SLOT" ) && !step.mode.equals( "INVENTORY" )) continue;
			if (step.quickslots.isEmpty()) continue;
			step.quickslots = "";
			blanked++;
		}
		if (blanked == 0){
			fail( where, "only " + ( source.steps.size() - 1 ) + " slot-bearing step(s) carried bindings, so"
					+ " nothing could be blanked without emptying the whole recording" );
			return;
		}

		ReplayIO.Verification result = verify( copy );
		if (!result.passed()){
			fail( where, blanked + " slot steps were given no bindings while the first kept its own, and"
					+ " the recording was "
					+ ( result.diverged ? "diverged at step " + result.divergedAt + ": " + result.divergence
							: "refused at step " + result.unwatchableAt + ": " + result.unwatchable )
					+ ". Nothing is bound once the hero drops his last bound item, and the index still"
					+ " resolves from backpack order." );
			return;
		}

		System.out.println( "  " + blanked + " slot steps with no bindings in force still verify, while"
				+ " the first keeps the binding that proves the field is there" );
	}

	/**
	 * The converse: a recording that <em>can</em> be watched must not be refused.
	 *
	 * <p>Added with the case above and for the same reason. A refusal that fires on everything is a
	 * refusal that reports nothing, and {@code MSU-OTL-OAT} is the file that has to keep passing: it is
	 * the post-fix trainer recording, so it is the one thing on disk that says whether the wire fix
	 * actually produces watchable output.
	 */
	private static void checkAWatchableRecordingIsNotRefused( Replay source ){
		String where = "a watchable recording is not refused";

		if (firstSlotBearingStep( source ) < 0){
			fail( where, "the recorded run has no SLOT or INVENTORY step, so this case could not run" );
			return;
		}

		ReplayIO.Verification result = verify( source );
		if ( result.unwatchableAt >= 0 ){
			fail( where, "a faithful recording was refused at step " + result.unwatchableAt + ": "
					+ result.unwatchable );
		}
	}

	/** The first step that resolves a slot index, or -1 when the run has none. */
	private static int firstSlotBearingStep( Replay source ){
		for (int i = 0; i < source.steps.size(); i++){
			Replay.Step step = source.steps.get( i );
			if (step.mode.equals( "SLOT" ) || step.mode.equals( "INVENTORY" )) return i;
		}
		return -1;
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