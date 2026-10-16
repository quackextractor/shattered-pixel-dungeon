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
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardLedger;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.StairPotential;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


public class RewardCheck {

	private static final int CHECKS = 26;


	private static class StairWalk {
		double away;
		double toward;
		int awaySteps;
		int towardSteps;

		double net(){ return away + toward; }
	}


	private static void checkClosingTheGapToTheStairsPays( EnvConfig config ){
		StairWalk walk = walkOutAndBack( config.copy(), 2f, 4 );

		if (walk.awaySteps == 0 || walk.towardSteps == 0){
			fail( "the walk away took " + walk.awaySteps + " step(s) and the walk back "
					+ walk.towardSteps + ", so neither half was exercised and the case measured"
					+ " nothing." );
			return;
		}

		if (walk.toward <= 0){
			fail( walk.towardSteps + " steps that closed the gap to the stairs paid "
					+ fmt( walk.toward ) + " of STAIR_POTENTIAL. A descent is the objective and the agent"
					+ " has to be able to see progress towards it." );
			return;
		}

		if (walk.away >= 0){
			fail( walk.awaySteps + " steps that opened the gap to the stairs paid " + fmt( walk.away )
					+ " of STAIR_POTENTIAL, so moving away from the objective is not worse than standing"
					+ " still. A shaping term with no downside is a reward." );
			return;
		}

		System.out.println( "  " + walk.towardSteps + " steps closing the gap pay "
				+ fmt( walk.toward ) + " and " + walk.awaySteps + " steps opening it cost "
				+ fmt( walk.away ));
	}


	private static void checkStairShapingCannotBeFarmed( EnvConfig config ){
		StairWalk walk = walkOutAndBack( config.copy(), 2f, 4 );

		double allowed = 0.5 * Math.max( 1, Math.abs( walk.toward ) );
		if (Math.abs( walk.net() ) > allowed){
			fail( "walking " + walk.awaySteps + " steps away from the stairs and "
					+ walk.towardSteps + " back nets " + fmt( walk.net() ) + " of STAIR_POTENTIAL ("
					+ fmt( walk.away ) + " then " + fmt( walk.toward ) + "). Potential-based shaping"
					+ " telescopes, so a round trip pays nothing however many times it is repeated -"
					+ " which is the whole difference between shaping and a farmable reward." );
			return;
		}

		System.out.println( "  a round trip away from the stairs and back nets " + fmt( walk.net() )
				+ " of STAIR_POTENTIAL, so it cannot be farmed" );
	}


	private static void checkStairShapingIsOffAtZero( EnvConfig config ){
		EnvConfig local = config.copy();
		local.stairPotentialWeight = 0f;

		StairWalk walk = walkOutAndBack( local, 0f, 4 );

		if (walk.net() != 0){
			fail( "with reward.stair_potential_weight = 0 the term still paid " + fmt( walk.net() )
					+ ", so the switch is not reachable and the two cases above prove nothing about"
					+ " which path a run takes." );
			return;
		}

		System.out.println( "  reward.stair_potential_weight = 0 pays nothing" );
	}


	private static StairWalk walkOutAndBack( EnvConfig config, float weight, int each ){
		config.stairPotentialWeight = weight;
		config.turnLimitPerFloor = 800;

		final StairWalk result = new StairWalk();
		final StairPotential potential = new StairPotential();
		final java.util.List< Action > outward = new ArrayList<>();
		final boolean[] returning = { false };

		play( config, each * 3 + 8, "SHAPEWALK", turn -> {
			Action step = null;

			if (!returning[ 0 ] && outward.size() >= each) returning[ 0 ] = true;

			boolean outwardPhase = !returning[ 0 ];

			if (outwardPhase){
				step = bestStepTowards( turn.env, potential, false );
				if (step == null) returning[ 0 ] = true;
			} else if (result.towardSteps < outward.size()){
				step = stepBackTowards( turn.env, outward,
						outward.size() - result.towardSteps - 1 );
			}

			if (step == null) return;

			double before = turn.env.ledger().termTotal( RewardTerm.STAIR_POTENTIAL );
			turn.env.step( step, 0 );
			double paid = turn.env.ledger().termTotal( RewardTerm.STAIR_POTENTIAL ) - before;

			if (outwardPhase){
				outward.add( step );
				result.away += paid;
				result.awaySteps++;
			} else {
				result.toward += paid;
				result.towardSteps++;
			}
		} );

		return result;
	}


