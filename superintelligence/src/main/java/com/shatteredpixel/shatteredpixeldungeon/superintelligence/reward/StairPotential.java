package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

public final class StairPotential {

	public static final int UNREACHABLE = Integer.MAX_VALUE;

	private int levelId = -1;
	private int[] distance = new int[ 0 ];
	private int width;
	private int height;

	public void reset(){
		levelId = -1;
		distance = new int[ 0 ];
	}

	public int distanceFrom( int from ){
		Level level = Dungeon.level;
		if (level == null || from < 0 || from >= level.length()) return UNREACHABLE;

		int exit = exitCell( level );
		if (exit < 0) return UNREACHABLE;

		ensureField( level, exit );

		if (from >= distance.length) return UNREACHABLE;
		int d = distance[ from ];
		return d < 0 ? UNREACHABLE : d;
	}

	public float potential( int from ){
		int d = distanceFrom( from );
		return d == UNREACHABLE ? 0f : -d;
	}

	public float shaping( float gamma, int from, int to ){
		int a = distanceFrom( from );
		int b = distanceFrom( to );
		if (a == UNREACHABLE || b == UNREACHABLE) return 0f;
		return gamma * ( -b ) - ( -a );
	}

	private int exitCell( Level level ){
		if (level.getTransition( null ) == null) return -1;
		return level.getTransition( null ).cell();
	}

	private void ensureField( Level level, int exit ){
		if (levelId == System.identityHashCode( level )
				&& width == level.width()
				&& height == level.height()
				&& distance.length == level.length()){
			return;
		}

		levelId = System.identityHashCode( level );
		width = level.width();
		height = level.height();
		distance = new int[ level.length() ];
		java.util.Arrays.fill( distance, -1 );

		int[] queue = new int[ level.length() ];
		int head = 0;
		int tail = 0;

		if (exit < 0 || exit >= level.length() || !level.passable[ exit ]){
			if (exit < 0 || exit >= level.length()) return;
		}

		distance[ exit ] = 0;
		queue[ tail++ ] = exit;

		while (head < tail){
			int cell = queue[ head++ ];
			int next = distance[ cell ] + 1;

			int cx = cell % width;
			int cy = cell / width;

			for (int dy = -1; dy <= 1; dy++){
				for (int dx = -1; dx <= 1; dx++){
					if (dx == 0 && dy == 0) continue;
					int nx = cx + dx;
					int ny = cy + dy;
					if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;

					int neighbour = nx + ny * width;
					if (distance[ neighbour ] >= 0) continue;
					if (!level.passable[ neighbour ]) continue;
					if (level.map[ neighbour ] == com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.CHASM){
						continue;
					}

					distance[ neighbour ] = next;
					queue[ tail++ ] = neighbour;
				}
			}
		}
	}
}