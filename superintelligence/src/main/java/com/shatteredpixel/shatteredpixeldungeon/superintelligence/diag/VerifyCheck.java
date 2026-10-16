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

		checkAlterationIsCaught( source, "hp",
				step -> { if (step.heroHp > 0) step.heroHp -= 1; } );
	}

	private static void checkAlteredTurnIsCaught( Replay source ){

		checkAlterationIsCaught( source, "turn",
				step -> { if (step.turn >= 0) step.turn = step.turn + 0.25f; } );
	}

	private static void checkAlteredInventoryIsCaught( Replay source ){
		checkAlterationIsCaught( source, "inventory",
				step -> { if (!step.inventory.isEmpty()) step.inventory = step.inventory + ",Ghost:1"; } );
	}

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
			step.quickslots = "";
			step.heroHp = -1;
			step.turn = -1f;
			step.inventory = "";
		}

		ReplayIO.Verification result = verify( copy );

		if ( result.passed() ){
			fail( where, "a recording carrying none of the five dropped step fields still verified: "
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

		System.out.println( "  a recording carrying none of the dropped step fields is refused at step "
				+ index + " instead of verifying: " + result.unwatchable );
	}

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
			fail( where, "only " + first + " slot-bearing step(s) carried bindings, so nothing could be"
					+ " blanked without emptying the whole recording" );
			return;
		}

		ReplayIO.Verification result = verify( copy );
		if (!result.passed()){
			fail( where, blanked + " slot steps were given no bindings while the first kept its own and"
					+ " every step kept real health and engine time, and the recording was "
					+ ( result.diverged ? "diverged at step " + result.divergedAt + ": " + result.divergence
							: "refused at step " + result.unwatchableAt + ": " + result.unwatchable )
					+ ". Nothing bound once the hero drops his last bound item, and the index still"
					+ " resolves from backpack order." );
			return;
		}

		System.out.println( "  " + blanked + " slot steps with nothing bound still verify, while health"
				+ " and engine time stay real - the hero carried nothing, which is a state" );
	}

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