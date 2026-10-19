package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.StallCause;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.Outcome;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeRecord;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;

import java.util.Random;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fmt;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.freeSteps;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.legalMove;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.play;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.playInWorld;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.playWaits;

public final class RewardTerminalCheck {

	private RewardTerminalCheck(){}

	public static void checkStallingIsNotCheaperThanDying( EnvConfig config ){
		Outcome stall = playWaits( config, config.stallLimit + 8 );

		if (stall.endReason != RewardModel.TerminateReason.STALLED){
			fail( "waiting " + ( config.stallLimit + 8 ) + " times did not stall; the episode ended "
					+ stall.endReason + ". The test can no longer tell whether the degenerate strategy"
					+ " is cheap, only whether the guard exists." );
			return;
		}

		double death = -config.deathPenalty;

		if (stall.score > death ){
			fail( "stalling scores " + fmt( stall.score ) + " against " + fmt( death )
					+ " for dying, so ending an episode on purpose is cheaper than losing. This is the"
					+ " strategy the agent converged on: WAIT costs a turn, moves nothing and changes no"
					+ " HP, which is what the stall guard tests." );
			return;
		}

		System.out.println( "  stalling scores " + fmt( stall.score ) + " over " + stall.turns
				+ " turns; dying costs " + fmt( death ) );
	}


	public static void checkNoOpActionsAreNotRefusals( EnvConfig config ){
		for (Action action : new Action[]{ Action.WAIT, Action.REST, Action.SEARCH }){
			Outcome o = playInWorld( config, action, 30 );

			if (o.worldTurns <= 0 ){
				fail( action + " never produced a WORLD turn (start mode " + o.startMode
						+ ", ended " + o.endReason + "), so the refusal count was measured over nothing"
						+ " and the case passed vacuously." );
				return;
			}

			if (o.refusals != 0 ){
				fail( action + " was reported as INVALID_ACTION " + o.refusals + " time(s) over "
						+ o.worldTurns + " WORLD turns. It is a legal action the environment performs, so"
						+ " the term cannot mean what it says." );
				return;
			}
		}

		System.out.println( "  WAIT, REST and SEARCH are accepted and never noted INVALID_ACTION" );
	}


	public static void checkAnItemActionInWorldIsNotARefusal( EnvConfig config ){
		for (Action action : new Action[]{ Action.USE, Action.DROP }){
			Outcome o = playInWorld( config, action, 20 );

			if (o.worldTurns <= 0 ){
				fail( action + " in WORLD never produced a WORLD turn (start mode " + o.startMode
						+ ", ended " + o.endReason + "), so the refusal count was measured over nothing"
						+ " and the case passed vacuously." );
				return;
			}

			if (o.refusals != 0 ){
				fail( action + " in WORLD was reported as INVALID_ACTION " + o.refusals + " time(s) over "
						+ o.worldTurns + " turns. It opens the item choice exactly as OPEN_INVENTORY opens"
						+ " the inventory, and that one is accepted." );
				return;
			}
		}

		System.out.println( "  USE and DROP in WORLD open the item choice and are never noted"
				+ " INVALID_ACTION" );
	}


	public static void checkStallIsNotANaturalEnding( EnvConfig config ){
		Outcome stall = playWaits( config, config.stallLimit + 8 );

		if (stall.endReason == RewardModel.TerminateReason.STALLED && stall.endedNaturally ){
			fail( "a stalled episode reports endedNaturally, so GAE treats it as a final state and"
					+ " refuses to bootstrap through it." );
			return;
		}

		for (RewardModel.TerminateReason reason : RewardModel.TerminateReason.values()){
			boolean shouldBeNatural = reason == RewardModel.TerminateReason.DEATH
					|| reason == RewardModel.TerminateReason.VICTORY;

			boolean isNatural = SPDEnv.isNaturalEnding( reason );
			if (isNatural != shouldBeNatural ){
				fail( reason + " is classified as " + ( isNatural ? "a natural ending" : "a truncation" )
						+ ", which is wrong. Only DEATH and VICTORY are outcomes where the game ended"
						+ " the episode; a stall or a turn limit is the harness cutting it short, and GAE"
						+ " must bootstrap through those." );
				return;
			}
		}

		System.out.println( "  STALLED bootstraps (truncated); only DEATH and VICTORY are natural endings" );
	}