	private static Action stepBackTowards( SPDEnv env, java.util.List< Action > outward, int index ){
		if (index < 0 || index >= outward.size()) return null;

		Action taken = outward.get( index );
		int here = Dungeon.hero.pos;
		int back = here - taken.dx - taken.dy * Dungeon.level.width();

		for (Action a : Action.values()){
			if (!a.directional()) continue;
			if (env.actionMask()[ a.index ] == 0f ) continue;
			if (here + a.dx + a.dy * Dungeon.level.width() != back) continue;
			return a;
		}

		return null;
	}


	private static Action bestStepTowards( SPDEnv env, StairPotential potential, boolean toward ){
		if (Dungeon.level == null || Dungeon.hero == null) return null;
		if (Dungeon.level.getTransition( null ) == null) return null;

		int here = Dungeon.hero.pos;
		int width = Dungeon.level.width();
		int from = potential.distanceFrom( here );
		if (from == StairPotential.UNREACHABLE) return null;

		Action best = null;
		int bestDistance = from;

		for (Action a : Action.values()){
			if (!a.directional()) continue;
			if (env.actionMask()[ a.index ] == 0f ) continue;

			int cell = here + a.dx + a.dy * width;
			if (cell < 0 || cell >= Dungeon.level.length()) continue;

			int d = potential.distanceFrom( cell );
			if (d == StairPotential.UNREACHABLE) continue;

			boolean improves = toward ? d < bestDistance : d > bestDistance;
			if (!improves) continue;

			bestDistance = d;
			best = a;
		}

		return best;
	}


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
		checkOnlyANewFloorIsPaidFor( config );
		checkFreeStepLoopIsBounded( config );
		checkStallPathRecordsOnce( config );
		checkDefaultProfilePaysEveryTerm();
		checkDepthOnlyPaysOnlyForDepth();
		checkDepthOnlyKeepsTheFourNegatives();
		checkRestIsNotAOneWayDoor( config );
		checkTheHeroCanDie( config );
		checkPickingUpAndDroppingIsNotProfitable( config );
		checkKillingIsWorthDoing( config );
		checkFreeStepsCostSomething( config );
		checkWaitingIsNotAFreeStep( config );
		checkAMultiTurnActionPaysForEveryTurn( config );
		checkSearchingIsNotCheaperThanActing( config );
		checkSpendingATalentIsWorthALot( config );

		checkClosingTheGapToTheStairsPays( config );
		checkStairShapingCannotBeFarmed( config );
		checkStairShapingIsOffAtZero( config );

