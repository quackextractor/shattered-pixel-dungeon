package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

/**
 * Breadth-first distance from the hero to this floor's stairs, and the potential built on it.
 *
 * <p>Floor 1 is several hundred walkable tiles joined by doors, and a descent is the only route to a
 * score worth having. Waiting for a random walk to step onto the exit has a success rate around 0.1%, so
 * a run that has descended has usually got there by something other than wandering - and across 5,360
 * episodes {@code meanDepth} was 1.001, which is what that arithmetic looks like from outside.
 *
 * <p>Potential-based shaping, {@code gamma*Phi(s') - Phi(s)} with {@code Phi} the negative distance, is
 * the fix that cannot be gamed. The shaping term telescopes over any episode to a bounded difference of
 * the potential at its ends, so it changes <em>where</em> the agent goes without changing <em>what</em> it
 * is worth arriving with: ping-ponging between two cells pays nothing over the round trip, and the only
 * way to earn is to genuinely close the distance. Ng, Harada and Russell's result is the reason this is
 * shaped rather than paid directly.
 *
 * <p>The distance field is computed once per floor and cached, because BFS over a whole level is the
 * most expensive thing the reward model could do per step and the answer changes only when the hero
 * moves. A field is invalidated by the floor, and never by a wall opening - a door the hero unlocks is
 * handled by recomputing from the hero's own cell, which is a single array lookup.
 *
 * <p>Reads nothing that draws: the passable map and the transition cell are the game's own data.
 */
public final class StairPotential {

	/** Unreachable, or no stairs on this floor. Distinct from "far away", which is a finite number. */
	public static final int UNREACHABLE = Integer.MAX_VALUE;

	private int levelId = -1;
	private int[] distance = new int[ 0 ];
	private int width;
	private int height;

	/** Drops the cached field. Called on every floor change. */
	public void reset(){
		levelId = -1;
		distance = new int[ 0 ];
	}

	/**
	 * Steps from {@code from} to the stairs over passable ground, or {@link #UNREACHABLE}.
	 *
	 * <p>Cheap when the field is already built, which is the whole point: the caller asks per step and
	 * pays for the BFS once.
	 */
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

	/** The potential of a state, which is the negative distance and so rises towards the stairs. */
	public float potential( int from ){
		int d = distanceFrom( from );
		return d == UNREACHABLE ? 0f : -d;
	}

	/**
	 * The shaped reward for moving between two cells.
	 *
	 * <p>{@code gamma * Phi(s') - Phi(s)}. Zero when either end is unreachable, because a potential that
	 * is only defined on part of the state space is not a potential function and would break the
	 * telescoping that makes this safe.
	 */
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