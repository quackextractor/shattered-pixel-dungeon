package com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs;

/**
 * The spatial observation planes fed to the CNN.
 *
 * research.md: "Instead of feeding it raw pixels, you feed it a multi channel matrix of the exact
 * data you pulled from FogOfWar.java and the level state. Channel 1 could be walls, Channel 2
 * enemies, Channel 3 items, and so on."
 *
 * Two families of channel are kept apart on purpose.
 *
 * <b>Visible channels</b> are gated on the hero's field of view, which is exactly what a player
 * sees. This is the whole of the partial observability: the agent cannot see a room it has not
 * entered. Terrain behind a wall is reported as unseen rather than as its real value, so the
 * policy cannot cheat by reading terrain through walls.
 *
 * <b>Memory channels</b> report which tiles the hero has <i>ever</i> seen. Fog of war in this game
 * is persistent - Level.visited and Level.mapped are never cleared - so a human remembers the
 * layout of a room they walked out of. Without this the LSTM would have to rediscover the map from
 * scratch on every visit, which is precisely the failure mode research.md's memory layer is meant
 * to avoid.
 *
 * Ordering is fixed and part of the network's input contract. Channels are appended in this
 * order; appending a new one is safe, reordering is not.
 */
public enum GridChannel {

	/** 1 where the tile blocks movement, from Level.passable. */
	WALL( Kind.VISIBLE ),

	/** 1 where the tile blocks line of sight, from Level.losBlocking. */
	OPAQUE( Kind.VISIBLE ),

	/** 1 where a tile is inside the hero's current field of view, Level.heroFOV. */
	VISIBLE( Kind.VISIBLE ),

	/** 1 where the hero has ever seen the tile, Level.visited. */
	EXPLORED( Kind.MEMORY ),

	/** 1 where the hero has seen a tile but it is not currently visible. */
	FOGGED( Kind.MEMORY ),

	/** Adjacency-preserving edge between visible passable tiles. Gives the CNN wall/door shape. */
	VISIBLE_EDGE( Kind.VISIBLE ),

	/** Hostile mob currently visible. */
	ENEMY( Kind.VISIBLE ),

	/** Neutral mob currently visible - NPCs and mimics. */
	NEUTRAL( Kind.VISIBLE ),

	/** Ally currently visible. */
	ALLY( Kind.VISIBLE ),

	/** Item heap currently visible. */
	HEAP( Kind.VISIBLE ),

	/** Ground item stack, eg. a dropped weapon or an uncollected corpse item. */
	GROUND_ITEM( Kind.VISIBLE ),

	/** Stairs or another level transition currently visible. */
	TRANSITION( Kind.VISIBLE ),

	/** Locked door or locked exit currently visible. */
	LOCKED_DOOR( Kind.VISIBLE ),

	/** Known visible trap that has already been triggered. */
	TRAP_DISARMED( Kind.VISIBLE ),

	/** Known visible trap that has not been triggered. */
	TRAP_ACTIVE( Kind.VISIBLE ),

	/** Alchemy pot. */
	ALCHEMY( Kind.VISIBLE ),

	/** High grass, which hides the hero. */
	HIGH_GRASS( Kind.VISIBLE ),

	/** Chasm, which kills on entry. */
	CHASM( Kind.VISIBLE ),

	/** Pushable boulder or similar. */
	BOULDER( Kind.VISIBLE ),

	/** 1 on the hero's own tile. Always exactly one per observation. */
	HERO( Kind.VISIBLE ),

	/** 1 where a visible hostile mob is currently aiming at the hero. */
	THREATENING( Kind.VISIBLE ),

	/** Depth-scaled sweep, 0 on floor 1 up to 1 on the final floor. Gives the CNN floor identity. */
	DEPTH_BIAS( Kind.SCALAR );

	public enum Kind {
		/** Gated on the current field of view. */
		VISIBLE,
		/** Reports remembered terrain the hero is not currently seeing. */
		MEMORY,
		/** Not spatial; a single value broadcast across the grid. */
		SCALAR
	}

	public final Kind kind;

	GridChannel( Kind kind ){
		this.kind = kind;
	}

	private static final GridChannel[] VALUES = values();

	public static int count(){
		return VALUES.length;
	}

	public static GridChannel at( int i ){
		return VALUES[ i ];
	}

	/** Spatial channel count. The DEPTH_BIAS plane is not fed to the convolution stack. */
	public static int spatialCount(){
		int n = 0;
		for (GridChannel c : VALUES) if (c.kind != Kind.SCALAR) n++;
		return n;
	}

}