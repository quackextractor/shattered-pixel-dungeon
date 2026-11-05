package com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Swarm;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Travel;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;

public final class Tactics {

	private static final int HALL_REACH = 8;
	private static final int HALL_NEIGHBOURS = 2;

	public void reset(){
	}

	public static boolean isDoor( Level level, int cell ){
		return level.map[ cell ] == Terrain.DOOR || level.map[ cell ] == Terrain.OPEN_DOOR;
	}

	public static boolean isHall( Level level, int cell ){
		if (isDoor( level, cell )) return true;
		int open = 0;
		for (int d : PathFinder.NEIGHBOURS8){
			int n = cell + d;
			if (n >= 0 && n < level.length() && level.passable[ n ]) open++;
		}
		return open <= HALL_NEIGHBOURS;
	}

	private static List< Mob > hunters(){
		List< Mob > found = new ArrayList<>();
		for (Mob mob : Dungeon.hero.getVisibleEnemies()){
			if (mob.isAlive() && mob.state == mob.HUNTING) found.add( mob );
		}
		return found;
	}

	private static boolean[] walkable( Level level ){
		boolean[] walk = Travel.passThrough( level );
		for (Mob mob : level.mobs) walk[ mob.pos ] = false;
		return walk;
	}

	private static Action stepTo( float[] mask, int cell ){
		Hero hero = Dungeon.hero;
		Level level = Dungeon.level;
		int step = PathFinder.getStep( hero.pos, cell, walkable( level ) );
		if (step < 0 || Actor.findChar( step ) != null) return null;
		return DescentBot.toward( mask, hero.pos, step );
	}

	private static int nearestHall( Level level, int from ){
		PathFinder.buildDistanceMap( from, walkable( level ), HALL_REACH );
		int best = -1;
		for (int c = 0; c < level.length(); c++){
			if (c == from || PathFinder.distance[ c ] > HALL_REACH || !isHall( level, c )) continue;
			if (best < 0 || PathFinder.distance[ c ] < PathFinder.distance[ best ]) best = c;
		}
		return best;
	}

	private static boolean surrounding( List< Mob > hunters ){
		if (hunters.size() >= 2) return true;
		for (Mob mob : hunters) if (mob instanceof Swarm) return true;
		return false;
	}

	public Action act( float[] mask ){
		Hero hero = Dungeon.hero;
		Level level = Dungeon.level;
		if (hero.rooted) return null;
		List< Mob > hunters = hunters();
		if (hunters.isEmpty() || !surrounding( hunters ) || isHall( level, hero.pos )) return null;
		int hall = nearestHall( level, hero.pos );
		return hall < 0 ? null : stepTo( mask, hall );
	}
}
