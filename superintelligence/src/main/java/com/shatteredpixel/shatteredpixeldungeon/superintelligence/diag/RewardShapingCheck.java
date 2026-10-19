package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.StairPotential;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.Outcome;

import java.util.ArrayList;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.equippableSlot;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fine;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fmt;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.play;

public final class RewardShapingCheck {

	private RewardShapingCheck(){}


	public static void checkPickingUpAnItemPays(){
		Outcome outcome = pickup(
				new com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing() );

		if (outcome.notes( RewardTerm.ITEM_PICKUP ) == 0){
			fail( "the hero walked over a potion and picked it up through Action.INTERACT and"
					+ " ITEM_PICKUP never fired. The emission site exists and pays on a diff of carried"
					+ " value, so either the item never reached the backpack or the diff is read against"
					+ " the wrong snapshot." );
			return;
		}

		if (outcome.termTotal( RewardTerm.ITEM_PICKUP ) <= 0){
			fail( "ITEM_PICKUP fired " + outcome.notes( RewardTerm.ITEM_PICKUP ) + " time(s) and paid "
					+ fmt( outcome.termTotal( RewardTerm.ITEM_PICKUP ) ) + ". Picking something up has to"
					+ " be worth something or the agent is better off leaving it on the floor." );
			return;
		}

		System.out.println( "  picking an item up fires ITEM_PICKUP " + outcome.notes( RewardTerm.ITEM_PICKUP )
				+ " time(s) paying " + fmt( outcome.termTotal( RewardTerm.ITEM_PICKUP ) ) );
	}


	public static void checkPickingUpGoldPays(){
		Outcome outcome = pickup( new com.shatteredpixel.shatteredpixeldungeon.items.Gold( 25 ) );

		if (outcome.notes( RewardTerm.GOLD_GAIN ) == 0){
			fail( "the hero walked over 25 gold and picked it up through Action.INTERACT and"
					+ " GOLD_GAIN never fired. TODO.md 2 records it as a term that has never been seen"
					+ " paying in a run, and this is the emission site it would have to come from."
					+ " The heap was dropped at " + outcome.deepest + " with the run reaching depth "
					+ outcome.deepest + ", so the fixture is the question rather than the run." );
			return;
		}

		if (outcome.termTotal( RewardTerm.GOLD_GAIN ) <= 0){
			fail( "GOLD_GAIN fired " + outcome.notes( RewardTerm.GOLD_GAIN ) + " time(s) and paid "
					+ fmt( outcome.termTotal( RewardTerm.GOLD_GAIN ) ) );
			return;
		}

		System.out.println( "  picking gold up fires GOLD_GAIN " + outcome.notes( RewardTerm.GOLD_GAIN )
				+ " time(s) paying " + fmt( outcome.termTotal( RewardTerm.GOLD_GAIN ) ) );
	}


	public static void checkAMenuNoOpEmitsAndPays(){
		EnvConfig config = new EnvConfig();

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardLedger ledger
				= new com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardLedger();
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel model
				= new com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel(
				config, ledger, new com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.Curriculum() );

		RewardFixtures.play( config, 3, "MENU-NOOP", turn -> turn.env.step( Action.WAIT, 0 ) );

		model.resetSnapshot();
		model.step( 0, true, false );
		if (ledger.notes( RewardTerm.MENU_NOOP ) != 0){
			fail( "a step that advanced and left the sub-mode noted MENU_NOOP"
					+ ledger.notes( RewardTerm.MENU_NOOP ) + " time(s), so the term is paying for states it"
					+ " was written for" );
			return;
		}

		double before = ledger.termTotal( RewardTerm.MENU_NOOP );
		model.step( 0, true, true );

		if (ledger.notes( RewardTerm.MENU_NOOP ) != 1){
			fail( "a sub-mode step that advanced no engine time and stayed in its mode noted MENU_NOOP "
					+ ledger.notes( RewardTerm.MENU_NOOP ) + " time(s) where it should note it once."
					+ " The emission site is SPDEnv.menuNoop() into RewardModel.step, and TODO.md 2.1"
					+ " still says it does not exist." );
			return;
		}

		double paid = ledger.termTotal( RewardTerm.MENU_NOOP ) - before;
		if (Math.abs( paid - config.menuNoopPenalty ) > 1e-9){
			fail( "MENU_NOOP paid " + fmt( paid ) + " and reward.menu_noop_penalty is "
					+ fmt( config.menuNoopPenalty ) );
			return;
		}

		System.out.println( "  a sub-mode step that moves nothing fires MENU_NOOP and pays "
				+ fmt( paid ) + " - the state is unreachable in this environment, which"
				+ " checkAMenuNoOpIsUnreachable measures, and the site is live rather than declared" );
	}