	public static void checkStallCostsMoreThanDying( EnvConfig config ){
		Outcome stall = playWaits( config, config.stallLimit + 8 );
		if (stall.endReason != RewardModel.TerminateReason.STALLED) return;

		double terminal = stall.termTotal( RewardTerm.STALLED );
		double expected = -config.stallPenalty;

		if (Math.abs( terminal - expected ) > 1e-4 ){
			fail( "STALLED carries a terminal reward of " + terminal + ", expected " + expected
					+ " - more than dying's " + (-config.deathPenalty) + ". It was -5.0, twenty times"
					+ " cheaper than dying and the agent converged on it; then zero, which made the"
					+ " stall guard firing on every descent free; then -deathPenalty, which made giving"
					+ " up and losing the same decision." );
			return;
		}

		if (!( -config.stallPenalty < -config.deathPenalty )){
			fail( "stalling costs " + (-config.stallPenalty) + " and dying costs "
					+ (-config.deathPenalty) + ". Giving up has to be the worse outcome, or 'stop' stays"
					+ " on the menu: an agent that can walk away from a run it is losing will." );
			return;
		}

		double turnCost = stall.termTotal( RewardTerm.TURN_COST );
		double expectedTurnCost = -config.turnCost * (stall.turns - freeSteps( stall ) );

		if (Math.abs( turnCost - expectedTurnCost ) > 1e-5 ){
			fail( "a stalled episode charged " + turnCost + " in turn cost where " + expectedTurnCost
					+ " was expected: " + stall.turns + " turns, " + freeSteps( stall ) + " of them free"
					+ " and therefore priced separately. Stall and idling are separate prices and both"
					+ " are charged." );
			return;
		}

		System.out.println( "  STALLED costs " + fmt( terminal ) + ", worse than dying's "
				+ fmt( -config.deathPenalty ) + ", plus " + fmt( turnCost ) + " in turn cost for "
				+ stall.turns + " turns" );
	}


	public static void checkTerminalsAreOrdered( EnvConfig config ){
		double stall = -config.stallPenalty;
		double death = -config.deathPenalty;
		double limit = -config.turnLimitPenalty;
		double cleared = config.depthReward;

		if (!( stall < death && death < limit && limit < cleared )){
			fail( "terminal outcomes are not ordered as stall (" + stall + ") < death (" + death
					+ ") < turn limit (" + limit + ") < a cleared floor (" + cleared + "). The agent"
					+ " will take the cheapest one, and every run so far has taken one nobody intended." );
			return;
		}

		System.out.println( "  terminals ordered: stall " + fmt( stall ) + " < death " + fmt( death )
				+ " < turn limit " + fmt( limit ) + " < a cleared floor " + fmt( cleared ) );
	}


	public static void checkDepthBeatsAPerFloorTurnLimit( EnvConfig config ){
		double perFloor = config.turnCost * config.turnLimitPerFloor;

		double descend = config.depthReward - perFloor;
		double idleOut = -config.turnLimitPenalty - perFloor;

		if (!( descend > idleOut )){
			fail( "descending within the floor budget nets " + fmt( descend ) + " and idling it out nets "
					+ fmt( idleOut ) + ", so the agent is better off never reaching the stairs."
					+ " TURN_LIMIT was turn_cost*100, a fifth of a point against a +10 descent." );
			return;
		}

		System.out.println( "  descending nets " + fmt( descend ) + " against " + fmt( idleOut )
				+ " for idling out the floor" );
	}


	public static void checkExplorationCannotOutbuyADescent( EnvConfig config ){
		final int TILES = 120;

		double exploration = config.exploreReward * TILES;
		double descend = config.depthReward;

		if (exploration >= descend ){
			fail( "mapping " + TILES + " tiles pays " + fmt( exploration ) + " in exploration against a "
					+ fmt( descend ) + " descent, so a hero that explores the floor and never takes the"
					+ " stairs is paid more for less. This is the default behaviour every run has"
					+ " shown: bestDepth 1 in every generation, score climbing on exploration." );
			return;
		}

		System.out.println( "  mapping " + TILES + " tiles pays " + fmt( exploration )
				+ " against a " + fmt( descend ) + " descent" );
	}


