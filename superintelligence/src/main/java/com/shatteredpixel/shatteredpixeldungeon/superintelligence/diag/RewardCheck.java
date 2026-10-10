package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
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

	private static final int CHECKS = 9;

	/** How many heaps to try before calling a floor that offers none a failure. */
	private static final int MAX_HEAP_TRIES = 12;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		EnvConfig config = new EnvConfig();

		checkStallingIsNotCheaperThanDying( config );
		checkNoOpActionsAreNotRefusals( config );
		checkStallIsNotANaturalEnding( config );
		checkStallCostsAsMuchAsDying( config );
		checkDeathRemainsMostExpensive( config );
		checkStallPathRecordsOnce( config );
		checkRestIsNotAOneWayDoor( config );
		checkTheHeroCanDie( config );
		checkPickingUpAndDroppingIsNotProfitable( config );

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
	 *
	 * <p>The comparison was inverted, and the inversion is why the original fault survived the gate that
	 * was written to catch it. It asserted {@code stall.score < death} and called that "cheaper", but a
	 * score is negative, so {@code -0.24 < -100} is false and a run that converged on a stall at -0.24
	 * passed. The condition that expresses the stated property is {@code stall.score > death}: it fires
	 * on -5.24 and on -0.24, and passes on -100.24.
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
	 * {@code STALLED} must cost exactly what dying costs, and turn cost must still be charged.
	 *
	 * <p>The pair matters. The reward used to be zero, on the argument that a stall is a harness
	 * timeout rather than an outcome and so should be "priced by turn cost alone" - and a zero stall
	 * reward with no turn cost is just as broken, since it means idling is free.
	 *
	 * <p><b>It is {@code -deathPenalty} now, and that reverses {@code PLAN-reward-signals.md} §3.2.</b>
	 * The change is requested in {@code issues.md} 9 - giving up should cost what losing costs - and
	 * the reason the earlier value was defensible has since stopped being true. §3.2 was written when
	 * the only observed stalls were ones the agent walked into; the stall guard was then found to fire
	 * on <i>every descent</i>, because the environment never saw the game ask for a level transition
	 * ({@code HeadlessGame.switchRequested}, gated by {@code transitioncheck}). "The episode ended on
	 * a timeout" was a harness bug wearing an outcome's name, and pricing it at zero made the bug free.
	 *
	 * <p>It cannot re-create the trap §3.2 closed either: stalling used to be cheaper than surviving
	 * to the turn cap, and now it costs a hundred times more than it.
	 *
	 * <p>Asserted as an equality against the config rather than a literal, so "as much as death" is a
	 * property that survives both constants being retuned.
	 */
	private static void checkStallCostsAsMuchAsDying( EnvConfig config ){
		Outcome stall = playWaits( config, config.stallLimit + 8 );
		if (stall.endReason != RewardModel.TerminateReason.STALLED) return;  // reported already

		double terminal = stall.termTotal( RewardTerm.STALLED );
		double expected = -config.deathPenalty;

		if (Math.abs( terminal - expected ) > 1e-4 ){
			fail( "STALLED carries a terminal reward of " + terminal + ", expected " + expected
					+ " - the same as dying. It was -5.0, twenty times cheaper than dying and the agent"
					+ " converged on it; it was then zero, which made the stall guard firing on every"
					+ " descent free." );
			return;
		}

		//Both stall guards record exactly once; see checkStallPathRecordsOnce, which drives one and
		//owns the assertion. Asserted on the record count rather than the score because a duplicate
		//terminate is invisible in the total only while STALLED is at zero, and it is no longer.
		double turnCost = stall.termTotal( RewardTerm.TURN_COST );
		double expectedTurnCost = -config.turnCost * stall.turns;

		//tolerance in ulps rather than ==. Both sides accumulate in float32, so -0.242 computed as 121
		//single-turn additions and as 121 * 0.002f differ in the last bits. An exact comparison here
		//would assert about float rounding, not about the reward.
		if (Math.abs( turnCost - expectedTurnCost ) > 1e-5 ){
			fail( "a stalled episode charged " + turnCost + " in turn cost where " + stall.turns
					+ " turns x " + config.turnCost + " = " + expectedTurnCost + " was expected."
					+ " Stall and idling are separate prices and both are charged." );
			return;
		}

		System.out.println( "  STALLED costs " + fmt( terminal ) + ", the same as dying, plus "
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
	 * <p>{@code REST} sets {@code hero.resting} without setting a {@code curAction}. {@code Hero.act()}
	 * branches on {@code curAction == null} first, so a resting hero takes the {@code rest} branch -
	 * {@code spendConstant} then {@code next()} - and never calls {@code ready()}. Control is never
	 * handed back, and if nothing else clears the flag the episode cannot leave the hero.
	 *
	 * <p>The assertion is on the hero's flag rather than on the episode surviving, because surviving is
	 * the weaker property. An episode that rests and then <i>moves</i> never stalls, because a movement
	 * action sets a {@code curAction} and {@code Hero.act()} clears {@code resting} on the way past - so
	 * a test written that way passes with the release broken. Only actions that set no
	 * {@code curAction} - {@code WAIT}, {@code SEARCH}, {@code USE}, {@code DROP} - leave the flag set.
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
					+ " ready(), so control is never handed back. Movement escapes on its own by"
					+ " setting a curAction; this fails only for the actions that set none." );
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

	/**
	 * Picking an item up and putting it back down must be worth exactly nothing.
	 *
	 * <p>{@code issues.md} 8: a recording shows a hero picking an item up and dropping it, "presumably
	 * cheating score". It was. {@code RewardModel} compared a <i>count</i> of carried items one way -
	 * {@code items > prevItemCount} paid, and nothing was charged for the loss - so the loop
	 * {@code INTERACT, DROP, INTERACT, DROP} raised the score on every pickup while the world did not
	 * change. Three steps per cycle and no limit on cycles.
	 *
	 * <p>research.md:57 asks for exploit mitigation for exactly this shape - "reinforcement learning
	 * agents are notoriously good at finding mechanical exploits" - and a diff-based reward is where
	 * they hide, because the diff is written once per term and a term written one-sided is a term
	 * with a free direction.
	 *
	 * <p><b>One round trip is the whole proof.</b> The assertion is that the drop refunds exactly what
	 * the pickup paid, for the same item, which is a per-item identity rather than a running total. If
	 * that holds for one item it holds for all of them, so a second cycle would only re-derive it; the
	 * mutation that motivated this case is the refund going to zero, and that fails here on the first
	 * cycle.
	 *
	 * <p>The hero is handed a known item rather than left to find one. Which cell carries a heap on a
	 * given seed, and whether the hero is even allowed to take it, are properties of the level: on this
	 * seed {@code INTERACT} walks the hero onto a second {@code Waterskin} - which {@code doPickUp}
	 * refuses - and the pile of {@code CrystalKey}s one cell away is refused headlessly, so a case that
	 * picked its own target reported "no item appeared" for reasons that had nothing to do with the
	 * reward. {@code Level.drop} is the game's own way of putting something on the floor and makes the
	 * item an object this check holds a reference to, which is what lets it assert a per-item refund
	 * rather than a running total.
	 */
	private static void checkPickingUpAndDroppingIsNotProfitable( EnvConfig config ){
		final int[] phase = { 0 };
		final int[] tries = { 0 };
		final Item[] grabbed = { new Potion() };
		final double[] paid = { 0 };
		final double[] refunded = { 0 };
		final int[] slotUsed = { -1 };

		play( config, 200, "EXPLOITCHECK", turn -> {
			SPDEnv env = turn.env;
			EnvMode mode = env.mode();

			switch ( phase[0] ) {
				case 0:
					if (mode != EnvMode.WORLD){ env.step( Action.CANCEL, 0 ); return; }
					if (tries[ 0 ] >= MAX_HEAP_TRIES){
						phase[0] = 9;
						return;
					}
					tries[0]++;
					grabbed[ 0 ] = new Potion();
					if (!takeItemFromTheFloor( env, grabbed[ 0 ] )) return;
					if (!carries( grabbed[ 0 ] )){
						//Hero.handle refuses to collect with enemies in sight, and refuses anything
						//that will not fit. Both are properties of where the hero is standing, not of the
						//reward, so try again rather than reporting a reward failure.
						phase[0] = 0;
						return;
					}
					paid[ 0 ] = env.ledger().termTotal( RewardTerm.ITEM_PICKUP );
					phase[ 0 ] = 1;
					return;

				case 1:
					//WORLD opens the inventory; INVENTORY drops the item the pickup just paid for.
					if (mode == EnvMode.WORLD){ env.step( Action.OPEN_INVENTORY, 0 ); return; }
					if (mode == EnvMode.INVENTORY){
						int slot = slotOf( env, grabbed[ 0 ] );
						slotUsed[ 0 ] = slot;
						env.step( slot < 0 ? Action.CANCEL : Action.DROP, Math.max( slot, 0 ));
						refunded[ 0 ] = env.ledger().termTotal( RewardTerm.ITEM_DROPPED );
					} else {
						env.step( Action.CANCEL, 0 );
					}
					phase[ 0 ] = 2;
					return;

				default:
					//settle onto WORLD and stop, so the run ends on a clean turn
					if (mode != EnvMode.WORLD) env.step( Action.CANCEL, 0 );
					else env.step( Action.WAIT, 0 );
			}
		} );

		if (phase[ 0 ] == 9 ){
			fail( "the hero would not take an item off the floor across " + MAX_HEAP_TRIES
					+ " attempts, so this case measured nothing." );
			return;
		}

		if (grabbed[ 0 ] == null ){
			fail( "the hero walked onto a heap carrying a known item and did not pick it up, so the"
					+ " pickup half of this case was not exercised." );
			return;
		}

		if (paid[ 0 ] <= 0 ){
			fail( "picking up " + grabbed[ 0 ].getClass().getSimpleName() + " paid " + paid[ 0 ]
					+ " of ITEM_PICKUP, so there was nothing for the drop to refund and this case"
					+ " passed without measuring the exploit." );
			return;
		}

		if (slotUsed[ 0 ] < 0 ){
			fail( "the item that was picked up is not in any slot, so it could not be dropped."
					+ " ActionMapper.refreshSlots covers the backpack and nothing else." );
			return;
		}

		if (Math.abs( refunded[ 0 ] + paid[ 0 ] ) > 1e-4 ){
			fail( "picking an item up paid " + paid[ 0 ] + " and putting it back down refunded "
					+ refunded[ 0 ] + ", so the round trip is worth " + ( paid[ 0 ] + refunded[ 0 ] )
					+ ". The pickup term compared a count one way and charged nothing for the loss, which"
					+ " is a free score every three steps - issues.md 8." );
			return;
		}

		System.out.println( "  a pickup and its matching drop cancel: " + fmt( paid[ 0 ] ) + " paid,"
				+ " " + fmt( refunded[ 0 ] ) + " refunded, net " + fmt( paid[ 0 ] + refunded[ 0 ] ) );
	}

	/**
	 * Puts a known item on an adjacent cell through the game's own {@code Level.drop}, and has the
	 * hero walk onto it.
	 *
	 * <p>Retries across the eight neighbours rather than insisting on one, because {@code Hero.handle}
	 * refuses to collect with enemies in sight and refuses anything that will not fit - and both are
	 * properties of where the hero happens to be standing, not of the reward being tested.
	 *
	 * @return true when the hero is carrying {@code item} when this returns.
	 */
	private static boolean takeItemFromTheFloor( SPDEnv env, Item item ){
		if (Dungeon.level == null || Dungeon.hero == null) return false;

		//One plain turn first. The first settled turn of an episode only establishes the reward model's
		//snapshot - reward.step re-takes it and charges turn cost, scoring nothing - so a pickup landing
		//on that turn is real and invisible. Measured: the pickup happened and ITEM_PICKUP stayed at 0.
		env.step( Action.WAIT, 0 );
		if (!env.running()) return false;

		int w = Dungeon.level.width();
		int[] offsets = { -w, w, -1, 1, -w - 1, -w + 1, w - 1, w + 1 };

		for (int off : offsets){
			int cell = Dungeon.hero.pos + off;
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			if (!Dungeon.level.insideMap( cell ) || !Dungeon.level.passable[ cell ]) continue;
			if (Math.abs( cell % w - Dungeon.hero.pos % w ) > 1) continue;
			if (Actor.findChar( cell ) != null) continue;
			if (Dungeon.level.heaps.get( cell ) != null) continue;

			Dungeon.level.drop( item, cell );
			Action walk = directionTo( Dungeon.hero.pos, cell );
			if (walk == null) continue;

			env.step( walk, 0 );
			if (!env.running()) return false;
			if (carries( item )) return true;
		}
		return false;
	}

	/** True when this exact item is in the hero's belongings. */
	private static boolean carries( Item item ){
		for (Item carried : Dungeon.hero.belongings){
			if (carried == item) return true;
		}
		return false;
	}

	/** The directional action whose cell is {@code to} from {@code from}, or null if not adjacent. */
	private static Action directionTo( int from, int to ){
		int w = Dungeon.level.width();
		int dx = Integer.compare( to % w, from % w );
		int dy = Integer.compare( to / w, from / w );
		for (Action a : Action.values()){
			if (a.directional() && Integer.compare( a.dx, 0 ) == dx && Integer.compare( a.dy, 0 ) == dy){
				return a;
			}
		}
		return null;
	}
	/** Which inventory slot currently resolves to this item, or -1. */
	private static int slotOf( SPDEnv env, Item item ){
		if (item == null) return -1;
		List< Item > slots = env.mapper().slots();
		for (int i = 0; i < slots.size(); i++){
			if (slots.get( i ) == item) return i;
		}
		return -1;
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
