package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Travel;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.StairPotential;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;

import java.util.ArrayList;
import java.util.List;

public class TravelCheck {

	private static final int CHECKS = 5;

	private static final String[] SEEDS = { "TRAVEL-A", "TRAVEL-B", "TRAVEL-C", "TRAVEL-D", "TRAVEL-E", "TRAVEL-F" };

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "travelcheck" ));
		HeadlessServices.disableSaving( true );

		checkUnseenStairsAreNotATarget();
		checkTravelOnlyStepsOntoKnownCells();
		checkTravellingAloneDescends();
		checkTravelNeverCrossesTheUpstairs();
		checkCachedTravelMatchesFresh();

		if (failures.isEmpty()){
			System.out.println( "[OK]     travel actions: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  travel actions: " + failures.size() + " of " + CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	private static void checkUnseenStairsAreNotATarget(){
		for (String seed : SEEDS){
			SPDEnv env = fresh( seed );
			int exit = StairPotential.exitCell( Dungeon.level );
			boolean known = exit >= 0 && (Dungeon.level.visited[ exit ] || Dungeon.level.mapped[ exit ]);
			if (!known && env.actionMask()[ Action.TRAVEL_STAIRS.index ] > 0.5f){
				fail( seed + ": TRAVEL_STAIRS is offered before the hero has seen the stairs at " + exit
						+ ", so the mask hands over where the exit is" );
				return;
			}
		}
		System.out.println( "  TRAVEL_STAIRS is masked until the stairs have been seen, " + SEEDS.length + " seeds" );
	}

	private static void checkTravelOnlyStepsOntoKnownCells(){
		int steps = 0;
		for (String seed : SEEDS){
			SPDEnv env = fresh( seed );
			for (int i = 0; i < 300 && env.running() && Dungeon.depth == 1; i++){
				if (env.mode() != EnvMode.WORLD || env.actionMask()[ Action.TRAVEL_EXPLORE.index ] < 0.5f) break;
				int before = Dungeon.hero.pos;
				boolean[] known = new boolean[ Dungeon.level.length() ];
				for (int c = 0; c < known.length; c++) known[ c ] = Dungeon.level.visited[ c ] || Dungeon.level.mapped[ c ];
				env.step( Action.TRAVEL_EXPLORE, 0 );
				if (!env.lastStepActed()){
					fail( seed + ": TRAVEL_EXPLORE was offered at " + before + " and refused" );
					return;
				}
				int after = Dungeon.hero.pos;
				if (after != before && Dungeon.depth == 1 && !known[ after ]){
					fail( seed + ": TRAVEL_EXPLORE walked " + before + " -> " + after + ", a cell the hero had never seen" );
					return;
				}
				steps++;
			}
		}
		if (steps == 0){
			fail( "TRAVEL_EXPLORE was never offered on a fresh floor, so this case measured nothing" );
			return;
		}
		System.out.println( "  " + steps + " TRAVEL_EXPLORE steps, every one accepted and onto a seen cell" );
	}

	private static void checkTravellingAloneDescends(){
		int descended = 0;
		StringBuilder detail = new StringBuilder();
		for (String seed : SEEDS){
			SPDEnv env = fresh( seed );
			int i = 0;
			for (; i < 3000 && env.running() && Dungeon.depth == 1; i++){
				if (env.mode() != EnvMode.WORLD){
					env.step( Action.CANCEL, 0 );
					continue;
				}
				float[] mask = env.actionMask();
				Action a = adjacentEnemy() && mask[ Action.INTERACT.index ] > 0.5f ? Action.INTERACT
						: mask[ Action.TRAVEL_STAIRS.index ] > 0.5f ? Action.TRAVEL_STAIRS
						: mask[ Action.TRAVEL_EXPLORE.index ] > 0.5f ? Action.TRAVEL_EXPLORE : Action.SEARCH;
				env.step( a, 0 );
			}
			if (Dungeon.depth > 1) descended++;
			detail.append( ' ' ).append( seed ).append( '=' ).append( Dungeon.depth > 1 ? "down@" + i : env.endReason().name() + "@" + i );
		}
		if (descended * 2 < SEEDS.length){
			fail( "fighting what is adjacent, else TRAVEL_STAIRS, else TRAVEL_EXPLORE, else SEARCH, descended on " + descended
					+ " of " + SEEDS.length + " seeds:" + detail );
			return;
		}
		System.out.println( "  travel alone descends on " + descended + " of " + SEEDS.length + " seeds:" + detail );
	}

	private static boolean adjacentEnemy(){
		for (Mob mob : Dungeon.hero.getVisibleEnemies()){
			if (mob.isAlive() && Dungeon.level.adjacent( mob.pos, Dungeon.hero.pos )) return true;
		}
		return false;
	}

	private static void checkTravelNeverCrossesTheUpstairs(){
		SPDEnv env = fresh( SEEDS[ 0 ] );
		if (!RewardFixtures.descend( env ) || !env.running()){
			fail( "could not reach depth 2 on " + SEEDS[ 0 ] + ", so this case measured nothing" );
			return;
		}
		env.step( Action.WAIT, 0 );
		LevelTransition up = Dungeon.level.getTransition( LevelTransition.Type.REGULAR_ENTRANCE );
		for (int i = 0; i < 10 && env.running() && up != null && Dungeon.hero.pos == up.cell(); i++){
			env.step( env.mode() == EnvMode.WORLD ? RewardFixtures.legalMove( env ) : Action.CANCEL, 0 );
		}
		if (Dungeon.depth != 2 || up == null || Dungeon.hero.pos == up.cell()){
			fail( "the hero did not stand on depth 2 off its upstairs (depth " + Dungeon.depth + ", hero " + Dungeon.hero.pos + ", upstairs " + (up == null ? -1 : up.cell()) + "), so this case measured nothing" );
			return;
		}
		if (Travel.walkable( Dungeon.level )[ up.cell() ]){
			fail( "travel paths may cross the upstairs at " + up.cell() + ", so TRAVEL_EXPLORE climbs a floor"
					+ " (goexplore DEMO-3 did, depth 4 -> 3)" );
			return;
		}
		System.out.println( "  travel paths avoid the upstairs at " + up.cell() + " on depth 2" );
	}

	private static void checkCachedTravelMatchesFresh(){
		int decisions = 0;
		for (String seed : SEEDS){
			SPDEnv env = fresh( seed );
			java.util.Random rng = new java.util.Random( seed.hashCode() );
			float[] mask = new float[ env.actionMask().length ];
			for (int i = 0; i < 400 && env.running(); i++){
				if (env.mode() == EnvMode.WORLD){
					boolean[] cached = Travel.passThrough( Dungeon.level );
					System.arraycopy( env.actionMask(), 0, mask, 0, mask.length );
					Travel.remember();
					boolean[] remembered = Travel.passThrough( Dungeon.level );
					Travel.invalidate();
					boolean[] fresh = Travel.passThrough( Dungeon.level );
					if (!java.util.Arrays.equals( remembered, fresh )){
						fail( seed + " step " + i + ": remembering the gas the hero sees changed the travel map at the same moment" );
						return;
					}
					float[] freshMask = new float[ mask.length ];
					env.mapper().actionMask( env.mode(), freshMask );
					boolean sameMask = true;
					for (Action a : new Action[]{ Action.TRAVEL_STAIRS, Action.TRAVEL_EXPLORE }) sameMask &= mask[ a.index ] == freshMask[ a.index ];
					if (!java.util.Arrays.equals( cached, fresh ) || !sameMask){
						fail( seed + " step " + i + ": " + ( sameMask ? "the cached travel map differs from a fresh one"
								: "a travel action's mask bit differs from one rebuilt on a fresh travel map" ));
						return;
					}
					decisions++;
				}
				float[] zero = new float[ Math.max( env.actionMask().length, Math.max( env.slotMask().length, env.targetMask().length ))];
				int action = Policy.sample( java.util.Arrays.copyOf( zero, env.actionMask().length ), env.actionMask(), rng );
				int slot = Policy.sample( java.util.Arrays.copyOf( zero, env.slotMask().length ), env.slotMask(), rng );
				int target = Policy.sample( java.util.Arrays.copyOf( zero, env.targetMask().length ), env.targetMask(), rng );
				env.step( Action.values()[ action ], env.mode() == EnvMode.TARGETING ? target : slot );
			}
		}
		System.out.println( "  at " + decisions + " decisions over random play the cached travel map and the action mask"
				+ " equal fresh ones" );
	}

	private static SPDEnv fresh( String seed ){
		SPDEnv env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );
		env.reset( seed, HeroClass.WARRIOR );
		return env;
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
