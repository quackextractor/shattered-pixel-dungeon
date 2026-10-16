package com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs;


public enum GridChannel {


	WALL( Kind.VISIBLE ),


	OPAQUE( Kind.VISIBLE ),


	VISIBLE( Kind.VISIBLE ),


	EXPLORED( Kind.MEMORY ),


	FOGGED( Kind.MEMORY ),


	VISIBLE_EDGE( Kind.VISIBLE ),


	ENEMY( Kind.VISIBLE ),


	NEUTRAL( Kind.VISIBLE ),


	ALLY( Kind.VISIBLE ),


	HEAP( Kind.VISIBLE ),


	GROUND_ITEM( Kind.VISIBLE ),


	TRANSITION( Kind.VISIBLE ),


	EXIT_BEACON( Kind.MEMORY ),


	LOCKED_DOOR( Kind.VISIBLE ),


	TRAP_DISARMED( Kind.VISIBLE ),


	TRAP_ACTIVE( Kind.VISIBLE ),


	ALCHEMY( Kind.VISIBLE ),


	HIGH_GRASS( Kind.VISIBLE ),


	CHASM( Kind.VISIBLE ),


	BOULDER( Kind.VISIBLE ),


	HERO( Kind.VISIBLE ),


	THREATENING( Kind.VISIBLE ),


	DEPTH_BIAS( Kind.SCALAR );

	public enum Kind {

		VISIBLE,

		MEMORY,

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


	public static int spatialCount(){
		int n = 0;
		for (GridChannel c : VALUES) if (c.kind != Kind.SCALAR) n++;
		return n;
	}

}