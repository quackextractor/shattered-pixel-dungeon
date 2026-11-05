package com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DM100;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Eye;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GnollTrickster;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Necromancer;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Piranha;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Scorpio;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Shaman;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Spinner;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Swarm;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Warlock;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Travel;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public final class Tactics {

	private static final int HALL_REACH = 8;
	private static final int HALL_NEIGHBOURS = 2;
	private static final int DOOR_REACH = 4;
	private static final int DOOR_WAIT = 4;
	private static final int ENGAGE_RANGE = 6;

	private Level danceLevel;
	private final BitSet usedDoors = new BitSet();
	private int door = -1, past = -1, waited;

	public void reset(){
		danceLevel = null;
		usedDoors.clear();
		endDance();
	}

	private void endDance(){
		if (door >= 0) usedDoors.set( door );
		door = past = -1;
		waited = 0;
	}

	private static boolean dodgeable( Mob mob, Hero hero ){
		if (mob.speed() > hero.speed() || mob.flying) return false;
		return !(mob instanceof Shaman || mob instanceof GnollTrickster || mob instanceof DM100 || mob instanceof Eye
				|| mob instanceof Warlock || mob instanceof Scorpio || mob instanceof Spinner || mob instanceof Necromancer
				|| mob instanceof Piranha);
	}

	private void planDance( Level level, Hero hero, Mob mob ){
		boolean[] walk = walkable( level );
		PathFinder.buildDistanceMap( hero.pos, walk, DOOR_REACH );
		int width = level.width();
		int best = Integer.MAX_VALUE;
		for (int c = 0; c < level.length(); c++){
			if (PathFinder.distance[ c ] > DOOR_REACH || !isDoor( level, c ) || usedDoors.get( c )) continue;
			if (PathFinder.distance[ c ] >= best || level.distance( mob.pos, c ) < PathFinder.distance[ c ]) continue;
			for (int d : new int[]{ 1, -1, width, -width }){
				int near = c + d, far = c - d;
				if (near < 0 || far < 0 || near >= level.length() || far >= level.length()) continue;
				if (!walk[ far ] || PathFinder.distance[ near ] != PathFinder.distance[ c ] - 1
						&& near != hero.pos) continue;
				best = PathFinder.distance[ c ];
				door = c;
				past = far;
			}
		}
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

	private Action dance( float[] mask, Hero hero, Level level, Mob mob ){
		if (door < 0){
			if (level.distance( mob.pos, hero.pos ) > ENGAGE_RANGE) return null;
			planDance( level, hero, mob );
			if (door < 0) return null;
		}
		if (hero.pos == past){
			if ((mob != null && level.adjacent( mob.pos, hero.pos )) || ++waited > DOOR_WAIT){
				endDance();
				return null;
			}
			return mask[ Action.WAIT.index ] > 0.5f ? Action.WAIT : null;
		}
		Action step = stepTo( mask, past );
		if (step == null) endDance();
		return step;
	}

	public Action act( float[] mask, boolean canShoot ){
		Hero hero = Dungeon.hero;
		Level level = Dungeon.level;
		if (danceLevel != level){
			reset();
			danceLevel = level;
		}
		for (Mob mob : Dungeon.hero.getVisibleEnemies()){
			if (mob.isAlive() && level.adjacent( mob.pos, hero.pos ) && mob.surprisedBy( hero )){
				Action hit = DescentBot.toward( mask, hero.pos, mob.pos );
				if (hit != null){
					endDance();
					return hit;
				}
			}
		}
		List< Mob > hunters = hunters();
		if (hero.rooted){
			endDance();
			return null;
		}
		if (hunters.isEmpty()){
			if (door < 0) return null;
			return dance( mask, hero, level, null );
		}
		if (surrounding( hunters )){
			endDance();
			if (isHall( level, hero.pos )) return null;
			int hall = nearestHall( level, hero.pos );
			return hall < 0 ? null : stepTo( mask, hall );
		}
		Mob mob = hunters.get( 0 );
		if (canShoot || !dodgeable( mob, hero )){
			endDance();
			return null;
		}
		return dance( mask, hero, level, mob );
	}
}
