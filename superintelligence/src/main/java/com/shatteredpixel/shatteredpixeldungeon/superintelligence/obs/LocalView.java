package com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;

public final class LocalView {

	public static final int RADIUS = 3;
	public static final int CELLS = (2 * RADIUS + 1) * (2 * RADIUS + 1) - 1;

	public static final int WALKABLE = 0;
	public static final int DOOR = 1;
	public static final int HAZARD = 2;
	public static final int ENEMY = 3;
	public static final int ENEMY_HP = 4;
	public static final int ENEMY_ASLEEP = 5;
	public static final int OTHER_CHAR = 6;

	public static final int PER_CELL = 7;
	public static final int SIZE = CELLS * PER_CELL;

	private LocalView(){}

	public static int slot( int dx, int dy ){
		int row = dy + RADIUS, column = dx + RADIUS;
		int index = row * (2 * RADIUS + 1) + column;
		int centre = RADIUS * (2 * RADIUS + 1) + RADIUS;
		return ( index > centre ? index - 1 : index ) * PER_CELL;
	}

	public static void encode( Level level, Hero hero, float[] out, int at ){
		int width = level.width(), height = level.height();
		int hx = hero.pos % width, hy = hero.pos / width;
		for (int dy = -RADIUS; dy <= RADIUS; dy++){
			for (int dx = -RADIUS; dx <= RADIUS; dx++){
				if (dx == 0 && dy == 0) continue;
				int x = hx + dx, y = hy + dy;
				if (x >= 0 && x < width && y >= 0 && y < height) cell( level, hero, x + y * width, out, at + slot( dx, dy ) );
			}
		}
	}

	private static void cell( Level level, Hero hero, int cell, float[] out, int at ){
		boolean visible = cell < level.heroFOV.length && level.heroFOV[ cell ];
		if (!visible && !level.visited[ cell ] && !level.mapped[ cell ]) return;
		int terrain = level.map[ cell ];
		out[ at + WALKABLE ] = level.passable[ cell ] ? 1f : 0f;
		out[ at + DOOR ] = terrain == Terrain.DOOR || terrain == Terrain.OPEN_DOOR ? 1f : 0f;
		if (!visible) return;
		Trap trap = level.traps.get( cell );
		out[ at + HAZARD ] = terrain == Terrain.CHASM || (trap != null && trap.visible && trap.active) ? 1f : 0f;
		Char ch = Actor.findChar( cell );
		if (ch == null || ch == hero) return;
		if (ch.alignment != Char.Alignment.ENEMY){
			out[ at + OTHER_CHAR ] = 1f;
			return;
		}
		out[ at + ENEMY ] = 1f;
		out[ at + ENEMY_HP ] = ch.HT > 0 ? (float) ch.HP / ch.HT : 0f;
		out[ at + ENEMY_ASLEEP ] = ch instanceof Mob && ((Mob) ch).state == ((Mob) ch).SLEEPING ? 1f : 0f;
	}
}
