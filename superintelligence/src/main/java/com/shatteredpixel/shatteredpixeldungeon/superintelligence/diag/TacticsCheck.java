package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Swarm;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.DescentBot;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.Tactics;
import com.watabou.utils.PathFinder;

public class TacticsCheck {

	private static final int CHECKS = 1;
	private static final String[] SEEDS = { "TACT-A", "TACT-B", "TACT-C", "TACT-D", "TACT-E", "TACT-F", "TACT-G", "TACT-H", "TACT-I", "TACT-J" };

	private static SPDEnv env;

	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "tacticscheck" ) );
		HeadlessServices.disableSaving( true );
		env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );

		checkASwarmIsFoughtInAHall();

		System.exit( GateReport.report( "combat tactics", CHECKS ) );
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
