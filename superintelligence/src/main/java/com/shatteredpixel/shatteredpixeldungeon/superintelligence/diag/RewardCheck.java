package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardLedger;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Fails if there is a cheaper way to end an episode than dying.
 *
 * <pre>gradle :superintelligence:rewardcheck</pre>
 *
 * <p>Written because the agent found one. A 20-generation training run converged {@code meanScore} on
 * exactly -5.0 with {@code meanTurns} collapsing from 62 to 8, and -5.0 was {@code STALLED}'s terminal
 * reward. The path was {@code WAIT} x {@code stallLimit}: waiting costs a turn, leaves the hero where
 * he was and changes no HP, which is exactly what the stall guard tests. Ending an episode that way
 * cost -5.24 against {@code deathPenalty} of -100 — twenty times cheaper than losing — and the agent
 * converged on it in twenty generations without ever leaving floor 1.
 *
 * <p><b>Corrected later the same day, by this same file.</b> The {@code WAIT} diagnosis above was a
 * plausible story that measurement refuted: instrumenting the two stall guards showed {@code STALLED}
 * arriving 36 times from {@code LevelPipeline} and <b>zero</b> times from the idle guard. The real cause
 * was that {@code REST} was a one-way door — {@link #checkRestIsNotAOneWayDoor} documents it. The -5.0
 * was still wrong and the cases below are still the right assertions, but the agent was not choosing to
 * wait; it was being trapped. See {@code PLAN-reward-signals.md} §7.
 *
 * <p><b>Why this is a gate and not a note.</b> Every loss metric looked healthy through that run:
 * {@code valueLoss} flat, {@code clipFraction} settling 0.73 → 0.05, entropy steady. The update was
 * working and the objective was wrong, and the only evidence was a mean score converging on a
 * constant. A reward function whose cheapest strategy is "stop" will find it again, and the constant
 * will not look wrong to a loss curve.
 *
 * <p><b>Every case is a property, not a constant.</b> {@code deathPenalty} is not compared against a
 * literal -5.0; the degenerate episode is replayed through the real environment and its score compared
 * against what it actually cost. Change any of the numbers in any order and the ordering this asserts
 * still holds.
 */
public class RewardCheck {

	private static final int CHECKS = 8;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		EnvConfig config = new EnvConfig();

		checkStallingIsNotCheaperThanDying( config );
		checkNoOpActionsAreNotRefusals( config );
		checkStallIsNotANaturalEnding( config );
		checkStallCarriesNoTerminalReward( config );
		checkDeathRemainsMostExpensive( config );
		checkStallPathRecordsOnce( config );
		checkRestIsNotAOneWayDoor( config );
		checkTheHeroCanDie( config );

		if (failures.isEmpty()){
			System.out.println( "[OK]     reward signals: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  reward signals: " + failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	/**
	 * The degenerate strategy, replayed: {@code WAIT} until the stall guard fires.
	 *
	 * <p>This is the actual bug, expressed as a test. It drives the real environment with the real
	 * stall limit and the real turn cost, so it catches the degenerate route whether it is reached by
	 * {@code WAIT} misreporting, by the terminal reward being wrong, by the guard being reachable
	 * without turns being charged, or by any future change that puts them back in that order.
	 */
	private static void checkStallingIsNotCheaperThanDying( EnvConfig config ){
		Outcome stall = playWaits( config, config.stallLimit + 8 );

		if (stall.endReason != RewardModel.TerminateReason.STALLED){
			fail( "waiting " + ( config.stallLimit + 8 ) + " times did not stall; the episode ended "
					+ stall.endReason + ". The test can no longer tell whether the degenerate strategy"
					+ " is cheap, only whether the guard exists." );
			return;
		}

		double death = -config.deathPenalty;

		if (stall.score < death ){
			fail( "stalling scores " + fmt( stall.score ) + " against " + fmt( death )
					+ " for dying, so ending an episode on purpose is cheaper than losing. This is the"
					+ " strategy the agent converged on: WAIT costs a turn, moves nothing and changes no"
					+ " HP, which is what the stall guard tests." );
			return;
		}

		System.out.println( "  stalling scores " + fmt( stall.score ) + " over " + stall.turns
				+ " turns; dying costs " + fmt( death ) );
	}

	/**
	 * {@code WAIT}, {@code REST} and {@code SEARCH} must report as accepted.
	 *
	 * <p>They are legal actions the environment performs. They used to return {@code false} from
	 * {@code ActionMapper.apply}, which {@code SPDEnv.step} read as a refusal — so
	 * {@code INVALID_ACTION} could not distinguish "the agent tried something impossible" from "the
	 * agent waited", which is most of what a weak policy does. And it was invisible: the term was
	 * recorded with a zero amount, so nothing about it showed up in any total.
	 *
	 * <p>Counted from {@link RewardLedger#notes}, not from a score. Asserting on the total compared an
	 * always-zero quantity, and this case passed with {@code WAIT} deliberately mutated back into
	 * reporting refusals — the first version of this check could not fail.
	 */
	private static void checkNoOpActionsAreNotRefusals( EnvConfig config ){
		Action[] noOps = { Action.WAIT, Action.REST, Action.SEARCH };

		for (Action action : noOps){
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

	/**
	 * A stall must not read as the game having decided the outcome.
	 *
	 * <p>{@code endedNaturally()} means death or victory, and only those two: they are the outcomes
	 * where the game ended the episode rather than the harness cutting it short. A stall is marked
	 * {@code truncated} instead, which is what makes GAE bootstrap it.
	 *
	 * <p>Asserted over the whole reason set rather than by producing a death, because whether a random
	 * walk dies on floor 1 is a property of the game rather than of the reward function — a check that
	 * failed when the hero became harder to kill would be backwards. The stall half is what proves the
	 * classification is reached at all: it drives a real episode to a real stall and asks.
	 */
	private static void checkStallIsNotANaturalEnding( EnvConfig config ){
		Outcome stall = playWaits( config, config.stallLimit + 8 );

		if (stall.endReason == RewardModel.TerminateReason.STALLED && stall.endedNaturally ){
			fail( "a stalled episode reports endedNaturally, so GAE treats it as a final state and"
					+ " refuses to bootstrap through it." );
			return;
		}

		for (RewardModel.TerminateReason reason : RewardModel.TerminateReason.values()){
			boolean shouldBeNatural = reason == RewardModel.TerminateReason.DEATH
					|| reason == RewardModel.TerminateReason.VICTORY;

			//asked of the real predicate, not restated here
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

	/**
	 * {@code STALLED} must carry no terminal reward, and turn cost must still be charged.
	 *
	 * <p>The pair matters. Zero on its own would mean "free", and an agent that finds a free way to end
	 * an episode will take it. The intended reading is "priced by turn cost alone", so a zero stall
	 * reward with no turn cost is as broken as the -5.0 it replaced.
	 */
	private static void checkStallCarriesNoTerminalReward( EnvConfig config ){
		Outcome stall = playWaits( config, config.stallLimit + 8 );
		if (stall.endReason != RewardModel.TerminateReason.STALLED) return;  // reported already

		double terminal = stall.termTotal( RewardTerm.STALLED );
		if (terminal != 0 ){
			fail( "STALLED carries a terminal reward of " + terminal + ". It was -5.0, twenty times"
					+ " cheaper than dying, and the agent converged on it." );
			return;
		}

		//Both stall guards record exactly once; see checkBothStallPathsRecordOnce, which drives both and
		//owns the assertion. Asserted on the record count rather than the score because with STALLED at
		//zero a duplicate terminate is invisible in the total.
		double turnCost = stall.termTotal( RewardTerm.TURN_COST );
		double expected = -config.turnCost * stall.turns;

		//tolerance in ulps rather than ==. Both sides accumulate in float32, so -0.242 computed as 121
		//single-turn additions and as 121 * 0.002f differ in the last bits. An exact comparison here
		//would assert about float rounding, not about the reward.
		if (Math.abs( turnCost - expected ) > 1e-5 ){
			fail( "a stalled episode charged " + turnCost + " in turn cost where " + stall.turns
					+ " turns x " + config.turnCost + " = " + expected + " was expected. A zero stall"
					+ " reward means idling is priced by turn cost, not free." );
			return;
		}

		System.out.println( "  STALLED is free to end the episode and still costs "
				+ fmt( turnCost ) + " in turn cost for " + stall.turns + " turns" );
	}

	/**
	 * The reachable stall path must record {@code STALLED} exactly once.
	 *
	 * <p>Asserted on the record count rather than the score, because with {@code STALLED} at zero a
	 * duplicate {@code terminate} is invisible in the total — the fix removed the very evidence that
	 * would have caught its own regression. That is why {@link RewardLedger#notes} exists.
	 *
	 * <p><b>Scope, honestly: this covers one of the two guards.</b> {@code SPDEnv} also stalls from
	 * {@code settle}'s {@code actorStepLimit} guard, and lowering that limit does not reach it — the
	 * scheduler returns {@code READY} within the budget on floor 1, so {@code settle} never iterates
	 * far enough to trip it. That path is protected by construction instead: {@code terminate} is now
	 * idempotent, so a second call costs nothing and cannot overwrite the reason. A check that claimed
	 * otherwise would be asserting something it cannot observe.
	 */
	private static void checkStallPathRecordsOnce( EnvConfig config ){
		Outcome stall = playWaits( config, config.stallLimit + 8 );
		if (stall.endReason != RewardModel.TerminateReason.STALLED ) return;  // reported already

		int records = stall.ledger == null ? 0 : stall.ledger.notes( RewardTerm.STALLED );
		if (records != 1 ){
			fail( "a stalled episode recorded STALLED " + records + " times, expected 1. The guard in"
					+ " settle() used to call terminate twice, charging the terminal reward twice"
					+ " over." );
			return;
		}

		System.out.println( "  the reachable stall path records STALLED exactly once" );
	}

	/**
	 * Dying must remain the most expensive way to end.
	 *
	 * <p>The ordering that made the whole thing possible: {@code STALLED} at -5.0 sat below
	 * {@code TURN_LIMIT} and far below {@code DEATH} at -100. Asserted as an ordering so it cannot
	 * invert silently as any one of the three is retuned.
	 */
	private static void checkDeathRemainsMostExpensive( EnvConfig config ){
		double death = -config.deathPenalty;
		double turnLimit = -config.turnCost * config.turnLimitTotal;

		if (!( death < turnLimit )){
			fail( "dying costs " + death + " and surviving to the turn cap costs " + turnLimit
					+ ". Dying must be the worse outcome, or the agent will learn to keep going past the"
					+ " point where it should have quit." );
		}
	}

	// --------------------------------------------------------------------------- playing

	/**
	 * The hero must be able to die.
	 *
	 * <p>{@code HeadlessSprite.die} used to call {@code ch.die( ch )} - invoking the death callback
	 * immediately to stand in for an animation that does not exist headless. That callback re-enters
	 * {@code Hero.die} -> {@code Char.die} -> {@code sprite.die} -> the callback, and each bounce passes
	 * a different {@code Char}, so {@code Hero.die}'s repeated-cause guard never matched. Every death
	 * blew the stack with {@code StackOverflowError} instead of recording the {@code DEATH} that GAE
	 * treats as a terminal state - so the one ending that actually matters was unreachable.
	 *
	 * <p><b>Why damage is applied directly.</b> A death has to be produced, and waiting on hunger or on
	 * the mob cluster is a property of the game's balance rather than of this check: the hero might
	 * simply survive, and a gate that starts failing when the game gets easier would be backwards.
	 * Damaging the hero makes the ending deterministic, and everything else - {@code Hero.die},
	 * {@code Char.die}, the sprite, {@code reallyDie}, and {@code SPDEnv}'s own observation of the
	 * corpse - runs for real.
	 *
	 * <p>Also asserts the resulting classification, because surviving the call is not enough: a
	 * {@code DEATH} that reports as truncated would silently change every advantage downstream.
	 */
	private static void checkTheHeroCanDie( EnvConfig config ){
		Outcome o = play( config, 200, "DEATHCHECK", turn -> {
			if (turn.env.mode() != EnvMode.WORLD ){
				turn.env.step( Action.CANCEL, 0 );
				return;
			}

			turn.env.step( Action.WAIT, 0 );

			if (Dungeon.hero != null && Dungeon.hero.isAlive()){
				//lethal in one hit, so the ending cannot depend on how much HP the hero rolled
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

		if (o.ledger == null || o.ledger.notes( RewardTerm.DEATH ) == 0 ){
			fail( "the hero died but no DEATH was recorded, so the terminal reward never lands and the"
					+ " agent is never told that dying is expensive." );
			return;
		}

		System.out.println( "  the hero can die: DEATH recorded, natural ending, scored "
				+ fmt( o.score ) );
	}

	/**
	 * {@code REST} must not end the episode.
	 *
	 * <p><b>This is the actual cause of the 100% stall rate, and it is a harness bug, not a
	 * preference.</b> {@code ActionMapper} set {@code hero.resting = true} without ever setting a
	 * {@code curAction}. {@code Hero.act()} branches on {@code curAction == null} first, so a resting
	 * hero takes the {@code rest} branch — {@code spendConstant} then {@code next()} — and never calls
	 * {@code ready()}. Control is never handed back.
	 *
	 * <p>The hero is then stuck for good, and nothing rescues him: {@code recoverStrandedHero}
	 * deliberately refuses a resting hero, which is right for a player, because a player escapes rest
	 * by choosing another action. Headless input has nobody to do that. After 8 scheduler steps
	 * {@code LevelPipeline} returns {@code STALLED} and the episode is over.
	 *
	 * <p>Measured before the fix: stalls at turns 10, 31, 24, 51, 168 across seeds — 36 of 36 episodes
	 * stalled, from the pipeline path, with the idle guard firing <b>zero</b> times. So the 20-generation
	 * run's "100% stalled" was this, not the {@code WAIT} story the other cases in this file describe.
	 *
	 * <p><b>Why the assertion is on the hero's flag and not on the episode surviving.</b> Survival looked
	 * like the stronger property and is the weaker one. An episode that rests and then <i>moves</i> never
	 * stalls even with this bug present, because a movement action sets a {@code curAction} and
	 * {@code Hero.act()} clears {@code resting} on the way past - so a test written that way passed with
	 * the fix deleted, which was caught by mutation rather than by reading. Only the actions that set no
	 * {@code curAction} ({@code WAIT}, {@code SEARCH}, {@code USE}, {@code DROP}) were ever trapped.
	 */
	private static void checkRestIsNotAOneWayDoor( EnvConfig config ){

		//The driver has to observe the hero *between* the step that rests and the step that releases,
		//because after a release the flag is false either way - which is why this is a probe with its
		//own replay rather than a read of the final state.
		HeroProbe rest = HeroProbe.restThenWait( config );

		if (!rest.measured ){
			fail( "the episode never reached a WORLD turn, so REST and WAIT were never applied and this"
					+ " case measured nothing." );
			return;
		}

		if (!rest.rested ){
			fail( "REST did not leave the hero resting, so the release below would pass vacuously."
					+ " Either the action is not reaching the mapper or rest is engaged somewhere else." );
			return;
		}

		if (!rest.released ){
			fail( "after REST the hero was still resting when the next action arrived. REST sets"
					+ " hero.resting without setting a curAction, so Hero.act() takes the"
					+ " `curAction == null && resting` branch, spends time and calls next() but never"
					+ " ready(). recoverStrandedHero cannot help because it refuses a resting hero - a"
					+ " player escapes by choosing another action, and headless input has nobody to do"
					+ " that. Every episode that rests is over, which is what produced the 100% stall"
					+ " rate. Note that movement escapes on its own; this fails only for the actions that"
					+ " set no curAction." );
			return;
		}

		System.out.println( "  REST is escapable: resting is engaged by REST and released by WAIT" );
	}

	/** Observes {@code hero.resting} across one REST and one following action. */
	private static class HeroProbe {
		/** Both steps actually ran; guards the case against passing by measuring nothing. */
		boolean measured;

		/** REST left the hero resting, so {@link #released} means something. */
		boolean rested;

		/** The following action cleared it. */
		boolean released;

		static HeroProbe restThenWait( EnvConfig config ){
			//rested-after-REST, released-after-WAIT, REST-issued, WAIT-issued
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

	// --------------------------------------------------------------------------- playing

	/** One episode's outcome, and the per-term totals that produced it. */
	private static class Outcome {
		double score;
		int turns;
		RewardModel.TerminateReason endReason = RewardModel.TerminateReason.OTHER;
		boolean endedNaturally;
		String startMode = "?";

		/** Refusals counted while the environment was in WORLD. See playInWorld. */
		int refusals;
		int worldTurns;

		private RewardLedger ledger;

		double termTotal( RewardTerm term ){
			return ledger == null ? 0 : ledger.termTotal( term );
		}
	}

	/** {@code WAIT} every turn, until the environment ends the episode. */
	private static Outcome playWaits( EnvConfig config, int maxTurns ){
		return play( config, maxTurns, "REWARDCHECK", turn -> {
			turn.env.step( Action.WAIT, 0 );
		} );
	}

	/**
	 * One action, only while the environment is in the mode it was designed for.
	 *
	 * <p>{@code step} runs {@code settle}, which can hand the turn back in {@code MENU} or
	 * {@code TARGETING}; a {@code WAIT} then means "cancel the dialog", which {@code applySecondary}
	 * legitimately refuses. Counting refusals across that transition would blame the action for the
	 * environment having moved on — so the count is taken over WORLD turns only, and the number of
	 * those turns is asserted non-zero so the case cannot pass by measuring nothing.
	 */
	private static Outcome playInWorld( EnvConfig config, Action action, int maxTurns ){
		//effectively final, so the driver lambda below can write through it
		Outcome counted = new Outcome();

		//CANCEL first, because a fresh reset can land in TARGETING or MENU and never return to WORLD
		//on its own — settle() keeps re-entering the mode, so a WORLD-only driver would spin until the
		//budget ran out having tested nothing. Cancelling out of those modes is what a real episode does
		//and is the only way to reach a clean WORLD turn to measure the action in.
		Outcome o = play( config, maxTurns, "REWARDCHECK", turn -> {
			if (turn.env.mode() != EnvMode.WORLD ){
				turn.env.step( Action.CANCEL, 0 );
				return;
			}

			turn.env.step( action, 0 );
			counted.worldTurns++;
			//read straight after the step, so it covers this turn only
			counted.refusals += turn.env.ledger().notes( RewardTerm.INVALID_ACTION );
		} );

		o.worldTurns = counted.worldTurns;
		o.refusals = counted.refusals;
		return o;
	}

	private interface Driver {
		void act( Turn turn );
	}

	private static class Turn {
		final SPDEnv env;

		/** Zero-based step index, so a driver can vary its action by turn. */
		int step;

		Turn( SPDEnv env ){ this.env = env; }
	}

	private static Outcome play( EnvConfig config, int maxTurns, String seed, Driver driver ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-rewardcheck" ));
		HeadlessServices.disableSaving( true );

		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( seed, HeroClass.WARRIOR );

		Turn turn = new Turn( env );
		Outcome outcome = new Outcome();
		outcome.startMode = env.mode().name();

		int n = 0;
		while (env.running() && n < maxTurns){
			turn.step = n;
			driver.act( turn );
			n++;
		}

		outcome.score = env.ledger().total();
		outcome.turns = env.turnsTotal();
		outcome.endReason = env.endReason();
		outcome.endedNaturally = env.endedNaturally();
		outcome.ledger = env.ledger();
		return outcome;
	}

	private static String fmt( double v ){
		return String.format( java.util.Locale.ROOT, "%.2f", v );
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