	private static Outcome pickup( com.shatteredpixel.shatteredpixeldungeon.items.Item item ){
		return play( new EnvConfig(), 12, "PICKUPCHECK", turn -> {
			if (turn.step == 1){
				Dungeon.level.drop( item, cellBesideHero() );
				Dungeon.observe();
				turn.env.step( Action.INTERACT, 0 );
				return;
			}
			turn.env.step( Action.WAIT, 0 );
		} );
	}


	public static void checkPickingUpUnderfootPays(){
		Outcome outcome = play( new EnvConfig(), 12, "UNDERFOOTCHECK", turn -> {
			if (turn.step == 1){
				Dungeon.level.drop( new com.shatteredpixel.shatteredpixeldungeon.items.potions
						.PotionOfHealing(), Dungeon.hero.pos );
				Dungeon.observe();
				turn.env.step( Action.INTERACT, 0 );
				return;
			}
			turn.env.step( Action.WAIT, 0 );
		} );

		if (outcome.notes( RewardTerm.ITEM_PICKUP ) == 0){
			fail( "a potion lying on the hero's own cell was not picked up by Action.INTERACT and"
					+ " ITEM_PICKUP never fired. The hero reaches that cell by walking over the heap, which"
					+ " is the only way a dropped item ever becomes carried, and an agent whose own"
					+ " pickup is unreachable cannot learn to collect anything." );
			return;
		}

		System.out.println( "  a heap under the hero is picked up by INTERACT, ITEM_PICKUP firing "
				+ outcome.notes( RewardTerm.ITEM_PICKUP ) + " time(s) paying "
				+ fmt( outcome.termTotal( RewardTerm.ITEM_PICKUP ) ) );
	}


	private static int cellBesideHero(){
		int width = Dungeon.level.width();
		for (int off : new int[]{ -width, width, -1, 1 }){
			int cell = Dungeon.hero.pos + off;
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			if (!Dungeon.level.insideMap( cell )) continue;
			if (!Dungeon.level.passable[ cell ]) continue;
			if (Actor.findChar( cell ) != null) continue;
			return cell;
		}
		return Dungeon.hero.pos;
	}

	private static class StairWalk {
		double away;
		double toward;
		int awaySteps;
		int towardSteps;
		int waits;
		int exit = -1;
		int distanceAtExit = -1;
		double expected;

		double net(){ return away + toward; }
	}


