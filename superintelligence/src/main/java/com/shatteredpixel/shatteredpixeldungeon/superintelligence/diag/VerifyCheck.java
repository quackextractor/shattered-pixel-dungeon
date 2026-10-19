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

	private static final int CHECKS = 15;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "verifycheck" ));
		HeadlessServices.disableSaving( true );

		Replay good = recordAShortRun();

		checkAFaithfulReplayPasses( good );
		checkAlteredPositionIsCaught( good );
		checkAlteredHealthIsCaught( good );
		checkAlteredTurnIsCaught( good );
		checkAlteredInventoryIsCaught( good );
		checkAlteredModeIsCaught( good );
		checkARecordingThatCannotResolveItsSlotsIsNotVerified( good );
		checkAHeroWithNothingBoundIsNotRefused( good );
		checkAnAlteredDrawCountIsCaughtAndNamed( good );
		checkAnAlteredAcceptanceIsCaughtAndNamed( good );
		checkTheDeathStepIsNotHeldToADrawCount();
		checkAPrefixDoesNotVerify( good );
		checkTheHeaderCarriesTheRunLimits( good );
		checkVerifyLeavesACallersTraceOn( good );
		checkAnUnknownHeroIsRefused( good );

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
		recorder.begin( "VERIFYCHECK-A", "WARRIOR", 0, 200, config );
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
			recorder.afterStep( env.heroPosition(), reward, env.lastStepActed() ? 1 : 0 );
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

	private static void checkAlteredModeIsCaught( Replay source ){
		checkAlterationIsCaught( source, "mode",
				step -> step.mode = "WORLD".equals( step.mode ) ? "MENU" : "WORLD" );
	}

	private static void checkAnAlteredDrawCountIsCaughtAndNamed( Replay source ){
		String where = "an altered RNG draw count is caught and named";

		if (!source.recordsRngFingerprint()){
			fail( where, "the fixture recorded no draw counts, so there is nothing to alter and the"
					+ " case proved nothing. env.record_rng_fingerprint has to be on for a recording to"
					+ " say anything about the stream." );
			return;
		}

		Replay copy = copyOf( source );
		int altered = -1;
		for (int i = 0; i < copy.steps.size() - 1; i++){
			if (copy.steps.get( i ).baseDraws >= 0){
				copy.steps.get( i ).baseDraws += 3;
				altered = i;
				break;
			}
		}

		if (altered < 0){
			fail( where, "no step carries a draw count" );
			return;
		}

		ReplayIO.Verification result = verify( copy );

		if (result.passed()){
			fail( where, "a recording whose draw count disagrees with its own replay by three"
					+ " verified clean. The comparison is skipped, so a stream divergence reports"
					+ " nothing more specific than whatever field happened to move first." );
			return;
		}
		if (!result.divergence.contains( "baseDraws" )){
			fail( where, "the divergence reported was '" + result.divergence + "' at step "
					+ result.divergedAt + ", and it does not name the draw count. A report that says"
					+ " only where the two sides parted does not say whether they had already parted in"
					+ " the generator." );
			return;
		}

		System.out.println( "  an altered draw count is caught at step " + result.divergedAt
				+ " and named: " + result.divergence );
	}


	private static void checkTheDeathStepIsNotHeldToADrawCount(){
		String where = "the step a hero dies on is not held to a draw count";

		File corpus = new File( repositoryRoot(), "superintelligence/test-replays" );
		File file = new File( corpus, "duelist-death-last.replay" );
		if (!file.isFile()){
			fail( where, "no death recording at " + file.getAbsolutePath() + ", so the case could"
					+ " not be built. A gate that cannot reach its own case is not a passing gate." );
			return;
		}

		Replay death;
		try {
			death = ReplayIO.read( file );
		} catch (java.io.IOException e){
			fail( where, "cannot read " + file + ": " + e.getMessage() );
			return;
		}

		if (death.termination == null || !death.termination.equals( "DEATH" )){
			fail( where, file.getName() + " ends as " + death.termination + " rather than DEATH, so"
					+ " its last step is not a step the hero died on" );
			return;
		}

		ReplayIO.Verification faithful = verify( death );
		if (!faithful.passed()){
			fail( where, "the corpus's own death recording did not verify: at step " + faithful.divergedAt
					+ " - " + faithful.divergence );
			return;
		}

		Replay copy = copyOf( death );
		Replay.Step last = copy.steps.get( copy.steps.size() - 1 );
		if (last.heroHp != 0){
			fail( where, "the last step records " + last.heroHp + " health rather than 0, so the"
					+ " hero is not dead on it" );
			return;
		}
		last.baseDraws = last.baseDraws < 0 ? 12345 : last.baseDraws + 7;
		last.baseFingerprint = last.baseFingerprint + 7;

		ReplayIO.Verification altered = verify( copy );
		if (!altered.passed()){
			fail( where, "a draw count that disagrees only on the step the hero died on failed"
					+ " verification at step " + altered.divergedAt + ": " + altered.divergence
					+ ". Bones.leave draws once per item the hero carried, and it refuses to for a"
					+ " custom seed, so the recording side and the replay side legitimately spend a"
					+ " different number of values on a step that is over either way." );
			return;
		}

		Replay live = copyOf( death );
		for (int i = 0; i < live.steps.size() - 1; i++){
			if (live.steps.get( i ).baseDraws >= 0){
				live.steps.get( i ).baseDraws += 5;
				ReplayIO.Verification result = verify( live );
				if (result.passed()){
					fail( where, "a draw count altered on a step where the hero was alive verified"
							+ " clean, so the case cannot tell the two apart" );
				}
				return;
			}
		}
		fail( where, "no step before the death carries a draw count, so the live-step half of the"
				+ " case could not be built" );
	}


	private static File repositoryRoot(){
		File dir = new File( System.getProperty( "user.dir" ) ).getAbsoluteFile();
		for (int depth = 0; depth < 12 && dir != null; depth++){
			if (new File( dir, "settings.gradle" ).isFile()) return dir;
			dir = dir.getParentFile();
		}
		return new File( "." ).getAbsoluteFile();
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

	private static void checkAPrefixDoesNotVerify( Replay source ){
		String where = "a run that ends before its recording does is not verified";

		Replay copy = copyOf( source );
		copy.turnLimitPerFloor = 5;
		ReplayIO.Verification result = verify( copy );
		if (result.passed()){
			fail( where, "the replay stopped after " + result.stepsVerified + " of " + copy.steps.size()
					+ " steps and verified clean. Only a prefix was checked." );
			return;
		}
		System.out.println( "  a prefix is reported: " + result.divergence );
	}

	private static void checkTheHeaderCarriesTheRunLimits( Replay source ){
		String where = "the header carries every limit that can end a run";

		Replay copy = copyOf( source );
		copy.turnLimitTotal = 11;
		copy.stallLimit = 12;
		copy.freeStepBudget = 13;
		copy.actorStepLimit = 14;
		EnvConfig read;
		try {
			File file = File.createTempFile( "spd-verifycheck", ".replay" );
			file.deleteOnExit();
			ReplayIO.write( copy, file );
			read = ReplayIO.configFor( ReplayIO.read( file ));
		} catch (java.io.IOException e){
			fail( where, "round trip failed: " + e.getMessage() );
			return;
		}
		if (read.turnLimitTotal != 11 || read.stallLimit != 12 || read.freeStepBudget != 13
				|| read.actorStepLimit != 14){
			fail( where, "wrote 11/12/13/14, read back " + read.turnLimitTotal + "/" + read.stallLimit + "/"
					+ read.freeStepBudget + "/" + read.actorStepLimit
					+ ", so verify replays under limits the run never had" );
		}
	}

	private static void checkAnUnknownHeroIsRefused( Replay source ){
		Replay copy = copyOf( source );
		copy.heroClass = "BARBARIAN";
		ReplayIO.Verification result = verify( copy );
		if (result.unwatchableAt != 0){
			fail( "an unknown hero class is refused", "a recording of hero BARBARIAN was "
					+ ( result.diverged ? "replayed as a Warrior and diverged at step " + result.divergedAt
							: "replayed without complaint" ) );
		}
	}

	private static void checkVerifyLeavesACallersTraceOn( Replay source ){
		Replay copy = copyOf( source );
		for (Replay.Step step : copy.steps) step.baseDraws = -1;
		com.watabou.utils.RandomTrace.enable();
		verify( copy );
		boolean on = com.watabou.utils.RandomTrace.enabled();
		com.watabou.utils.RandomTrace.disable();
		if (!on){
			fail( "verify leaves a caller's RNG trace on", "verifying a recording without draw counts turned"
					+ " the trace off, so rngtrace on such a file reports zero draws for every step" );
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
		s.baseDraws = step.baseDraws;
		s.baseFingerprint = step.baseFingerprint;
			s.accepted = step.accepted;
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


private static void checkAnAlteredAcceptanceIsCaughtAndNamed( Replay source ){
		String where = "an altered acceptance flag is caught and named";

		if ( !recordsAcceptance( source )){
			fail( where, "the fixture recorded no acceptance flags, so there is nothing to alter."
					+ " The trainer's worker has to write it for this case to say anything." );
			return;
		}

		Replay copy = copyOf( source );
		int altered = -1;
		for (int i = 0; i < copy.steps.size() - 1; i++){
			Replay.Step step = copy.steps.get( i );
			if (step.accepted < 0) continue;
			step.accepted = step.accepted == 1 ? 0 : 1;
			altered = i;
			break;
		}

		if (altered < 0){
			fail( where, "no step carries an acceptance flag" );
			return;
		}

		ReplayIO.Verification result = ReplayIO.verify( copy,
				new SPDEnv( ReplayIO.configFor( copy ), HeadlessGame.install() ) );

		if (!result.diverged){
			fail( where, "verification reported no divergence after flipping step " + altered
					+ " from accepted to refused. A recording cannot say whether the engine acted on a"
					+ " step, so a viewer refusal and a trainer refusal look identical - which is"
					+ " issues.md 26's question exactly." );
			return;
		}
		if (result.divergedAt != altered){
			fail( where, "verification caught the altered acceptance at step " + result.divergedAt
					+ " rather than at " + altered + ", so the flag is not what stopped it" );
			return;
		}
		if (result.divergence == null || !result.divergence.contains( "accepted" )){
			fail( where, "the divergence is '" + result.divergence + "' and does not say the two sides"
					+ " disagree about whether the action was applied" );
			return;
		}

		System.out.println( "  " + where + ": " + result.divergence );
	}


	private static boolean recordsAcceptance( Replay replay ){
		for (Replay.Step step : replay.steps) if (step.accepted >= 0) return true;
		return false;
	}
}
