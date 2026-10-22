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

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fmt;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.legalMove;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.play;

public final class RewardFreeStepCheck {

	private RewardFreeStepCheck(){}

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
