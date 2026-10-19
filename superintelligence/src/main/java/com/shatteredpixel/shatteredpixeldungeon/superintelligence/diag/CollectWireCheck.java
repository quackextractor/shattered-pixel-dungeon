package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.StallCause;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.ReplayCodec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.CollectTrace.fail;

public final class CollectWireCheck {

	private CollectWireCheck(){}

	public static void checkTheEpisodeFrameCarriesEveryReportedField(){
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Episode sent
				= new com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Episode();
		sent.score = -3521.5;
		sent.depth = 2;
		sent.turns = 811;
		sent.endedNaturally = false;
		sent.reason = "STALLED";
		sent.stallCause = StallCause.FREE_STEP.ordinal();
		sent.objectiveScore = -2903.25;
		sent.termTotals[ RewardTerm.DEPTH_ADVANCE.ordinal() ] = 1800.0;
		sent.termTotals[ RewardTerm.TURN_COST.ordinal() ] = -811.0;
		sent.termCounts[ RewardTerm.DEPTH_ADVANCE.ordinal() ] = 1;
		sent.termCounts[ RewardTerm.TURN_COST.ordinal() ] = 811;
		sent.termCounts[ RewardTerm.KILL.ordinal() ] = 3;

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Episode back;
		try {
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Episode.write(
					new java.io.DataOutputStream( bytes ), sent );
			back = com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Episode.read(
					new java.io.DataInputStream( new java.io.ByteArrayInputStream( bytes.toByteArray() )));
		} catch (java.io.IOException e){
			fail( "the episode frame could not round-trip: " + e.getMessage() );
			return;
		}

		if (back.stallCause != sent.stallCause){
			fail( "the stall cause did not survive the pipe: " + StallCause.values()[ sent.stallCause ]
					+ " -> " + StallCause.values()[ back.stallCause ] + ". Every STALLED episode reads as"
					+ " the same one, so a run cannot say which guard is binding." );
			return;
		}

		if (back.objectiveScore != sent.objectiveScore){
			fail( "the objective score did not survive the pipe: " + sent.objectiveScore + " -> "
					+ back.objectiveScore + ". Without it a run's descent cannot be separated from the"
					+ " exploration shaping it is measured against." );
			return;
		}

		for (int i = 0; i < sent.termTotals.length; i++){
			if (back.termTotals[ i ] == sent.termTotals[ i ] ) continue;
			fail( "term total " + RewardTerm.at( i ) + " did not survive the pipe: "
					+ sent.termTotals[ i ] + " -> " + back.termTotals[ i ]
					+ ". The generation report prints type subtotals built from these, so a field that"
					+ " falls off the wire reports a score the run did not earn." );
			return;
		}

		for (int i = 0; i < sent.termCounts.length; i++){
			if (back.termCounts[ i ] == sent.termCounts[ i ] ) continue;
			fail( "term count " + RewardTerm.at( i ) + " did not survive the pipe: "
					+ sent.termCounts[ i ] + " -> " + back.termCounts[ i ] );
			return;
		}

		System.out.println( "  the episode frame carries the stall cause, the objective score and every"
				+ " term total and count across the pipe" );
	}