	public static void checkTheHeroCanDie( EnvConfig config ){
		Outcome o = play( config, 200, "DEATHCHECK", turn -> {
			if (turn.env.mode() != EnvMode.WORLD ){
				turn.env.step( Action.CANCEL, 0 );
				return;
			}

			turn.env.step( Action.WAIT, 0 );

			if (Dungeon.hero != null && Dungeon.hero.isAlive()){
				Dungeon.hero.damage( Dungeon.hero.HP + 1, Dungeon.hero );
			}
		} );

		if (o.endReason != RewardModel.TerminateReason.DEATH ){
			fail( "the hero was killed outright but the episode ended " + o.endReason + " after "
					+ o.turns + " turns, so death cannot be recorded. HeadlessSprite.die used to invoke"
					+ " the death callback to fake an animation, which re-entered Hero.die -> Char.die ->"
					+ " sprite.die forever and blew the stack. DEATH is the one ending GAE must treat as"
					+ " terminal, and it was unreachable." );
			return;
		}

		if (!o.endedNaturally ){
			fail( "the hero died but the episode reports endedNaturally=false, so GAE would bootstrap"
					+ " through a terminal state and treat a real death as a truncation." );
			return;
		}

		if (o.notes( RewardTerm.DEATH ) == 0 ){
			fail( "the hero died but no DEATH was recorded, so the terminal reward never lands and the"
					+ " agent is never told that dying is expensive." );
			return;
		}

		Transition last = collectADeath( config );
		if (last == null){
			fail( "the collector's episode did not end in DEATH, so its terminal flag was never measured" );
			return;
		}
		if (!last.terminal ){
			fail( "the collector recorded the step the hero died on as non-terminal, so GAE bootstraps"
					+ " through death on every episode that ends in one." );
			return;
		}

		System.out.println( "  the hero can die: DEATH recorded, natural ending, terminal transition, scored "
				+ fmt( o.score ) );
	}


	private static Transition collectADeath( EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		EpisodeCollector collector = new EpisodeCollector( config, new Network( config, new Random( 7L ) ),
				new Random( 8L ), new Random( 9L ), 0.99f, 0.95f, 0f );
		collector.listener( new EpisodeCollector.Listener() {
			@Override public void onEpisodeStart( String seedText, String heroClass ){}

			@Override public void onDecision( EnvMode mode, Action action, int secondary ){
				if (Dungeon.hero != null && Dungeon.hero.isAlive()){
					Dungeon.hero.damage( Dungeon.hero.HP + 1, Dungeon.hero );
				}
			}
		} );
		EpisodeRecord record = collector.run( env, "DEATHCHECK", HeroClass.WARRIOR );
		if (env.endReason() != RewardModel.TerminateReason.DEATH) return null;

		Transition last = null;
		for (Transition t : record.sampledTransitions()){
			if (last == null || t.stepIndex > last.stepIndex) last = t;
		}
		return last != null && last.stepIndex == record.stepCount() - 1 ? last : null;
	}


	public static void checkStallPathRecordsOnce( EnvConfig config ){
		Outcome stall = playWaits( config, config.stallLimit + 8 );
		if (stall.endReason != RewardModel.TerminateReason.STALLED) return;

		int records = stall.notes( RewardTerm.STALLED );
		if (records != 1 ){
			fail( "a stalled episode recorded STALLED " + records + " times, expected 1. The guard in"
					+ " settle() used to call terminate twice, charging the terminal reward twice over." );
			return;
		}

		System.out.println( "  the reachable stall path records STALLED exactly once" );
	}


	public static void checkWaitingIsNotAFreeStep( EnvConfig config ){
		Outcome o = play( config, config.freeStepBudget * 3, "WAITCOST", turn -> {
			SPDEnv env = turn.env;
			if (!env.running()) return;
			if (env.mode() != EnvMode.WORLD){
				env.step( Action.CANCEL, 0 );
				return;
			}
			env.step( Action.WAIT, 0 );
		});

		if (o.ledger == null){
			fail( "the run produced no ledger, so nothing was measured." );
			return;
		}

		if (o.stallCause == StallCause.FREE_STEP){
			fail( "waiting " + o.turns + " times ended the episode on the FREE_STEP guard, so WAIT is still"
					+ " counted as a step that advances no engine time - which is exactly what"
					+ " env.free_step_budget exists to bound." );
			return;
		}

		int charged = o.notes( RewardTerm.NO_PROGRESS );
		if (charged != 0){
			fail( "waiting " + o.turns + " times charged NO_PROGRESS " + charged + " time(s). A turn"
					+ " the agent deliberately gives up is a turn, and is charged as one." );
		}
	}


	public static void checkRestIsNotAOneWayDoor( EnvConfig config ){
		HeroProbe rest = HeroProbe.restThenWait( config );

		if (!rest.measured ){
			fail( "the episode never reached a WORLD turn, so REST and WAIT were never applied and this"
					+ " case measured nothing." );
			return;
		}

		if (rest.rested ){
			fail( "REST left the hero resting, and Hero.act()'s `curAction == null && resting` branch"
					+ " never calls ready(), so control is never handed back: the drain runs to its stall"
					+ " guard instead. REST pays Actor.TICK itself and hands control back." );
			return;
		}

		if (!rest.released ){
			fail( "the action following REST was never applied, so this case measured nothing. REST now"
					+ " spends its own turn and returns to WORLD, which is the property being asserted."
					+ " Measured=false means the probe never reached a second WORLD turn." );
			return;
		}

		System.out.println( "  REST is not a one-way door: it pays its turn and leaves the hero ready" );
	}


