package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.Outcome;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeRecord;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.util.Random;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fmt;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.play;

public final class RewardForfeitCheck {

	private RewardForfeitCheck(){}

	public static void checkAnEarlyDeathIsNotCheaperThanALateOne( EnvConfig config ){
		Outcome early = dieAfter( config, 5 );
		Outcome late = dieAfter( config, 100 );
		if (early.endReason != RewardModel.TerminateReason.DEATH || late.endReason != RewardModel.TerminateReason.DEATH){
			fail( "the forfeit fixture ended " + early.endReason + " and " + late.endReason
					+ " rather than in two deaths, so the price of dying early was never measured" );
			return;
		}
		if (early.score > late.score + config.turnCost + 1e-6){
			fail( "dying after " + early.turns + " turns scored " + fmt( early.score ) + " and dying after "
					+ late.turns + " scored " + fmt( late.score ) + ". Every turn alive costs turn_cost, so"
					+ " unless a death forfeits the turns it did not live, the cheapest way through a run that"
					+ " cannot descend is to die at once - which is what depth_only learned." );
			return;
		}
		System.out.println( "  an early death costs what a late one does: " + fmt( early.score ) + " after "
				+ early.turns + " turns, " + fmt( late.score ) + " after " + late.turns );
	}

	public static void checkAPricedEndingIsTerminalToTheLearner( EnvConfig config ){
		EnvConfig local = config.copy();
		local.turnLimitPerFloor = 4;
		RewardFixtures.prepare();
		SPDEnv env = new SPDEnv( local, HeadlessGame.install() );
		EpisodeCollector collector = new EpisodeCollector( local, new Network( local, new Random( 7L ) ),
				new Random( 8L ), new Random( 9L ), 0.99f, 0.95f, 1f );
		EpisodeRecord record = collector.run( env, "FORFEITCHECK", HeroClass.WARRIOR );
		Transition last = null;
		for (Transition t : record.sampledTransitions()){
			if (last == null || t.stepIndex > last.stepIndex) last = t;
		}
		if (env.running() || last == null || last.stepIndex != record.stepCount() - 1){
			fail( "the collector's episode did not end with its last step sampled, so its terminal flag was"
					+ " never measured" );
			return;
		}
		if (!last.terminal){
			fail( "the collector recorded the last step of a " + env.endReason() + " episode as non-terminal,"
					+ " so GAE adds the critic's guess at a future the episode does not have on top of the"
					+ " price it was charged. Bootstrapping past a stall or a turn limit while a death stops"
					+ " cold makes dying the cheap ending." );
			return;
		}
		System.out.println( "  a " + env.endReason() + " ending is terminal to the learner, as a death is" );
	}

	private static Outcome dieAfter( EnvConfig config, int turns ){
		Outcome o = play( config, turns + 40, "FORFEITCHECK", turn -> {
			if (turn.env.mode() != EnvMode.WORLD){
				turn.env.step( Action.CANCEL, 0 );
				return;
			}
			turn.env.step( RewardFixtures.legalMove( turn.env ), 0 );
			if (turn.step >= turns && Dungeon.hero != null && Dungeon.hero.isAlive()){
				Dungeon.hero.damage( Dungeon.hero.HP + 1, Dungeon.hero );
			}
		} );
		return o;
	}
}