	public static void checkWorkerWireCarriesEveryStepField( EnvConfig config ){
		Replay sample = new Replay();
		sample.seedText = "WIRECHECK";
		sample.heroClass = "ROGUE";
		sample.challenges = 3;
		sample.generation = 11;
		sample.score = -12.5;
		sample.depth = 2;
		sample.turns = 97;
		sample.turnLimitPerFloor = 1500;
		sample.maxSlots = 32;
		sample.allowEquipping = false;
		sample.termination = "DEATH";

		sample.rewardProfile = RewardProfile.DEPTH_ONLY;

		Replay.Step s = new Replay.Step();
		s.action = "USE";
		s.slot = 4;
		s.mode = "SLOT";
		s.heroPos = 1234;
		s.reward = -1.25;
		s.quickslots = "0:Food,1:Waterskin";
		s.heroHp = 17;
		s.turn = 42.5f;
		s.inventory = "Food:1,Waterskin:2";
		s.accepted = 1;
		s.baseDraws = 918273L;
		s.baseFingerprint = 0x0123456789ABCDEFL;
		sample.steps.add( s );

		Replay back;
		try {
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			ReplayCodec.write( new java.io.DataOutputStream( bytes ), sample );
			java.io.DataInputStream in = new java.io.DataInputStream(
					new java.io.ByteArrayInputStream( bytes.toByteArray() ));
			back = ReplayCodec.read( in, sample.steps.size() );
		} catch (java.io.IOException e){
			fail( "the worker replay wire could not round-trip: " + e.getMessage() );
			return;
		}

		if (back.steps.size() != 1){
			fail( "the wire carried " + back.steps.size() + " steps instead of 1" );
			return;
		}

		Replay.Step r = back.steps.get( 0 );

		if (r.heroHp != s.heroHp ){
			fail( "heroHp did not survive the wire: " + s.heroHp + " -> " + r.heroHp
					+ ". ReplayIO.verify skips its health comparison when heroHp is negative, so a"
					+ " recording that lost it verifies on position alone and reports success." );
			return;
		}
		if (r.turn != s.turn ){
			fail( "engine time did not survive the wire: " + s.turn + " -> " + r.turn
					+ ". ReplayIO.verify skips its turn comparison when turn is negative, which is"
					+ " exactly the one-turn-drift case health and turn were added to catch." );
			return;
		}
		if (!s.inventory.equals( r.inventory )){
			fail( "inventory did not survive the wire: '" + s.inventory + "' -> '" + r.inventory
					+ "'. ReplayIO.verify skips an empty inventory, so a lost slot binding is invisible." );
			return;
		}
		if (r.reward != s.reward ){
			fail( "per-step reward did not survive the wire: " + s.reward + " -> " + r.reward
					+ ". The viewer's running score and per-step delta are computed from it." );
			return;
		}
		if (!s.quickslots.equals( r.quickslots )){
			fail( "quickslot bindings did not survive the wire: '" + s.quickslots + "' -> '"
					+ r.quickslots + "'. A recorded slot index means nothing without them." );
			return;
		}
		if (r.accepted != s.accepted ){
			fail( "accepted did not survive the wire: " + s.accepted + " -> " + r.accepted
					+ ". Without it a step the engine refused is indistinguishable from one it applied,"
					+ " and issues.md 26 is exactly that question.");
			return;
		}
		if (r.baseDraws != s.baseDraws || r.baseFingerprint != s.baseFingerprint ){
			fail( "the RNG fingerprint did not survive the wire: " + s.baseDraws + "/"
					+ s.baseFingerprint + " -> " + r.baseDraws + "/" + r.baseFingerprint
					+ ". ReplayIO.verify skips the comparison on a negative count, so a lost field"
					+ " means a recording that diverged for want of one draw reports nothing about the"
					+ " stream." );
			return;
		}
		if (back.maxSlots != sample.maxSlots || back.allowEquipping != sample.allowEquipping ){
			fail( "the header did not carry the slot width or the equipping rule, so a recorded slot"
					+ " index cannot be resolved the same way twice" );
			return;
		}
		if (back.turnLimitPerFloor != sample.turnLimitPerFloor || back.depth != sample.depth
				|| back.termination.compareTo( sample.termination ) != 0 ){
			fail( "the header lost turnLimitPerFloor, depth or termination" );
			return;
		}
		if (back.rewardProfile != sample.rewardProfile ){
			fail( "the header lost the reward profile: " + sample.rewardProfile + " -> "
					+ back.rewardProfile + ". A recording written under a profile and read back as the"
					+ " default reports a score and a reward column that never matched the run, and says"
					+ " nothing about the trajectory." );
			return;
		}

		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		Network network = new Network( config, new Random( 31337L ) );
		EpisodeCollector collector = new EpisodeCollector( config, network,
				new Random( 5L ), new Random( 11L ), 0.99f, 0.95f, 0.05f );

		final ReplayRecorder recorder = new ReplayRecorder();

		collector.listener( new EpisodeCollector.Listener() {
			@Override public void onEpisodeStart( String seedText, String hero ){
				recorder.begin( seedText, hero, 0, config.turnLimitPerFloor, config );
			}

			@Override public void onDecision( EnvMode mode, Action action, int secondary ){
				recorder.record( action, secondary, mode );
			}

			@Override public void onStep( int heroPosition, float reward ){
				recorder.afterStep( heroPosition, reward );
			}
		} );

		collector.run( env, "WIRE-CHECK", HeroClass.WARRIOR );
		collector.listener( null );
		recorder.end( env.ledger().total(), env.deepestDepth(), env.turnsTotal(), 0,
				env.endReason().name() );

		Replay recorded = recorder.replay();
		if (recorded.steps.isEmpty()){
			fail( "the worker-wire case recorded no steps, so nothing was checked" );
			return;
		}

		int noHp = 0, noTurn = 0, noRng = 0;
		Set< String > inventories = new LinkedHashSet<>();
		for (Replay.Step step : recorded.steps){
			if (step.heroHp < 0) noHp++;
			if (step.turn < 0) noTurn++;
			if (step.baseDraws < 0) noRng++;
			inventories.add( step.inventory );
		}

		int n = recorded.steps.size();
		if (noHp == n ){
			fail( n + " of " + n + " recorded steps carry no hero health. Every verifier skips its"
					+ " health comparison on a negative value, so this recording would verify on"
					+ " position alone. This is the four-field wire bug." );
			return;
		}
		if (noTurn == n ){
			fail( n + " of " + n + " recorded steps carry no engine time, so a run one turn adrift"
					+ " would verify clean." );
			return;
		}
		if (noRng == n ){
			fail( n + " of " + n + " recorded steps carry no RNG draw count, so the recording says"
					+ " nothing about whether the two sides had consumed the same randomness - which is"
					+ " the question issues.md 18 and 37 both turn on." );
			return;
		}

		if (recorded.steps.get( 0 ).inventory.isEmpty() ){
			fail( "step 0 carries no inventory, but every hero class starts with a VelvetPouch, so"
					+ " this field is not being written at all" );
			return;
		}
		if (inventories.size() < 2 ){
			fail( "the inventory is identical on all " + n + " steps ('"
					+ recorded.steps.get( 0 ).inventory + "'), so it is not tracking what the hero is"
					+ " carrying - the bindings were frozen, not dropped" );
			return;
		}

		System.out.println( "  worker wire carries every step field over " + n + " recorded steps;"
				+ " " + inventories.size() + " distinct inventories, hp, turn and the RNG draw count"
				+ " on every step" );
	}

