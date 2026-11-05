package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Crab;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Gnoll;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Goo;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Swarm;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.DescentBot;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.Tactics;
import com.watabou.utils.PathFinder;

public class TacticsCheck {

	private static final int CHECKS = 6;
	private static final String[] SEEDS = { "TACT-A", "TACT-B", "TACT-C", "TACT-D", "TACT-E", "TACT-F", "TACT-G", "TACT-H", "TACT-I", "TACT-J" };

	private static SPDEnv env;

	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "tacticscheck" ) );
		HeadlessServices.disableSaving( true );
		env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );

		checkASwarmIsFoughtInAHall();
		checkADoorBuysASurpriseAttack();
		checkAFastEnemyIsNotDanced();
		checkAChargeIsDodged();
		checkGooIsLuredOffWater();
		checkTheTeacherRestsBeforeABoss();

		System.exit( GateReport.report( "combat tactics", CHECKS ) );
	}

	private static int[] doorway(){
		Level level = Dungeon.level;
		int width = level.width();
		for (int c = 0; c < level.length(); c++){
			if (!Tactics.isDoor( level, c ) || !level.insideMap( c )) continue;
			for (int d : new int[]{ 1, -1, width, -width }){
				int a = c + d, b = c + 2 * d, m = c + 3 * d, past = c - d;
				if (past < 0 || m < 0 || m >= level.length()) continue;
				if (level.passable[ a ] && level.passable[ b ] && level.passable[ m ] && level.passable[ past ]
						&& Actor.findChar( a ) == null && Actor.findChar( m ) == null && Actor.findChar( past ) == null){
					return new int[]{ c, a, m, past };
				}
			}
		}
		return null;
	}

	private static int[] stage( HeroClass heroClass ){
		for (String seed : SEEDS){
			env.reset( seed, heroClass );
			int[] door = doorway();
			if (door == null) continue;
			java.util.Arrays.fill( Dungeon.level.visited, true );
			Dungeon.hero.belongings.backpack.items.removeIf( i -> i instanceof com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.MissileWeapon );
			Dungeon.hero.pos = door[ 1 ];
			Dungeon.observe();
			return door;
		}
		GateReport.fail( "no seed of " + SEEDS.length + " has a clear doorway on floor 1" );
		return null;
	}

	private static void checkADoorBuysASurpriseAttack(){
		int[] door = stage( HeroClass.WARRIOR );
		if (door == null) return;
		placeHunting( new Gnoll(), door[ 2 ] );
		int before = Statistics.sneakAttacks;
		DescentBot bot = new DescentBot();
		int[] secondary = new int[ 1 ];
		for (int i = 0; i < 14 && env.running() && Statistics.sneakAttacks == before; i++){
			Dungeon.hero.HP = Dungeon.hero.HT;
			env.step( bot.act( env, secondary ), secondary[ 0 ] );
		}
		if (Statistics.sneakAttacks == before) GateReport.fail( "a gnoll chased the hero with a door beside it and no surprise attack landed in 14 steps; hero at " + Dungeon.hero.pos + " door " + door[ 0 ] );
	}

	private static void checkAFastEnemyIsNotDanced(){
		int[] door = stage( HeroClass.WARRIOR );
		if (door == null) return;
		Crab crab = new Crab();
		placeHunting( crab, door[ 2 ] );
		Action first = null;
		DescentBot bot = new DescentBot();
		int[] secondary = new int[ 1 ];
		int start = Dungeon.hero.pos;
		for (int i = 0; i < 6 && env.running() && !Dungeon.level.adjacent( crab.pos, Dungeon.hero.pos ); i++) env.step( bot.act( env, secondary ), secondary[ 0 ] );
		first = bot.act( env, secondary );
		if (!first.directional() || crab.pos != Dungeon.hero.pos + first.dx + first.dy * Dungeon.level.width()){
			GateReport.fail( "a crab moves twice a turn and the teacher chose " + first + " beside it instead of hitting it; hero left " + start );
		}
	}

	private static void placeHunting( Mob mob, int cell ){
		mob.pos = cell;
		mob.state = mob.HUNTING;
		GameScene.add( mob, 0f );
		Dungeon.level.occupyCell( mob );
		Dungeon.observe();
	}

	private static int openCellInView( int least ){
		int hero = Dungeon.hero.pos;
		for (int c = 0; c < Dungeon.level.length(); c++){
			if (Dungeon.level.heroFOV[ c ] && Dungeon.level.passable[ c ] && Actor.findChar( c ) == null
					&& Dungeon.level.distance( hero, c ) >= least) return c;
		}
		return -1;
	}

	private static int hallWithin( int steps ){
		Level level = Dungeon.level;
		PathFinder.buildDistanceMap( Dungeon.hero.pos, level.passable, steps );
		for (int c = 0; c < level.length(); c++){
			if (PathFinder.distance[ c ] <= steps && level.visited[ c ] && Tactics.isHall( level, c )) return c;
		}
		return -1;
	}

	private static Goo gooBesideHero( int cellsAway ){
		for (String seed : SEEDS){
			env.reset( seed, HeroClass.WARRIOR );
			Dungeon.hero.belongings.backpack.items.removeIf( i -> i instanceof com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.MissileWeapon );
			int hero = Dungeon.hero.pos;
			for (int c = 0; c < Dungeon.level.length(); c++){
				if (Dungeon.level.heroFOV[ c ] && Dungeon.level.passable[ c ] && Actor.findChar( c ) == null
						&& Dungeon.level.distance( hero, c ) == cellsAway && !Tactics.isDoor( Dungeon.level, c )){
					Goo goo = new Goo();
					placeHunting( goo, c );
					return goo;
				}
			}
		}
		GateReport.fail( "no seed has a free cell " + cellsAway + " from the hero, so Goo was not tested" );
		return null;
	}

	private static void checkAChargeIsDodged(){
		Goo goo = gooBesideHero( 1 );
		if (goo == null) return;
		try {
			java.lang.reflect.Field pumped = Goo.class.getDeclaredField( "pumpedUp" );
			pumped.setAccessible( true );
			pumped.setInt( goo, 1 );
		} catch (ReflectiveOperationException e){
			GateReport.fail( "pumpedUp could not be set: " + e );
			return;
		}
		env.step( Action.WAIT, 0 );
		if (goo.pumpedUp() == 0) return;
		int before = Dungeon.level.distance( goo.pos, Dungeon.hero.pos );
		Action action = new DescentBot().act( env, new int[ 1 ] );
		int target = Dungeon.hero.pos + action.dx + action.dy * Dungeon.level.width();
		if (!action.directional() || target == goo.pos || Dungeon.level.distance( goo.pos, target ) <= before){
			GateReport.fail( "Goo is charging a triple-damage hit and the teacher chose " + action + " at distance " + before );
		}
	}

	private static void checkGooIsLuredOffWater(){
		Goo goo = gooBesideHero( 1 );
		if (goo == null) return;
		Dungeon.level.water[ goo.pos ] = true;
		goo.HP = goo.HT - 20;
		env.step( Action.WAIT, 0 );
		Action action = new DescentBot().act( env, new int[ 1 ] );
		Dungeon.level.water[ goo.pos ] = false;
		int target = Dungeon.hero.pos + action.dx + action.dy * Dungeon.level.width();
		if (!action.directional() || target == goo.pos){
			GateReport.fail( "Goo stands on water healing 1 HP a turn and the teacher chose " + action + " instead of drawing it off" );
		}
	}

	private static Action teacherAt( int depth ){
		env.reset( SEEDS[ 0 ], HeroClass.WARRIOR );
		Dungeon.hero.belongings.backpack.items.removeIf( i -> i instanceof com.shatteredpixel.shatteredpixeldungeon.items.food.Food );
		java.util.Arrays.fill( Dungeon.level.visited, true );
		for (int c = 0; c < Dungeon.level.length(); c++) Dungeon.level.mapped[ c ] = true;
		Dungeon.level.heaps.clear();
		Dungeon.hero.HP = Dungeon.hero.HT * 8 / 10;
		int was = Dungeon.depth;
		env.step( Action.WAIT, 0 );
		Dungeon.depth = depth;
		Action action = new DescentBot().act( env, new int[ 1 ] );
		Dungeon.depth = was;
		return action;
	}

	private static void checkTheTeacherRestsBeforeABoss(){
		Action ordinary = teacherAt( 1 );
		Action boss = teacherAt( 4 );
		if (ordinary == Action.REST) GateReport.fail( "at 80% health on an ordinary floor the teacher rested with the stairs known" );
		if (boss != Action.REST) GateReport.fail( "at 80% health with Goo below the teacher chose " + boss + "; the floor seals once Goo wakes" );
	}

	private static void checkASwarmIsFoughtInAHall(){
		for (String seed : SEEDS){
			env.reset( seed, HeroClass.WARRIOR );
			if (Tactics.isHall( Dungeon.level, Dungeon.hero.pos ) || hallWithin( 8 ) < 0) continue;
			int cell = openCellInView( 3 );
			if (cell < 0) continue;
			Swarm swarm = new Swarm();
			placeHunting( swarm, cell );
			DescentBot bot = new DescentBot();
			int[] secondary = new int[ 1 ];
			for (int i = 0; i < 14 && env.running(); i++){
				Dungeon.hero.HP = Dungeon.hero.HT;
				if (Tactics.isHall( Dungeon.level, Dungeon.hero.pos )) return;
				env.step( bot.act( env, secondary ), secondary[ 0 ] );
			}
			GateReport.fail( seed + ": a swarm hunted the hero 3 cells away with a hall in reach and it stayed in the open" );
			return;
		}
		GateReport.fail( "no seed of " + SEEDS.length + " starts in a room with a hall in reach, so swarms were not tested" );
	}
}