	public static void checkFreeStepLoopIsBounded( EnvConfig config ){
		Outcome loop = playFreeStepLoop( config, config.freeStepBudget + 200 );

		if (loop.endReason != RewardModel.TerminateReason.STALLED ){
			fail( "an episode spending its steps on OPEN_INVENTORY and CANCEL, with a move between pairs,"
					+ " ended " + loop.endReason + " after " + loop.turns + " turns rather than stalling."
					+ " " + loop.notes( RewardTerm.NO_PROGRESS ) + " of those steps advanced no engine"
					+ " time, which is " + loop.termTotal( RewardTerm.NO_PROGRESS )
					+ " of the run's cost. The position guard cannot see this shape - one move resets it -"
					+ " so nothing bounded it." );
			return;
		}

		int free = loop.notes( RewardTerm.NO_PROGRESS );
		if (free > config.freeStepBudget || free < config.freeStepBudget - 1 ){
			fail( "the episode stalled after " + free + " free steps against a budget of "
					+ config.freeStepBudget + ". The bound is what was supposed to end it." );
			return;
		}

		double terminal = loop.termTotal( RewardTerm.STALLED );
		if (Math.abs( terminal + config.stallPenalty ) > 1e-4 ){
			fail( "the loop ended on a STALLED carrying " + terminal + ", expected "
					+ (-config.stallPenalty) + ". A bound that charges less than dying teaches the loop"
					+ " again." );
			return;
		}

		if (!( loop.score < -config.deathPenalty )){
			fail( "looping scores " + fmt( loop.score ) + " and dying costs " + (-config.deathPenalty)
					+ ", so the cheapest way to end the episode is to look at nothing repeatedly."
					+ " Stalling has to cost more than dying." );
			return;
		}

		EnvConfig roomy = new EnvConfig();
		roomy.freeStepBudget = 100000;
		Outcome control = playFreeStepLoop( roomy, config.freeStepBudget * 4 + 60 );

		int controlFree = control.notes( RewardTerm.NO_PROGRESS );
		if (controlFree <= config.freeStepBudget + 20 ){
			fail( "the control run reached " + controlFree + " free steps against a budget of "
					+ config.freeStepBudget + " and ended " + control.endReason + " after "
					+ control.turns + " turns, so it never got far enough past the budget for this case to"
					+ " be measuring the budget rather than something else." );
			return;
		}

		System.out.println( "  a free-action loop is bounded: " + free + " free steps ended it as STALLED"
				+ " at " + fmt( loop.score ) + ", worse than dying's " + fmt( -config.deathPenalty )
				+ "; the same loop with the budget out of reach reached " + controlFree
				+ " free steps before ending " + control.endReason );
	}


	private static Outcome playFreeStepLoop( EnvConfig config, int maxTurns ){
		return play( config, maxTurns, "FREESTEPLOOP", turn -> {
			SPDEnv env = turn.env;
			if (env.mode() == EnvMode.INVENTORY){
				env.step( Action.CANCEL, 0 );
				return;
			}
			if (env.mode() == EnvMode.WORLD){
				env.step( turn.step % 3 == 2 ? legalMove( env ) : Action.OPEN_INVENTORY, 0 );
				return;
			}
			env.step( Action.CANCEL, 0 );
		} );
	}


	private static class HeroProbe {

		boolean measured;
		boolean rested;
		boolean released;

		static HeroProbe restThenWait( EnvConfig config ){
			final boolean[] seen = { false, false, false, false };

			play( config, config.stallLimit + 40, "RESTPROBE", turn -> {
				if (turn.env.mode() != EnvMode.WORLD ){
					turn.env.step( Action.CANCEL, 0 );
					return;
				}
				if (!seen[ 2 ] && !seen[ 0 ] ){
					turn.env.step( Action.REST, 0 );
					seen[ 0 ] = Dungeon.hero.resting;
					seen[ 2 ] = true;
					return;
				}
				if (seen[ 2 ] && !seen[ 1 ] && !seen[ 3] ){
					turn.env.step( Action.WAIT, 0 );
					seen[ 1 ] = !Dungeon.hero.resting;
					seen[ 3 ] = true;
				} else {
					turn.env.step( Action.CANCEL, 0 );
				}
			} );

			HeroProbe p = new HeroProbe();
			p.rested = seen[ 0 ];
			p.released = seen[ 1 ];
			p.measured = seen[ 3 ];
			return p;
		}
	}
}