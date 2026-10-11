package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
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

	private static final int CHECKS = 17;

	/** How many heaps to try before calling a floor that offers none a failure. */
	private static final int MAX_HEAP_TRIES = 12;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		EnvConfig config = new EnvConfig();

		checkStallingIsNotCheaperThanDying( config );
		checkNoOpActionsAreNotRefusals( config );
		checkAnItemActionInWorldIsARefusal( config );
		checkStallIsNotANaturalEnding( config );
		checkStallCostsMoreThanDying( config );
		checkTerminalsAreOrdered( config );
		checkDepthBeatsAPerFloorTurnLimit( config );
		checkExplorationCannotOutbuyADescent( config );
		checkStallPathRecordsOnce( config );
		checkRestIsNotAOneWayDoor( config );
		checkTheHeroCanDie( config );
		checkPickingUpAndDroppingIsNotProfitable( config );
		checkKillingIsWorthDoing( config );
		checkFreeStepsCostSomething( config );
		checkAMultiTurnActionPaysForEveryTurn( config );
		checkSearchingIsNotCheaperThanActing( config );
		checkSpendingATalentIsWorthALot( config );

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
	 * Choosing an item action in WORLD is a refusal, because nothing was chosen.
	 *
	 * <p>The exact converse of {@link #checkNoOpActionsAreNotRefusals}, and the boundary between the
	 * two matters. {@code WAIT}, {@code REST} and {@code SEARCH} do what they are asked in WORLD and
	 * are accepted. {@code USE} and {@code DROP} in WORLD do <b>nothing</b>: they clear a stale
	 * pending item and the real choice happens on the next step, in SLOT or INVENTORY. Returning
	 * {@code true} claimed the environment had performed an action it had not, and nothing else checks
	 * - the mode moves either way, so the run looks completely normal and the only trace is a recording
	 * whose step says {@code DROP} and whose inventory never changed. Three of those in an 88-step
	 * trainer recording.
	 *
	 * <p>The mask never offers {@code USE} or {@code DROP} in WORLD, so this is only reachable by
	 * replaying a step - which is exactly what a recording is.
	 */
	private static void checkAnItemActionInWorldIsARefusal( EnvConfig config ){
		for (Action action : new Action[]{ Action.USE, Action.DROP }){
			Outcome o = playInWorld( config, action, 20 );

			if (o.worldTurns <= 0 ){
				fail( action + " in WORLD never produced a WORLD turn (start mode " + o.startMode
						+ ", ended " + o.endReason + "), so the refusal count was measured over nothing"
						+ " and the case passed vacuously." );
				return;
			}

			if (o.refusals == 0 ){
				fail( action + " in WORLD was never reported as INVALID_ACTION over " + o.worldTurns
						+ " turns, so it is being reported as an action the environment performed."
						+ " It performed none: the item is chosen on the next step, in SLOT or INVENTORY." );
				return;
			}
		}

		System.out.println( "  USE and DROP in WORLD are refusals - nothing was chosen, and the item"
				+ " comes on the next step" );
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
	 * {@code STALLED} must cost <em>more</em> than dying, and turn cost must still be charged.
	 *
	 * <p>The pair matters. The reward used to be zero, on the argument that a stall is a harness
	 * timeout rather than an outcome and so should be "priced by turn cost alone" - and a zero stall
	 * reward with no turn cost is just as broken, since it means idling is free.
	 *
* <p><b>It is worse than dying now, by its own configurable constant.</b> It was
	 * {@code -deathPenalty}, which balanced the price and lost the distinction: to a learner the two
	 * were one number with two labels, and the lesson that survives is that quitting is survivable.
	 * Dying moved the run forward and closed a floor. A stall is a run that stopped, on a hero who is
	 * alive and has not been asked to do anything difficult. See {@code issues.md} 13 for what
	 * stopping actually looks like — {@code duelist-mid} spending 120 consecutive steps alternating
	 * equip and unequip at zero engine time.
	 *
	 * <p>Asserted as an ordering against the config rather than against a literal, so "worse than
	 * dying" is a property that survives both constants being retuned.
	 */
	private static void checkStallCostsMoreThanDying( EnvConfig config ){
		Outcome stall = playWaits( config, config.stallLimit + 8 );
		if (stall.endReason != RewardModel.TerminateReason.STALLED) return;  // reported already

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

		System.out.println( "  STALLED costs " + fmt( terminal ) + ", worse than dying's "
				+ fmt( -config.deathPenalty ) + ", plus " + fmt( turnCost ) + " in turn cost for "
				+ stall.turns + " turns" );
	}

	/**
	 * The terminal outcomes have to be ordered, or the agent will take the cheapest one.
	 *
	 * <p>As an ordering rather than as three comparisons to zero, because the ordering is the property:
	 * giving up worse than dying, dying worse than running out of budget, running out of budget worse
	 * than a floor that turned over. Any inversion among them is a strategy, and the whole history of
	 * this function is strategies being found by an agent that was correct to find them.
	 */
	private static void checkTerminalsAreOrdered( EnvConfig config ){
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

	/**
	 * Descending has to beat idling out the floor's turn budget.
	 *
	 * <p>This is the comparison {@code TURN_LIMIT} at {@code -turnCost * 100} lost by two orders of
	 * magnitude: a fifth of a point against a +10 descent, on a floor where exploration pays per tile.
	 * A hero could spend 1500 turns mapping a floor, bank the exploration, and be charged almost
	 * nothing for never reaching the stairs — so idling out the clock was a strategy rather than a
	 * failure, and the runs that did it were not misbehaving, they were optimising.
	 */
	private static void checkDepthBeatsAPerFloorTurnLimit( EnvConfig config ){
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

	/**
	 * Exploration must not outbuy a descent.
	 *
	 * <p>A regular floor holds several hundred walkable tiles — {@code TODO.md} 0.8 measured 419 of
	 * 1040 sampled floors exceeding 48 on at least one side, up to 81 — so {@code exploreReward} per
	 * tile is a large number by the time a floor is finished. At the shipped 0.05 it was larger than
	 * the depth reward, which is the reason {@code bestDepth} has been 1 in every generation of every
	 * run while the score climbed: the policy was being paid to wander, and wandering is what it
	 * learned.
	 *
	 * <p>Measured against a deliberately small floor rather than the measured maximum, so the property
	 * holds with room to spare instead of on a number that happens to be true today.
	 */
	private static void checkExplorationCannotOutbuyADescent( EnvConfig config ){
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

/**
	 * Killing must pay, and a surprise attack must pay more.
	 *
	 * <p>{@code KILL} was declared, counted by {@code RewardLedger.countKill()} and never emitted, so
	 * fighting was worth exactly nothing while being hit cost something. Under that price the cheapest
	 * way past a corridor is to walk down it, and the deaths in the corpus have that shape: the hero
	 * does not lose a fight, he runs out of room.
	 *
	 * <p>Driven through the real environment rather than compared against a literal, so it survives the
	 * constants being retuned in any order, and against a real mob from {@code Level.createMob()} rather
	 * than one built to be convenient.
	 *
	 * <p><b>A hunt on the level's own mobs was tried first and could not reach the code.</b> A driver
	 * that walked the hero at whatever was visible fought for 900 turns, took 99 damage and killed
	 * nothing. The level is rooms joined by doors, so an enemy the hero can see but cannot reach is an
	 * ordinary situation and the driver could not tell it from one it could reach. A gate that cannot
	 * reach the emission site it guards is not a gate, so the mob is placed instead - through
	 * {@code createMob}, {@code LevelPipeline.spriteFor} and {@code level.mobs}, which is exactly what
	 * {@code SummoningTrap} does and what {@code GameScene.add} would have done in the game.
	 *
	 * <p>Placing it adjacent also gives the surprise case something to be true of: a mob the level has
	 * just produced has not seen the hero, which is the condition {@code Mob.surprisedBy} tests.
	 */
	private static void checkKillingIsWorthDoing( EnvConfig config ){
		final boolean[] placed = { false };
		final int[] lastMobHp = { -1 };

		Outcome o = play( config, 200, "KILLCHECK", turn -> {
			SPDEnv env = turn.env;
			if (env.mode() != EnvMode.WORLD){
				env.step( Action.CANCEL, 0 );
				return;
			}

			//Top the hero up between steps.
			//
			//Not so the fight is easier to win - it is a real mob, really attacked, and Statistics
			//increments for real - but so the case does not depend on whether this seed's floor 1 is
			//survivable. That is a property of the game's balance, and a gate that failed on it would
			//start failing the moment the hero got better at surviving, which is the direction this
			//whole module is trying to move. Same reasoning, and the same technique, as
			// checkTheHeroCanDie applying lethal damage rather than waiting for the mob cluster.
			if (Dungeon.hero != null && Dungeon.hero.isAlive() && Dungeon.hero.HP < 12){
				Dungeon.hero.HP = Dungeon.hero.HT;
			}

			if (!placed[ 0 ] && placeMobBesideHero( env )) placed[ 0 ] = true;

			Mob target = adjacentEnemy();
			if (target == null){
				env.step( Action.SEARCH, 0 );
				return;
			}
			lastMobHp[ 0 ] = target.HP;
			env.step( directionToTarget( env, target ), 0 );
		} );

		int kills = o.ledger == null ? 0 : o.ledger.notes( RewardTerm.KILL );

		if (!placed[ 0 ]){
			fail( "no free cell beside the hero, so no mob could be placed and neither the KILL nor the"
					+ " SNEAK_ATTACK emission site was reached. A case that cannot reach the code it"
					+ " guards is not a check." );
			return;
		}

		if (kills == 0 ){
			fail( "a level-appropriate mob was placed beside the hero and fought for " + o.turns
					+ " turns with no kill recorded. The hero's melee went through Hero.handle exactly as"
					+ " it does for a player, so either Statistics.enemiesSlain is not moving or"
					+ " Mob.destroy is not reached headlessly - and KILL is diffed from that counter, so"
					+ " a KILL that never fires is a term worth nothing. Mob left at hp " + lastMobHp[ 0 ]
					+ ", damage taken " + o.termTotal( RewardTerm.DAMAGE_TAKEN ) );
			return;
		}

		if (o.termTotal( RewardTerm.KILL ) <= 0 ){
			fail( "a mob was killed and KILL paid " + o.termTotal( RewardTerm.KILL ) + ". The term and"
					+ " RewardLedger.countKill() both existed and neither was ever called, so fighting"
					+ " was worth nothing while being hit cost something." );
			return;
		}

		//Damage has to be priced against dying rather than picked, or the gradient disappears.
		//
		//A floor-1 hero has 20 HP. damagePenalty * 20 is what losing a full health bar costs, and it
		//has to be within an order of magnitude of deathPenalty - otherwise a run that dies every
		//episode returns the same terminal every time and the only term that varies is a twentieth of
		//it. At the old 0.25 that was 5 against 100, and two 12- and 22-generation runs ended 100%
		//dead with no movement on any axis.
		//
		//It also has to leave room for killReward: killing a rat has to be worth the health it costs,
		//and a rat is about 8 HP, so the trade is close enough that a surprise attack is the right way
		//to take it - which is precisely what the ordering below is for.
		final int HP = 20;
		double wholeHealthBar = config.damagePenalty * HP;

		if (!( wholeHealthBar > config.deathPenalty / 10f )){
			fail( "losing a " + HP + " HP health bar costs " + wholeHealthBar + " against a death penalty"
					+ " of " + config.deathPenalty + ". Below about a tenth, an agent that dies every"
					+ " episode gets the same terminal reward every episode and the only signal that"
					+ " varies is a fraction of it - so there is no gradient to learn from at all."
					+ " Measured: two runs at 0.25/HP both sat at 100% deaths." );
			return;
		}

		if (!( config.damagePenalty * 8 < config.killReward * 4 )){
			fail( "killing a mob worth about 8 HP costs " + ( config.damagePenalty * 8 )
					+ " in damage against " + config.killReward + " paid, so the agent would rather walk"
					+ " past than fight - which is the state KILL being dead for in the first place." );
			return;
		}

		//A surprise attack is the one combat outcome that costs the hero nothing, because
		//Mob.surprisedBy skips the defender's defence skill outright. It is therefore paid above the
		//kill, and the ordering is the property: it pays for arriving unseen rather than for trading
		//blows with whatever happens to be in reach.
		if (!( config.sneakAttackReward > config.killReward )){
			fail( "a surprise attack pays " + config.sneakAttackReward + " and a kill pays "
					+ config.killReward + ". The surprise is the one that costs no health -"
					+ " Mob.surprisedBy skips the defender's defence - so paying it less than the kill"
					+ " teaches trading blows instead." );
			return;
		}

		//The emission site itself, not just the price. Statistics.sneakAttacks is what makes the term
		//more than a second name for KILL, and it is read rather than re-derived.
		int sneaks = o.ledger == null ? 0 : o.ledger.notes( RewardTerm.SNEAK_ATTACK );
		if (sneaks == 0 ){
			fail( "the fight killed " + kills + " mob(s) and recorded no SNEAK_ATTACK, on a mob the"
					+ " level had just produced and therefore had not seen the hero. The term is meant"
					+ " to pay for arriving unseen - Mob.surprisedBy skips the defender's defence - and a"
					+ " counter that never moves is not a reward." );
			return;
		}

		if (o.termTotal( RewardTerm.SNEAK_ATTACK ) <= 0 ){
			fail( "SNEAK_ATTACK was recorded " + sneaks + " time(s) and paid "
					+ o.termTotal( RewardTerm.SNEAK_ATTACK ) + "." );
			return;
		}

		System.out.println( "  killing pays " + fmt( config.killReward ) + " and a surprise attack "
				+ fmt( config.sneakAttackReward ) + "; " + kills + " kill(s) and " + sneaks
				+ " surprise(s) recorded" );
	}

	/**
	 * Puts one level-appropriate mob on a free cell beside the hero, by the same three steps
	 * {@code SummoningTrap} uses.
	 *
	 * @return false when every neighbour is occupied, so the caller reports that rather than quietly
	 *         asserting nothing
	 */
	private static boolean placeMobBesideHero( SPDEnv env ){
		if (Dungeon.level == null || Dungeon.hero == null) return false;

		Hero hero = Dungeon.hero;
		int w = Dungeon.level.width();

		int[] offsets = { -w, w, -1, 1, -w - 1, -w + 1, w - 1, w + 1 };

		for (int off : offsets){
			int cell = hero.pos + off;
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			if (!Dungeon.level.insideMap( cell )) continue;
			if (!Dungeon.level.passable[ cell ]) continue;
			//never wrap a row: a naive +/-1 puts a mob on the far side of a thin floor
			if (Math.abs( cell % w - hero.pos % w ) > 1) continue;
			if (Actor.findChar( cell ) != null) continue;
			if (Dungeon.level.heaps.get( cell ) != null) continue;

			Mob mob = Dungeon.level.createMob();
			if (mob == null) continue;
			//a large mob does not fit, and the engine's own summoning code discards one for that
			if (Char.hasProp( mob, Char.Property.LARGE ) && !Dungeon.level.openSpace[ cell ]) continue;
			if (mob.state != mob.PASSIVE) mob.state = mob.WANDERING;
			mob.pos = cell;

			//GameScene.add is what does this in the game and never runs headlessly. All three calls
			//matter: the sprite because Char paths dereference it, level.mobs because Mob.destroy
			//removes itself from there, and Actor.add because Actor.findChar - which is what
			//ActionMapper.moveLegal and Hero.handle both use to decide whether a cell holds a mob -
			//searches Actor.chars and nothing else. Without it the hero walks into the mob and
			//nothing happens, which is what the first version of this fixture did.
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.LevelPipeline.spriteFor( mob );
			Actor.add( mob );
			Dungeon.level.mobs.add( mob );

			Dungeon.observe();
			return true;
		}
		return false;
	}

	/** The hostile mob beside the hero right now, or null. */
	private static Mob adjacentEnemy(){
		Hero hero = Dungeon.hero;
		if (hero == null || Dungeon.level == null) return null;

		int w = Dungeon.level.width();
		int[] offsets = { -w, w, -1, 1, -w - 1, -w + 1, w - 1, w + 1 };

		for (int off : offsets){
			int cell = hero.pos + off;
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			Char ch = Actor.findChar( cell );
			if (ch instanceof Mob && ((Mob) ch).isAlive()
					&& ((Mob) ch).alignment == Char.Alignment.ENEMY){
				return (Mob) ch;
			}
		}
		return null;
	}

	/** A legal action that walks the hero into the given mob. */
	private static Action directionToTarget( SPDEnv env, Mob mob ){
		Hero hero = Dungeon.hero;
		int w = Dungeon.level.width();

		int dx = Integer.signum( mob.pos % w - hero.pos % w );
		int dy = Integer.signum( mob.pos / w - hero.pos / w );

		Action[] order = { Action.MOVE_N, Action.MOVE_E, Action.MOVE_S, Action.MOVE_W,
				Action.MOVE_NE, Action.MOVE_SE, Action.MOVE_SW, Action.MOVE_NW };

		for (Action a : order){
			if (a.dx == dx && a.dy == dy && a.index < env.actionMask().length
					&& env.actionMask()[ a.index ] != 0f ){
				return a;
			}
		}
		//not adjacent, or the cell is blocked: step toward it by whatever the mask offers
		for (Action a : order){
			if (a.index < env.actionMask().length && env.actionMask()[ a.index ] != 0f ) return a;
		}
		return Action.SEARCH;
	}

	/**
	 * A step the game resolved for free must be charged, and must cost materially more than a real one.
	 *
	 * <p>{@code issues.md} 13: {@code duelist-mid} ends {@code STALLED} without looking idle, because
	 * 120 consecutive {@code USE} steps alternate equip and unequip on one {@code Rapier} at
	 * {@code dEng = 0.0}. The stall guard is right that this is a stall, but until it fires those steps
	 * cost a hundredth of a turn each, so a genuinely free infinite action is nearly free.
	 *
	 * <p>Priced here rather than prevented in the guard, because the guard decides when an episode
	 * ends and moving that boundary would invalidate every recording in the corpus.
	 *
	 * <p><b>Two halves, because the first version only had one and it was the weak half.</b> Asserting
	 * that {@code noProgressPenalty > turnCost * 10} is a statement about a number, and mutating the
	 * emission site away entirely leaves it passing - the case was guarding the constant rather than the
	 * behaviour. The second half drives the real loop through the real environment and requires the term
	 * to have been charged on the step that spent nothing. Same failure as {@code checkStallPathRecordsOnce}
	 * on {@code STALLED} at zero: removing the evidence removes the regression test for it.
	 */
	private static void checkFreeStepsCostSomething( EnvConfig config ){
		if (!( config.noProgressPenalty > config.turnCost * 10f )){
			fail( "a step that advances no engine time costs " + config.noProgressPenalty
					+ " against " + config.turnCost + " for a real one, so a free action is cheaper than"
					+ " acting. duelist-mid alternated equip and unequip for 120 steps at that price." );
			return;
		}

		Outcome o = play( config, 60, "FREESTEPCHECK", turn -> {
			SPDEnv env = turn.env;
			if (env.mode() != EnvMode.WORLD){
				env.step( Action.CANCEL, 0 );
				return;
			}
			//equip, unequip, equip, unequip: the free action, on the hero's own weapon
			env.step( Action.USE, 0 );
			if (env.mode() == EnvMode.SLOT) env.step( Action.USE, equippableSlot( env ) );
		} );

		int charged = o.ledger == null ? 0 : o.ledger.notes( RewardTerm.NO_PROGRESS );
		if (charged == 0 ){
			fail( "the driver equipped and unequipped the hero's weapon for " + o.turns + " steps and"
					+ " NO_PROGRESS was never charged. Those steps advance no engine time -"
					+ " WndUseItem.onClick calls item.execute and returns - so a free action is free again."
					+ " This is the same shape as STALLED at zero: removing the evidence removes the"
					+ " regression test for it." );
			return;
		}

		if (o.termTotal( RewardTerm.NO_PROGRESS ) >= 0 ){
			fail( "NO_PROGRESS was recorded " + charged + " time(s) and paid "
					+ o.termTotal( RewardTerm.NO_PROGRESS ) + ", so a free step is not being charged." );
			return;
		}

		System.out.println( "  a free step costs " + config.noProgressPenalty + " against "
				+ config.turnCost + " for a real one; " + charged + " free step(s) charged" );
	}

	/**
	 * An action that costs several engine turns must be charged for all of them.
	 *
	 * <p>{@code TURN_COST} was a flat charge per <em>step</em>, and a step is not a turn. {@code Hero.search}
	 * calls {@code spendAndNext(TIME_TO_SEARCH)} where {@code TIME_TO_SEARCH} is 2, so a search buys two
	 * turns of the engine clock - in which other actors act and the hero can be hit - for the price of
	 * one.
	 *
	 * <p>Measured on a trainer run, reward per engine turn actually consumed:
	 *
	 * <pre>
	 *   SEARCH   189 steps  387 turns  -0.11 per turn
	 *   MOVE_E    63 steps   73 turns  +0.10 per turn
	 * </pre>
	 *
	 * 189 of 940 steps - a fifth of the episode - took <b>half the run's engine time</b> at half price, and
	 * searching reliably finds nothing after the first sweep of a floor, so the agent flooded the one
	 * action that most reliably wastes time.
	 *
	 * <p>Driven rather than asserted as a constant, because the claim is about what the engine's clock
	 * says. The clock is sampled <em>inside</em> the driver: read after the run it is not this run's
	 * spend, because the stall guard and the mode machinery move it between the last step and the read.
	 */
	private static void checkAMultiTurnActionPaysForEveryTurn( EnvConfig config ){
		final float[] spent = new float[ 1 ];
		final int[] steps = new int[ 1 ];

		Outcome o = play( config, 40, "MULTITURNCHECK", turn -> {
			if (turn.env.mode() != EnvMode.WORLD ){
				turn.env.step( Action.CANCEL, 0 );
				return;
			}
			float before = Actor.now();
			turn.env.step( Action.SEARCH, 0 );
			float after = Actor.now();
			if (after > before) spent[ 0 ] += after - before;
			steps[ 0 ]++;
		} );

		double charged = -o.termTotal( RewardTerm.TURN_COST );

		if (spent[ 0 ] <= 0f || steps[ 0 ] == 0 ){
			fail( steps[ 0 ] + " SEARCH step(s) consumed " + fmt( spent[ 0 ] ) + " engine turns in"
					+ " total, so this case measured nothing. Hero.search calls"
					+ " spendAndNext(TIME_TO_SEARCH) and the clock must move." );
			return;
		}

		//the point: an action spending n turns must cost more than n steps' worth, so a multi-turn
		//action cannot come out cheaper per turn than a single-turn one
		double flat = config.turnCost * steps[ 0 ];
		if (charged < flat * 1.5 ){
			fail( "SEARCH consumed " + fmt( spent[ 0 ] ) + " engine turns over " + steps[ 0 ]
					+ " step(s) and was charged " + fmt( charged ) + ", against " + fmt( flat )
					+ " for one turn each. TURN_COST is charged per step, so an action costing more than"
					+ " one turn is cheaper per turn than one costing exactly one - which is why 189"
					+ " searches took half of a 940-step run's engine time." );
			return;
		}

		double perTurn = charged / spent[ 0 ];
		if (Math.abs( perTurn - config.turnCost ) > config.turnCost * 0.5 ){
			fail( "SEARCH cost " + fmt( perTurn ) + " per engine turn against a configured "
					+ config.turnCost + ". The charge is scaled by the engine clock now, so this should be"
					+ " the configured price whatever the action costs." );
			return;
		}

		System.out.println( "  " + steps[ 0 ] + " searches over " + fmt( spent[ 0 ] ) + " engine turns"
				+ " cost " + fmt( charged ) + " - " + fine( perTurn ) + " per turn, against "
				+ fine( config.turnCost ) + " for a single-turn action" );
	}

	/**
	 * Searching a floor that has already been searched must not be worth doing.
	 *
	 * <p>The companion to the case above, and the one that says what the agent should prefer instead.
	 * {@code Hero.search} reveals a tile only when it <em>finds</em> something -
	 * {@code Dungeon.level.discover} runs inside the success branch - so the second sweep of a room is
	 * worth exactly nothing, forever.
	 *
	 * <p>So the property is that a search costs at least what the engine time it consumes, measured from
	 * the same driver as the case above rather than compared against a constant, because the constant
	 * would pass whatever the clock did.
	 */
	private static void checkSearchingIsNotCheaperThanActing( EnvConfig config ){
		final float[] spent = new float[ 1 ];
		final int[] steps = new int[ 1 ];

		Outcome o = play( config, 40, "SEARCHCOSTCHECK", turn -> {
			if (turn.env.mode() != EnvMode.WORLD ){
				turn.env.step( Action.CANCEL, 0 );
				return;
			}
			float before = Actor.now();
			turn.env.step( Action.SEARCH, 0 );
			float after = Actor.now();
			if (after > before) spent[ 0 ] += after - before;
			steps[ 0 ]++;
		} );

		if (steps[ 0 ] == 0 || spent[ 0 ] <= 0f ){
			fail( steps[ 0 ] + " search(es) consumed " + spent[ 0 ] + " engine turns, so this case"
					+ " measured nothing." );
			return;
		}

		double perSearch = -o.termTotal( RewardTerm.TURN_COST ) / steps[ 0 ];
		double singleTurn = config.turnCost;

		if (perSearch < singleTurn ){
			fail( "a search costs " + fmt( perSearch ) + " where it consumes " + fmt( spent[ 0 ] )
					+ " of engine time over " + steps[ 0 ] + " search(es), so it is cheaper than a"
					+ " single-turn action despite costing twice the time. A policy that finds this"
					+ " searches instead of exploring." );
			return;
		}

		//and it must never pay the agent to spend time
		if (o.termTotal( RewardTerm.TURN_COST ) > 0 ){
			fail( "TURN_COST paid " + o.termTotal( RewardTerm.TURN_COST ) + " over " + steps[ 0 ]
					+ " searches; a positive turn cost pays the agent to spend time." );
			return;
		}

		System.out.println( "  a search costs " + fine( perSearch ) + " for the engine time it consumes,"
				+ " against " + fine( singleTurn ) + " for a single-turn action" );
	}

	/** A slot holding an equippable the hero is not already wearing, or 0. */
	private static int equippableSlot( SPDEnv env ){
		Hero hero = Dungeon.hero;
		if (hero == null) return 0;

		List< Item > slots = env.mapper().slots();
		for (int i = 0; i < slots.size(); i++){
			Item item = slots.get( i );
			if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem
					&& !item.isEquipped( hero )){
				return i;
			}
		}
		return 0;
	}

	/**
	 * Spending a talent point must pay, and must pay for more than levelling does.
	 *
	 * <p>The point is {@code TODO.md} 1.10 and the bug that motivates it. {@code Hero.upgradeTalent}
	 * works and is public, and <b>nothing headless calls it</b>: spending a point is UI-only
	 * ({@code TalentButton} -> {@code TalentsPane} -> {@code WndHero}), and {@code WindowBridge.open}
	 * only answers {@code WndOptions}, so the agent has no way to reach it. Meanwhile
	 * {@code talentPointsAvailable} gates at {@code {2, 7, 13, 21, 31}} — so the <b>first point arrives
	 * at level 2</b>, which two rats on floor 1 will do. This is not an endgame gap.
	 *
	 * <p>So the emission site is wired and gated now, so that the action has something to pay when it
	 * lands, and the gate drives it by calling {@code upgradeTalent} directly. That is the same
	 * technique {@link #checkTheHeroCanDie} uses to produce a death without waiting for the mob cluster:
	 * the ending is produced deterministically and everything downstream of it runs for real.
	 *
	 * <p>Asserted against {@link RewardTerm#LEVEL_UP} rather than against a literal, because the
	 * relationship is the claim: a talent point is a choice about which gains to take, while a level is
	 * +1 attack, +1 defence and a little max HP that happen automatically. Paying them the same would
	 * say the choice does not matter.
	 */
	private static void checkSpendingATalentIsWorthALot( EnvConfig config ){
		//**And a talent point must not outbid a descent.** This replaces an assertion that it must, and the
		//replacement is worth stating plainly because weakening a gate to pass is normally the wrong move.
		//
		//The old requirement was `talentReward > 4 * a whole floor's turns`, on the reasoning that
		//spending a point has to beat "every remaining turn of the floor". At `turn_cost = 1.0` and 1500
		//turns a floor that is **6000**, which put a talent point at 4.7 floors against depth's 1.2 -
		//so levelling was the best thing the agent could do and descending was not. The gate was
		//enforcing an inversion of the objective rather than protecting it.
		//
		//A talent point is worth what it is worth *relative to the objective*, and the objective is depth.
		//Its real opportunity cost is the turns spent levelling instead, which is `LEVEL_UP`, and that is
		//asserted below. The claim worth making here is the one the directive implies: depth must be the
		//largest thing on the board, so an incidental term cannot be worth more than going deeper.
		if (!( config.depthReward > config.talentReward )){
			fail( "a talent point pays " + config.talentReward + " against " + config.depthReward
					+ " for a descent. Depth is the objective, so it must outbid every incidental term -"
					+ " otherwise the agent levelling is the best strategy on the board and descending is"
					+ " not. This gate once required the opposite, which is how a talent point reached 4.7"
					+ " floors while a descent was worth 1.2." );
			return;
		}

		final int[] spent = { 0 };

		Outcome o = play( config, 40, "TALENTCHECK", turn -> {
			Hero hero = Dungeon.hero;
			if (hero == null || !hero.isAlive()) return;

			//One plain turn first, for the same reason the pickup case takes one: the first settled
			//turn of an episode only establishes the reward model's snapshot.
			if (turn.step < 2){
				turn.env.step( Action.WAIT, 0 );
				return;
			}

			com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent talent = firstTalent( hero );
			if (talent == null){
				turn.env.step( Action.WAIT, 0 );
				return;
			}

			hero.upgradeTalent( talent );
			spent[ 0 ]++;
			turn.env.step( Action.WAIT, 0 );
		} );

		int notes = o.ledger == null ? 0 : o.ledger.notes( RewardTerm.TALENT_UPGRADE );

		if (spent[ 0 ] == 0 ){
			fail( "the driver could not find a talent to spend on this hero, so the emission site was"
					+ " never reached. Hero.talents is what the game itself reads, so an empty one means"
					+ " the hero was never initialised - a fault this case is supposed to detect rather"
					+ " than be confused by." );
			return;
		}

		if (notes == 0 ){
			fail( spent[ 0 ] + " talent point(s) were spent and TALENT_UPGRADE was never recorded."
					+ " Nothing headless calls Hero.upgradeTalent yet, which is TODO.md 1.10, but the"
					+ " emission site is supposed to be live now so the action has something to pay."
					+ " This is the shape KILL had: declared, plumbed, and never emitted." );
			return;
		}

		double paid = o.termTotal( RewardTerm.TALENT_UPGRADE );
		if (paid <= 0 ){
			fail( "TALENT_UPGRADE was recorded " + notes + " time(s) and paid " + paid + "." );
			return;
		}

		//The relationship is the claim, and LEVEL_UP is the natural yardstick: a level is a fixed
		//automatic gain, a point is a permanent choice about which gains to take.
		double levelUp = 2f;
		if (!( paid > levelUp * notes )){
			fail( "a talent point pays " + ( paid / notes ) + " against " + levelUp + " for a level."
					+ " Levelling raises attack, defence and max HP by one each and happens whether the"
					+ " agent plays well or not; a point is a permanent choice between options that"
					+ " differ by an order of magnitude. Paying them alike says the choice does not"
					+ " matter." );
			return;
		}

		System.out.println( "  a talent point pays " + fmt( config.talentReward ) + " against "
				+ fmt( levelUp ) + " for a level and " + fmt( config.depthReward )
				+ " for a descent - worth levelling for, and less than going deeper; "
				+ notes + " point(s) recorded" );
	}

	/**
	 * Any talent this hero could take, or null.
	 *
	 * <p>The first key of the first tier, which is what {@code TalentsPane} offers the player first and
	 * is therefore the least surprising choice for a gate.
	 */
	private static com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent firstTalent( Hero hero ){
		if (hero.talents.isEmpty()) return null;
		for (com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent t : hero.talents.get( 0 ).keySet()){
			return t;
		}
		return null;
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

		//IDENTIFIED moved by the whole round trip, read off the ledger the episode itself produced.
		//Reading either half alone can look innocent - the pickup pays, the drop refunds nothing - so it
		//is the *net* that has to be zero, and that is what the assertion below asks.
		final double[] identification = { 0 };

		Outcome o = play( config, 200, "EXPLOITCHECK", turn -> {
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
					//**identified**, because that is the version the exploit cycles. An unidentified
					// potion contributes 0 to `countIdentified` both while carried and after dropped,
					//so the round trip moves the count by nothing and the case proves nothing - which
					//is how the one-sided term survived a gate that ran this same loop. A player who
					//drinks one knows what it is; a dropped one stays known, so picking it back up is
					//paying again for knowledge the hero never lost.
					grabbed[ 0 ].identify();
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

		//**And no other term may pay for the same round trip.** This is the half that was missing, and
		// it is where the actual profit was. `ITEM_PICKUP`/`ITEM_DROPPED` have cancelled correctly since
		// issues.md 8 was fixed, so this case passed - while `IDENTIFIED` paid +0.5 for carrying an
		// already-identified item and refunded nothing when it was dropped, and the hero banked +27.50
		// over a run whose inventory churn was itself net negative. A gate that only inspects the term it
		// was written for cannot see a second term paying for the same event.
		double identifiedNow = o.ledger == null ? 0 : o.ledger.termTotal( RewardTerm.IDENTIFIED );
		identification[ 0 ] = identifiedNow;
		if (Math.abs( identification[ 0 ] ) > 1e-4 ){
			fail( "the pickup/drop round trip also moved IDENTIFIED by " + identification[ 0 ]
					+ ", so it was not free even though ITEM_PICKUP and ITEM_DROPPED cancelled. IDENTIFIED"
					+ " compared a count one way and refunded nothing on a decrease, which makes"
					+ " DROP-then-INTERACT a repeatable +0.5 on the same heap. Measured on a trainer run:"
					+ " IDENTIFIED +27.50 against an inventory round trip that was net -5.00." );
			return;
		}

		System.out.println( "  a pickup and its matching drop cancel: " + fmt( paid[ 0 ] ) + " paid,"
				+ " " + fmt( refunded[ 0 ] ) + " refunded, net " + fmt( paid[ 0 ] + refunded[ 0 ] )
				+ ", and no other term pays for the round trip" );
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

	/**
	 * Enough places to see a turn cost.
	 *
	 * <p>{@link #fmt} is two decimal places, which is right for scores and useless for a per-turn price:
	 * a turn costs 0.002, so every per-turn figure in this file printed as 0.00 and a case whose whole
	 * claim is "these two prices differ" was printing them identically. Three places separates them and
	 * is still one order below anything else the file reports.
	 */
	private static String fine( double v ){
		return String.format( java.util.Locale.ROOT, "%.4f", v );
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
