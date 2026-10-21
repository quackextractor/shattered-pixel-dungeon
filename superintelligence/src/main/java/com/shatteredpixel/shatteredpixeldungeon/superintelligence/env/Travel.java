package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.StairPotential;
import com.watabou.utils.PathFinder;

public final class Travel {

	private Travel(){}

	public static int target( Action action ){
		if (Dungeon.level == null || Dungeon.hero == null) return -1;
		return action == Action.TRAVEL_STAIRS ? stairs() : frontier();
	}

	public static int step( Action action ){
		int target = target( action );
		if (target < 0 || target == Dungeon.hero.pos) return -1;
		int step = PathFinder.getStep( Dungeon.hero.pos, target, walkable( Dungeon.level ) );
		return step >= 0 && Dungeon.level.adjacent( Dungeon.hero.pos, step ) ? step : -1;
	}

	private static int stairs(){
		Level level = Dungeon.level;
		int exit = StairPotential.exitCell( level );
		return exit >= 0 && known( level, exit ) ? exit : -1;
	}

	private static int frontier(){
		Level level = Dungeon.level;
		int hero = Dungeon.hero.pos;
		PathFinder.buildDistanceMap( hero, walkable( level ) );
		int best = -1;
		for (int c = 0; c < level.length(); c++){
			if (c == hero || PathFinder.distance[ c ] == Integer.MAX_VALUE || !bordersUnknown( level, c )) continue;
			if (best < 0 || PathFinder.distance[ c ] < PathFinder.distance[ best ]) best = c;
		}
		return best;
	}

	private static boolean bordersUnknown( Level level, int cell ){
		for (int d : PathFinder.NEIGHBOURS8){
			int n = cell + d;
			if (n >= 0 && n < level.length() && !known( level, n )) return true;
		}
		return false;
	}

	private static boolean[] walkable( Level level ){
		boolean[] walk = new boolean[ level.length() ];
		for (int c = 0; c < walk.length; c++){
			walk[ c ] = level.passable[ c ] && known( level, c ) && !level.avoid[ c ];
		}
		walk[ Dungeon.hero.pos ] = true;
		return walk;
	}

	private static boolean known( Level level, int cell ){
		return level.visited[ cell ] || level.mapped[ cell ];
	}
}
