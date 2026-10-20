package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.StairPotential;

import java.util.ArrayList;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fine;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fmt;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.play;

public final class RewardStairCheck {

	private RewardStairCheck(){}

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