	public static void checkClosingTheGapToTheStairsPays( EnvConfig config ){
		StairWalk walk = walkOutAndBack( config.copy(), 2f, 4 );

		if (walk.exit < 0 || walk.distanceAtExit != 0){
			fail( "the stair potential is " + walk.distanceAtExit + " steps from zero on the downstairs at "
					+ walk.exit + ". Level.getTransition(null) returns the entrance, so a potential built"
					+ " on it pays the agent for walking back up." );
			return;
		}

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


	public static void checkStairShapingCannotBeFarmed( EnvConfig config ){
		EnvConfig local = config.copy();
		local.shapingGamma = 0.9f;
		StairWalk walk = walkOutAndBack( local, 2f, 4 );

		if (walk.awaySteps == 0 || walk.towardSteps == 0 || walk.waits == 0){
			fail( "the round trip took " + walk.awaySteps + " step(s) out, " + walk.towardSteps
					+ " back and " + walk.waits + " wait(s), so it measured nothing." );
			return;
		}

		if (Math.abs( walk.net() - walk.expected ) > 1e-3){
			fail( "walking " + walk.awaySteps + " steps away from the stairs and "
					+ walk.towardSteps + " back nets " + fine( walk.net() ) + " of STAIR_POTENTIAL ("
					+ fmt( walk.away ) + " then " + fmt( walk.toward ) + ") where gamma*Phi(s') - Phi(s)"
					+ " summed over the same cells is " + fine( walk.expected ) + ". Potential-based"
					+ " shaping telescopes under the discount, so anything else is a farmable reward." );
			return;
		}

		System.out.println( "  a round trip away from the stairs and back nets " + fine( walk.net() )
				+ " of STAIR_POTENTIAL, exactly the discounted potential difference" );
	}


	public static void checkStairShapingIsOffAtZero( EnvConfig config ){
		EnvConfig local = config.copy();
		local.stairPotentialWeight = 0f;

		StairWalk walk = walkOutAndBack( local, 0f, 4 );

		if (walk.awaySteps == 0){
			fail( "with reward.stair_potential_weight = 0 the walk took no step, so it measured nothing." );
			return;
		}

		if (walk.net() != 0){
			fail( "with reward.stair_potential_weight = 0 the term still paid " + fmt( walk.net() )
					+ ", so the switch is not reachable and the two cases above prove nothing about"
					+ " which path a run takes." );
			return;
		}

		System.out.println( "  reward.stair_potential_weight = 0 pays nothing" );
	}


	public static void checkFreeStepsCostSomething( EnvConfig config ){
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

		int charged = o.notes( RewardTerm.NO_PROGRESS );
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


	public static void checkAMultiTurnActionPaysForEveryTurn( EnvConfig config ){
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


	public static void checkSearchingIsNotCheaperThanActing( EnvConfig config ){
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


	private static StairWalk walkOutAndBack( EnvConfig config, float weight, int each ){
		config.stairPotentialWeight = weight;
		config.turnLimitPerFloor = 800;

		final StairWalk result = new StairWalk();
		final StairPotential potential = new StairPotential();
		final ArrayList< Action > outward = new ArrayList<>();
		final boolean[] returning = { false };

		play( config, each * 3 + 8, "SHAPEWALK", turn -> {
			Action step = null;

			if (turn.step == 0){
				result.exit = regularExit();
				if (result.exit >= 0) result.distanceAtExit = potential.distanceFrom( result.exit );
				turn.env.step( Action.WAIT, 0 );
				return;
			}

			if (!returning[ 0 ] && outward.size() >= each) returning[ 0 ] = true;

			boolean outwardPhase = !returning[ 0 ] || result.waits == 0;

			if (returning[ 0 ] && result.waits == 0){
				step = Action.WAIT;
				result.waits++;
			} else if (outwardPhase){
				step = bestStepTowards( turn.env, potential, false );
				if (step == null) returning[ 0 ] = true;
			} else if (result.towardSteps < outward.size()){
				step = stepBackTowards( turn.env, outward,
						outward.size() - result.towardSteps - 1 );
			}

			if (step == null) return;

			double before = turn.env.ledger().termTotal( RewardTerm.STAIR_POTENTIAL );
			int from = Dungeon.hero.pos;
			int depth = Dungeon.depth;
			turn.env.step( step, 0 );
			double paid = turn.env.ledger().termTotal( RewardTerm.STAIR_POTENTIAL ) - before;
			result.expected += expectedShaping( turn.env, potential, weight, depth, from );

			if (outwardPhase){
				if (step != Action.WAIT) outward.add( step );
				result.away += paid;
				result.awaySteps++;
			} else {
				result.toward += paid;
				result.towardSteps++;
			}
		} );

		return result;
	}


	private static double expectedShaping( SPDEnv env, StairPotential potential, float weight,
			int depth, int from ){
		int to = Dungeon.hero.pos;
		if (Dungeon.depth != depth) return 0;
		float shaped = env.config().shapingGamma * potential.potential( to ) - potential.potential( from );
		return weight * shaped * env.ledger().shapingScale();
	}


	private static Action stepBackTowards( SPDEnv env, ArrayList< Action > outward, int index ){
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


	private static int regularExit(){
		for (LevelTransition transition : Dungeon.level.transitions){
			if (transition.type == LevelTransition.Type.REGULAR_EXIT) return transition.cell();
		}
		return -1;
	}


	private static Action bestStepTowards( SPDEnv env, StairPotential potential, boolean toward ){
		if (Dungeon.level == null || Dungeon.hero == null) return null;
		if (regularExit() < 0) return null;

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
}