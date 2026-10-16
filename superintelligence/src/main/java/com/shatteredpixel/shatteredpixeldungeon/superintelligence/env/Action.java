package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;


public enum Action {


	MOVE_N  ( Kind.WORLD, 0, -1 ),
	MOVE_NE ( Kind.WORLD, 1, -1 ),
	MOVE_E  ( Kind.WORLD, 1,  0 ),
	MOVE_SE ( Kind.WORLD, 1,  1 ),
	MOVE_S  ( Kind.WORLD, 0,  1 ),
	MOVE_SW ( Kind.WORLD, -1,  1 ),
	MOVE_W  ( Kind.WORLD, -1,  0 ),
	MOVE_NW ( Kind.WORLD, -1, -1 ),

	WAIT    ( Kind.TIME,  0, 0 ),


	REST    ( Kind.TIME,  0, 0 ),


	SEARCH  ( Kind.TIME,  0, 0 ),


	INTERACT( Kind.WORLD, 0, 0 ),


	USE     ( Kind.ITEM,  0, 0 ),


	DROP    ( Kind.ITEM,  0, 0 ),


	OPEN_INVENTORY ( Kind.MENU, 0, 0 ),


	TALENT ( Kind.MENU, 0, 0 ),


	ALCHEMY ( Kind.MENU, 0, 0 ),


	MENU_SELECT ( Kind.MENU, 0, 0 ),


	CANCEL ( Kind.MENU, 0, 0 );

	public enum Kind {

		WORLD,

		TIME,

		ITEM,

		MENU
	}

	public final Kind kind;


	public final int dx, dy;


	public final int index;

	private static final Action[] VALUES = values();

	Action( Kind kind, int dx, int dy ){
		this.kind = kind;
		this.dx = dx;
		this.dy = dy;
		this.index = ordinal();
	}


	public boolean directional(){
		return kind == Kind.WORLD && (dx != 0 || dy != 0);
	}

	public static Action fromIndex( int i ){
		return (i >= 0 && i < VALUES.length) ? VALUES[i] : null;
	}

	public static int size(){
		return VALUES.length;
	}


	@Override
	public String toString(){
		return name();
	}
}