		if (failures.isEmpty()){
			System.out.println( "[OK]     reward signals: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  reward signals: " + failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}


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


	private static void checkStallCostsMoreThanDying( EnvConfig config ){
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


	private static void checkKillingIsWorthDoing( EnvConfig config ){
		final boolean[] placed = { false };
		final int[] lastMobHp = { -1 };

		Outcome o = play( config, 200, "KILLCHECK", turn -> {
			SPDEnv env = turn.env;
			if (env.mode() != EnvMode.WORLD){
				env.step( Action.CANCEL, 0 );
				return;
			}

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

		if (!( config.sneakAttackReward > config.killReward )){
			fail( "a surprise attack pays " + config.sneakAttackReward + " and a kill pays "
					+ config.killReward + ". The surprise is the one that costs no health -"
					+ " Mob.surprisedBy skips the defender's defence - so paying it less than the kill"
					+ " teaches trading blows instead." );
			return;
		}

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

			if (Math.abs( cell % w - hero.pos % w ) > 1) continue;
			if (Actor.findChar( cell ) != null) continue;
			if (Dungeon.level.heaps.get( cell ) != null) continue;

			Mob mob = Dungeon.level.createMob();
			if (mob == null) continue;

			if (Char.hasProp( mob, Char.Property.LARGE ) && !Dungeon.level.openSpace[ cell ]) continue;
			if (mob.state != mob.PASSIVE) mob.state = mob.WANDERING;
			mob.pos = cell;

			com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.LevelPipeline.spriteFor( mob );
			Actor.add( mob );
			Dungeon.level.mobs.add( mob );

			Dungeon.observe();
			return true;
		}
		return false;
	}


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

		for (Action a : order){
			if (a.index < env.actionMask().length && env.actionMask()[ a.index ] != 0f ) return a;
		}
		return Action.SEARCH;
	}


	private static void checkFreeStepsCostSomething( EnvConfig config ){
		double realStep = config.turnCost;
		double freeStep = config.turnCost * config.freeStepCostFactor;

		if (!( freeStep > 0 )){
			fail( "a step that advances no engine time costs " + freeStep + ", so a free action is free."
					+ " duelist-mid alternated equip and unequip for 120 steps at that price." );
			return;
		}

		if (!( freeStep < realStep )){
			fail( "a free step costs " + fine( freeStep ) + " against " + fine( realStep ) + " for a real"
					+ " one, so free_step_cost_factor is not expressing the ratio it exists to express. The"
					+ " directive is half a turn: cheaper than acting, and not free." );
			return;
		}

		Outcome o = play( config, 60, "FREESTEPCHECK", turn -> {
			SPDEnv env = turn.env;

			if (!env.running()) return;

			if (env.mode() != EnvMode.WORLD){
				env.step( Action.CANCEL, 0 );
				return;
			}

			env.step( Action.USE, 0 );
			if (env.running() && env.mode() == EnvMode.SLOT) env.step( Action.USE, equippableSlot( env ) );
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

		double paid = o.termTotal( RewardTerm.NO_PROGRESS );
		if (Math.abs( paid + freeStep * charged ) > 1e-4 ){
			fail( "NO_PROGRESS was recorded " + charged + " time(s) and paid " + paid + " where "
					+ fine( freeStep * charged ) + " was expected (" + charged + " x "
					+ fine( freeStep ) + "). The factor is the relationship, so it is measured per step"
					+ " rather than compared as a total." );
			return;
		}

		double realPaid = Math.abs( o.termTotal( RewardTerm.TURN_COST ) );
		int realSteps = o.turns - charged;
		if (realSteps > 0 && Math.abs( realPaid - realStep * realSteps ) > 1e-4 ){
			fail( realSteps + " real steps cost " + realPaid + " where " + fine( realStep * realSteps )
					+ " was expected (" + realSteps + " x " + fine( realStep ) + "). A free step is half a"
					+ " turn only if a real one is a whole turn." );
			return;
		}

		System.out.println( "  a free step costs " + fine( freeStep ) + " against " + fine( realStep )
				+ " for a real one - " + charged + " free (" + fine( paid ) + "), " + realSteps
				+ " real (" + fine( realPaid ) + ")" );
	}


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

		if (o.termTotal( RewardTerm.TURN_COST ) > 0 ){
			fail( "TURN_COST paid " + o.termTotal( RewardTerm.TURN_COST ) + " over " + steps[ 0 ]
					+ " searches; a positive turn cost pays the agent to spend time." );
			return;
		}

		System.out.println( "  a search costs " + fine( perSearch ) + " for the engine time it consumes,"
				+ " against " + fine( singleTurn ) + " for a single-turn action" );
	}


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


	private static void checkSpendingATalentIsWorthALot( EnvConfig config ){

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


	private static com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent firstTalent( Hero hero ){
		if (hero.talents.isEmpty()) return null;
		for (com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent t : hero.talents.get( 0 ).keySet()){
			return t;
		}
		return null;
	}


	private static void checkFreeStepLoopIsBounded( EnvConfig config ){
		Outcome loop = playFreeStepLoop( config, config.freeStepBudget + 200 );

		if (loop.endReason != RewardModel.TerminateReason.STALLED ){
			fail( "an episode spending its steps on OPEN_INVENTORY and CANCEL, with a move between pairs,"
					+ " ended " + loop.endReason + " after " + loop.turns + " turns rather than stalling."
					+ " " + loop.ledger.notes( RewardTerm.NO_PROGRESS ) + " of those steps advanced no"
					+ " engine time, which is " + loop.termTotal( RewardTerm.NO_PROGRESS )
					+ " of the run's cost. The position guard cannot see this shape - one move resets it -"
					+ " so nothing bounded it." );
			return;
		}

		int free = loop.ledger == null ? 0 : loop.ledger.notes( RewardTerm.NO_PROGRESS );
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

		int controlFree = control.ledger == null ? 0 : control.ledger.notes( RewardTerm.NO_PROGRESS );
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


	private static Action legalMove( SPDEnv env ){
		float[] mask = env.actionMask();
		for (Action a : Action.values()){
			if (a.directional() && a.index < mask.length && mask[ a.index ] != 0f ) return a;
		}
		return Action.SEARCH;
	}


	private static void checkStallPathRecordsOnce( EnvConfig config ){
		Outcome stall = playWaits( config, config.stallLimit + 8 );
		if (stall.endReason != RewardModel.TerminateReason.STALLED ) return;  

		int records = stall.ledger == null ? 0 : stall.ledger.notes( RewardTerm.STALLED );
		if (records != 1 ){
			fail( "a stalled episode recorded STALLED " + records + " times, expected 1. The guard in"
					+ " settle() used to call terminate twice, charging the terminal reward twice"
					+ " over." );
			return;
		}

		System.out.println( "  the reachable stall path records STALLED exactly once" );
	}


	private static void checkDefaultProfilePaysEveryTerm(){
		List< String > refused = new ArrayList<>();
		for (int i = 0; i < RewardTerm.count(); i++){
			RewardTerm term = RewardTerm.at( i );
			if (!RewardProfile.DEFAULT.allows( term )) refused.add( term.name() );
		}

		if (!refused.isEmpty()){
			fail( "the DEFAULT profile refuses " + refused + ", so the shipped reward function no longer"
					+ " pays for terms it is documented to pay for." );
			return;
		}

		RewardProfile compiled = new EnvConfig().rewardProfile;
		if (compiled != RewardProfile.DEFAULT ){
			fail( "a run with no configuration and no SPD_* variables asks for " + compiled
					+ ". The compiled default has to be DEFAULT or the experiment ships enabled." );
			return;
		}

		RewardProfile loaded = com.shatteredpixel.shatteredpixeldungeon.superintelligence.env
				.EnvConfigBinder.defaults().config.rewardProfile;
		if (loaded != RewardProfile.DEFAULT ){
			fail( "the binder loads " + loaded + " from nothing, where a run with no file must load"
					+ " DEFAULT." );
			return;
		}

		System.out.println( "  DEFAULT admits all " + RewardTerm.count() + " terms and is what a run with"
				+ " no configuration loads" );
	}


	private static void checkDepthOnlyPaysOnlyForDepth(){
		EnvConfig config = new EnvConfig();
		config.rewardProfile = RewardProfile.DEPTH_ONLY;

		final boolean[] placed = { false };
		final boolean[] descended = { false };
		final int[] incidental = { 0 };
		final List< String > incidentalTerms = new ArrayList<>();

		Outcome o = play( config, 220, "DEPTHONLY", turn -> {
			SPDEnv env = turn.env;
			if (env.mode() != EnvMode.WORLD ){
				env.step( Action.CANCEL, 0 );
				return;
			}

			if (Dungeon.hero != null && Dungeon.hero.isAlive() && Dungeon.hero.HP < 12){
				Dungeon.hero.HP = Dungeon.hero.HT;
			}

			if (turn.step % 4 == 3 ){
				env.step( Action.USE, 0 );
				if (env.mode() == EnvMode.SLOT) env.step( Action.USE, equippableSlot( env ) );
				return;
			}

			if (!descended[ 0 ] && turn.step >= 40 && descend( env )){
				descended[ 0 ] = true;
				return;
			}

			if (!placed[ 0 ] && placeMobBesideHero( env )) placed[ 0 ] = true;

			Mob target = adjacentEnemy();
			if (target != null){
				env.step( directionToTarget( env, target ), 0 );
				return;
			}

			if (turn.step % 3 == 0) env.step( Action.SEARCH, 0 );
			else env.step( Action.values()[ 4 + (turn.step % 4 )], 0 );
		} );

		if (o.ledger == null || o.ledger.profile() != RewardProfile.DEPTH_ONLY ){
			fail( "the episode ran under " + ( o.ledger == null ? "no ledger" : o.ledger.profile() )
					+ " rather than depth_only, so nothing below measured the override." );
			return;
		}

		List< String > paid = new ArrayList<>();
		for (int i = 0; i < RewardTerm.count(); i++){
			RewardTerm term = RewardTerm.at( i );
			if (RewardProfile.DEPTH_ONLY.allows( term )) continue;
			int notes = o.ledger.notes( term );
			if (notes > 0 ) incidentalTerms.add( term.name() + "x" + notes );
			if (o.ledger.termTotal( term ) != 0 ){
				paid.add( term.name() + "=" + o.ledger.termTotal( term ));
			}
			incidental[ 0 ] += notes;
		}

		if (!paid.isEmpty()){
			fail( "depth_only paid " + paid + ". The only route to a positive score under this profile is"
					+ " a new floor or a win; everything else must total exactly zero." );
			return;
		}

		if (o.ledger.notes( RewardTerm.EXPLORE ) == 0 ){
			fail( "the driver searched and walked for " + o.turns + " turns and EXPLORE was never recorded,"
					+ " so this case measured an episode that never reached the terms it silences." );
			return;
		}

		int combat = o.ledger.notes( RewardTerm.KILL ) + o.ledger.notes( RewardTerm.SNEAK_ATTACK )
				+ o.ledger.notes( RewardTerm.DAMAGE_TAKEN );
		if (combat == 0 ){
			fail( "a level-appropriate mob was placed beside the hero and fought for " + o.turns
					+ " turns with no KILL, SNEAK_ATTACK or DAMAGE_TAKEN recorded (placed=" + placed[ 0 ]
					+ "), so the combat terms were never exercised under the override." );
			return;
		}

		if (o.ledger.notes( RewardTerm.NO_PROGRESS ) == 0 ){
			fail( "the driver equipped and unequipped the hero's weapon throughout and NO_PROGRESS was never"
					+ " recorded, so the free-step charge was not exercised under the override." );
			return;
		}

		if (!descended[ 0 ] || Dungeon.depth <= 1 ){
			fail( "the hero never descended (depth " + Dungeon.depth + ", descended=" + descended[ 0 ]
					+ "), so the case never reached the one route to a positive score it exists to protect." );
			return;
		}

		double depthPaid = o.ledger.termTotal( RewardTerm.DEPTH_ADVANCE );
		if (depthPaid < config.depthReward ){
			fail( "the hero reached depth " + Dungeon.depth + " and DEPTH_ADVANCE paid " + depthPaid
					+ " against a configured " + config.depthReward + ". Under depth_only this term is the"
					+ " objective, and the one positive score an episode can earn." );
			return;
		}

		double positives = 0;
		for (int i = 0; i < RewardTerm.count(); i++){
			double total = o.ledger.termTotal( RewardTerm.at( i ));
			if (total > 0) positives += total;
		}
		if (Math.abs( positives - depthPaid ) > 1e-4 ){
			fail( "the run's positive score totals " + positives + " against " + depthPaid
					+ " of DEPTH_ADVANCE, so something other than the descent paid for something." );
			return;
		}

		System.out.println( "  depth_only: " + incidental[ 0 ] + " incidental event(s) ("
				+ String.join( ", ", incidentalTerms ) + ") paid nothing; the descent paid " + fmt( depthPaid )
				+ " and it is the whole of the run's positive score" );
	}


	private static void checkDepthOnlyKeepsTheFourNegatives(){
		EnvConfig config = new EnvConfig();
		config.rewardProfile = RewardProfile.DEPTH_ONLY;

		Outcome stall = playWaits( config, config.stallLimit + 8 );
		if (stall.endReason != RewardModel.TerminateReason.STALLED ){
			fail( "waiting under depth_only ended " + stall.endReason + " rather than stalling, so the"
					+ " stall price was not exercised." );
			return;
		}

		double stallPaid = stall.termTotal( RewardTerm.STALLED );
		if (Math.abs( stallPaid + config.stallPenalty ) > 1e-4 ){
			fail( "a stalled run under depth_only paid " + stallPaid + " for STALLED, expected "
					+ (-config.stallPenalty) + ". Stall is one of the four prices the profile keeps." );
			return;
		}

		double turnCost = stall.termTotal( RewardTerm.TURN_COST );
		double expectedTurnCost = -config.turnCost * (stall.turns - freeSteps( stall ) );
		if (Math.abs( turnCost - expectedTurnCost ) > 1e-5 ){
			fail( "a stalled run under depth_only charged " + turnCost + " in turn cost where "
					+ expectedTurnCost + " was expected: " + stall.turns + " turns, "
					+ freeSteps( stall ) + " of them free." );
			return;
		}

		EnvConfig limited = new EnvConfig();
		limited.rewardProfile = RewardProfile.DEPTH_ONLY;
		limited.turnLimitPerFloor = 12;
		limited.stallLimit = 100000;
		Outcome capped = playWaits( limited, 40 );
		if (capped.endReason != RewardModel.TerminateReason.TURN_LIMIT ){
			fail( "a run capped at " + limited.turnLimitPerFloor + " turns ended "
					+ capped.endReason + " rather than on the turn limit, so that price was not exercised." );
			return;
		}

		double cappedPaid = capped.termTotal( RewardTerm.TURN_LIMIT );
		if (Math.abs( cappedPaid + limited.turnLimitPenalty ) > 1e-4 ){
			fail( "a run that ran out of turns paid " + cappedPaid + " for TURN_LIMIT, expected "
					+ (-limited.turnLimitPenalty) + "." );
			return;
		}

		Outcome death = play( config, 60, "DEPTHONLYDEATH", turn -> {
			turn.env.step( Action.WAIT, 0 );
			if (Dungeon.hero != null && Dungeon.hero.isAlive()){
				Dungeon.hero.damage( Dungeon.hero.HP + 1, Dungeon.hero );
			}
		} );

		if (death.endReason != RewardModel.TerminateReason.DEATH ){
			fail( "the hero was killed under depth_only and the run ended " + death.endReason
					+ ", so the death price was not exercised." );
			return;
		}

		double deathPaid = death.termTotal( RewardTerm.DEATH );
		if (Math.abs( deathPaid + config.deathPenalty ) > 1e-4 ){
			fail( "a dead run under depth_only paid " + deathPaid + " for DEATH, expected "
					+ (-config.deathPenalty) + "." );
			return;
		}

		System.out.println( "  depth_only still charges stall " + fmt( stallPaid ) + ", turn limit "
				+ fmt( cappedPaid ) + ", death " + fmt( deathPaid ) + " and " + fmt( turnCost )
				+ " of turn cost" );
	}


	private static void checkOnlyANewFloorIsPaidFor( EnvConfig config ){
		final int[] descents = { 0 };
		final int[] ascents = { 0 };

		Outcome o = play( config, 120, "PINGPONG", turn -> {
			SPDEnv env = turn.env;
			if (env.mode() != EnvMode.WORLD ){
				env.step( Action.CANCEL, 0 );
				return;
			}

			if (Dungeon.hero == null || !Dungeon.hero.isAlive()) return;

			if (turn.step % 12 == 4 ){
				if (ascend( env )){
					ascents[ 0 ]++;
					return;
				}
			}
			if (turn.step % 12 == 0 ){
				if (descend( env )) descents[ 0 ]++;
				return;
			}
			env.step( Action.WAIT, 0 );
		} );

		if (descents[ 0 ] < 3 || ascents[ 0 ] < 2 ){
			fail( "the driver performed " + descents[ 0 ] + " descent(s) and " + ascents[ 0 ] + " ascent(s),"
					+ " so it never reached the shape this case is about (depth "
					+ Dungeon.depth + ", deepest " + o.deepest + "). A case that cannot reach the code it"
					+ " guards is not a check." );
			return;
		}

		if (o.deepest < 2 ){
			fail( "the driver descended " + descents[ 0 ] + " times and never got past depth "
					+ o.deepest + ", so there was nothing to re-descend." );
			return;
		}

		double paid = o.termTotal( RewardTerm.DEPTH_ADVANCE );
		double oneFloor = config.depthReward * (o.deepest - 1);

		if (Math.abs( paid - oneFloor ) > 1e-4 ){
			fail( "after " + descents[ 0 ] + " descents to a deepest floor of " + o.deepest
					+ ", DEPTH_ADVANCE paid " + paid + " where " + oneFloor + " was expected - one reward"
					+ " per new floor, and none for coming back down a stair the run had already used."
					+ " Paying on the direction made 1 -> 2 -> 1 -> 2 a money machine: +7200 at deepest=2 on"
					+ " JEA-FKU-WZS." );
			return;
		}

		System.out.println( "  " + descents[ 0 ] + " descents and " + ascents[ 0 ] + " ascents reached depth "
				+ o.deepest + " and paid " + fmt( paid ) + " - one reward per new floor, not per stair" );
	}


	private static boolean descend( SPDEnv env ){
		com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition exit =
				Dungeon.level.getTransition(
						com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition.Type.REGULAR_EXIT );
		if (exit == null) return false;

		Hero hero = Dungeon.hero;
		hero.pos = exit.cell();
		boolean accepted = Dungeon.level.activateTransition( hero, exit );
		hero.ready = false;
		return accepted;
	}


	private static boolean ascend( SPDEnv env ){
		com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition entrance =
				Dungeon.level.getTransition(
						com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition.Type.REGULAR_ENTRANCE );
		if (entrance == null) return false;

		Hero hero = Dungeon.hero;
		hero.pos = entrance.cell();
		boolean accepted = Dungeon.level.activateTransition( hero, entrance );
		hero.ready = false;
		return accepted;
	}


	private static void checkTheHeroCanDie( EnvConfig config ){
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

		if (o.ledger == null || o.ledger.notes( RewardTerm.DEATH ) == 0 ){
			fail( "the hero died but no DEATH was recorded, so the terminal reward never lands and the"
					+ " agent is never told that dying is expensive." );
			return;
		}

		System.out.println( "  the hero can die: DEATH recorded, natural ending, scored "
				+ fmt( o.score ) );
	}


	private static void checkWaitingIsNotAFreeStep( EnvConfig config ){

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

		if (o.stallCause == com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.StallCause.FREE_STEP){
			fail( "waiting " + o.turns + " times ended the episode on the FREE_STEP guard, so WAIT is still"
					+ " counted as a step that advances no engine time - which is exactly what"
					+ " env.free_step_budget exists to bound." );
			return;
		}

		int charged = o.ledger.notes( RewardTerm.NO_PROGRESS );
		if (charged != 0){
			fail( "waiting " + o.turns + " times charged NO_PROGRESS " + charged + " time(s). A turn"
					+ " the agent deliberately gives up is a turn, and is charged as one." );
		}
	}


	private static void checkRestIsNotAOneWayDoor( EnvConfig config ){

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


	private static void checkPickingUpAndDroppingIsNotProfitable( EnvConfig config ){
		final int[] phase = { 0 };
		final int[] tries = { 0 };
		final Item[] grabbed = { new Potion() };
		final double[] paid = { 0 };
		final double[] refunded = { 0 };
		final int[] slotUsed = { -1 };

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

					grabbed[ 0 ].identify();
					if (!takeItemFromTheFloor( env, grabbed[ 0 ] )) return;
					if (!carries( grabbed[ 0 ] )){

						phase[0] = 0;
						return;
					}
					paid[ 0 ] = env.ledger().termTotal( RewardTerm.ITEM_PICKUP );
					phase[ 0 ] = 1;
					return;

				case 1:

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


	private static boolean takeItemFromTheFloor( SPDEnv env, Item item ){
		if (Dungeon.level == null || Dungeon.hero == null) return false;

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


	private static boolean carries( Item item ){
		for (Item carried : Dungeon.hero.belongings){
			if (carried == item) return true;
		}
		return false;
	}


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

	private static int slotOf( SPDEnv env, Item item ){
		if (item == null) return -1;
		List< Item > slots = env.mapper().slots();
		for (int i = 0; i < slots.size(); i++){
			if (slots.get( i ) == item) return i;
		}
		return -1;
	}


	private static class Outcome {
		double score;
		int turns;


		int deepest;
		RewardModel.TerminateReason endReason = RewardModel.TerminateReason.OTHER;
		boolean endedNaturally;

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.StallCause stallCause =
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.StallCause.NONE;
		String startMode = "?";


		int refusals;
		int worldTurns;

		private RewardLedger ledger;

		double termTotal( RewardTerm term ){
			return ledger == null ? 0 : ledger.termTotal( term );
		}
	}


	private static Outcome playWaits( EnvConfig config, int maxTurns ){
		return play( config, maxTurns, "REWARDCHECK", turn -> {
			turn.env.step( Action.WAIT, 0 );
		} );
	}


	private static Outcome playInWorld( EnvConfig config, Action action, int maxTurns ){

		Outcome counted = new Outcome();

		Outcome o = play( config, maxTurns, "REWARDCHECK", turn -> {
			if (turn.env.mode() != EnvMode.WORLD ){
				turn.env.step( Action.CANCEL, 0 );
				return;
			}

			turn.env.step( action, 0 );
			counted.worldTurns++;

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
		outcome.deepest = env.deepestDepth();
		outcome.endReason = env.endReason();
		outcome.endedNaturally = env.endedNaturally();
		outcome.stallCause = env.stallCause();
		outcome.ledger = env.ledger();
		return outcome;
	}

	private static String fmt( double v ){
		return String.format( java.util.Locale.ROOT, "%.2f", v );
	}



	private static int freeSteps( Outcome o ){
		return o.ledger == null ? 0 : o.ledger.notes( RewardTerm.NO_PROGRESS );
	}

	private static String fine( double v ){
		return String.format( java.util.Locale.ROOT, "%.4f", v );
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
