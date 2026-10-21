package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blizzard;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ConfusionGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.CorrosiveGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Electricity;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Freezing;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Inferno;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ParalyticGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.StenchGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.StairPotential;
import com.watabou.utils.PathFinder;

public final class Travel {

	private static final java.util.Set< Class< ? > > HARMFUL = java.util.Set.of( ToxicGas.class, CorrosiveGas.class,
			ConfusionGas.class, ParalyticGas.class, StenchGas.class, Fire.class, Inferno.class, Electricity.class,
			Freezing.class, Blizzard.class );

	private static final float GAS_MEMORY_TURNS = 30f;

	private static Level committedLevel;

	private static Level gasLevel;
	private static float[] gasSeenAt;
	private static int committed = -1;

	private Travel(){}

	public static int target( Action action, boolean commit ){
		if (Dungeon.level == null || Dungeon.hero == null) return -1;
		return action == Action.TRAVEL_STAIRS ? stairs() : frontier( commit );
	}

	public static int step( Action action, boolean commit ){
		int target = target( action, commit );
		if (target < 0 || target == Dungeon.hero.pos) return -1;
		int step = PathFinder.getStep( Dungeon.hero.pos, target, walkable( Dungeon.level ) );
		return step >= 0 && Dungeon.level.adjacent( Dungeon.hero.pos, step ) ? step : -1;
	}

	private static int stairs(){
		Level level = Dungeon.level;
		int exit = StairPotential.exitCell( level );
		return exit >= 0 && known( level, exit ) ? exit : -1;
	}

	private static int frontier( boolean commit ){
		Level level = Dungeon.level;
		int hero = Dungeon.hero.pos;
		PathFinder.buildDistanceMap( hero, walkable( level ) );
		if (committedLevel == level && committed >= 0 && committed != hero
				&& PathFinder.distance[ committed ] != Integer.MAX_VALUE && bordersUnknown( level, committed )){
			return committed;
		}
		int best = -1;
		for (int c = 0; c < level.length(); c++){
			if (c == hero || PathFinder.distance[ c ] == Integer.MAX_VALUE || !bordersUnknown( level, c )) continue;
			if (best < 0 || PathFinder.distance[ c ] < PathFinder.distance[ best ]) best = c;
		}
		if (commit){
			committedLevel = level;
			committed = best;
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

	public static boolean[] walkable( Level level ){
		boolean[] walk = new boolean[ level.length() ];
		for (int c = 0; c < walk.length; c++){
			walk[ c ] = level.passable[ c ] && known( level, c ) && !level.avoid[ c ];
		}
		for (LevelTransition t : level.transitions){
			if (t.type != LevelTransition.Type.REGULAR_EXIT) walk[ t.cell() ] = false;
		}
		for (int c = 0; c < walk.length; c++){
			if (!harmful( level, c ) && !remembered( level, c )) continue;
			walk[ c ] = false;
			for (int d : PathFinder.NEIGHBOURS8){
				if (c + d >= 0 && c + d < walk.length) walk[ c + d ] = false;
			}
		}
		walk[ Dungeon.hero.pos ] = true;
		return walk;
	}

	public static void remember(){
		Level level = Dungeon.level;
		if (level == null) return;
		if (gasLevel != level){
			gasLevel = level;
			gasSeenAt = new float[ level.length() ];
			java.util.Arrays.fill( gasSeenAt, Float.NEGATIVE_INFINITY );
		}
		for (int c = 0; c < gasSeenAt.length; c++){
			if (harmful( level, c )) gasSeenAt[ c ] = Actor.now();
		}
	}

	private static boolean remembered( Level level, int cell ){
		return gasLevel == level && Actor.now() - gasSeenAt[ cell ] < GAS_MEMORY_TURNS;
	}

	public static boolean harmful( Level level, int cell ){
		for (Blob blob : level.blobs.values()){
			if (blob.volume > 0 && HARMFUL.contains( blob.getClass() ) && blob.cur[ cell ] > 0 && level.heroFOV[ cell ]) return true;
		}
		return false;
	}

	public static boolean known( Level level, int cell ){
		return level.visited[ cell ] || level.mapped[ cell ];
	}
}