	public static void checkTheBaselineIsInTheUnitsOfTheReturns( EnvConfig config ){
		PPO ppo = new PPO( config, new Random( 1L ) );
		List< Transition > returns = new ArrayList<>();
		for (int i = 0; i < 64; i++){
			Transition t = new Transition();
			t.returnValue = 100f + ( i % 2 == 0 ? 4f : -4f );
			returns.add( t );
		}
		ppo.valueNorm.update( returns );

		List< Transition > raw = baselines( config, 0f, 1f );
		List< Transition > scaled = baselines( config, ppo.baselineMean(), ppo.baselineStd() );

		double worst = 0;
		for (int i = 0; i < Math.min( raw.size(), scaled.size() ); i++){
			float expected = ppo.baselineMean() + ppo.baselineStd() * raw.get( i ).value;
			worst = Math.max( worst, Math.abs( scaled.get( i ).value - expected ));
		}

		if (raw.isEmpty() || raw.size() != scaled.size() || worst > 1e-3){
			fail( "with the critic trained on returns normalised by mean " + ppo.baselineMean() + " and std "
					+ ppo.baselineStd() + ", the collector's baseline is off its return-unit value by "
					+ worst + " over " + raw.size() + " steps. GAE subtracts that baseline from raw rewards,"
					+ " so it has to be denormalised before it is stored." );
			return;
		}
		System.out.println( "  the collector stores the critic's value denormalised by mean "
				+ ppo.baselineMean() + " and std " + ppo.baselineStd() + " over " + raw.size() + " steps" );
	}

	private static List< Transition > baselines( EnvConfig config, float mean, float std ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		EpisodeCollector collector = new EpisodeCollector( config, new Network( config, new Random( 3L ) ),
				new Random( 4L ), new Random( 5L ), 0.99f, 0.95f, 1f, 4096 );
		collector.valueScale( mean, std );
		List< Transition > out = new ArrayList<>( collector.run( env, "BASELINE", HeroClass.WARRIOR )
				.sampledTransitions() );
		out.sort( ( a, b ) -> Integer.compare( a.stepIndex, b.stepIndex ));
		return out;
	}
}
