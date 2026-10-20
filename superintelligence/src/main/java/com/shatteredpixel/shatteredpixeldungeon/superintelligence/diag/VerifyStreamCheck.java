package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;

import java.io.File;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GateReport.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.VerifyCheck.copyOf;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.VerifyCheck.verify;

final class VerifyStreamCheck {

	private VerifyStreamCheck(){}

	static void checkAnAlteredDrawCountIsCaughtAndNamed( Replay source ){
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

	static void checkTheDeathStepIsNotHeldToADrawCount(){
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

	static void checkVerifyLeavesACallersTraceOn( Replay source ){
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

	static void checkAnAlteredAcceptanceIsCaughtAndNamed( Replay source ){
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
