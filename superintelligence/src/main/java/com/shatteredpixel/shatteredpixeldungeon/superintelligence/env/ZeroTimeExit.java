package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;

final class ZeroTimeExit {

	private static final Action[] REOPENERS = { Action.OPEN_INVENTORY, Action.USE, Action.DROP };

	private ZeroTimeExit(){}

	static void mask( float[] mask, boolean anyOffered ){
		for (Action reopener : REOPENERS) mask[ reopener.index ] = anyOffered ? 0f : 1f;
		for (Action move : Action.values()){
			if (move.kind != Action.Kind.WORLD || (move.dx == 0 && move.dy == 0)) continue;
			int cell = Surroundings.offsetCell( move.dx, move.dy );
			if (Surroundings.insideMap( cell ) && Dungeon.level.pit[ cell ] && !Dungeon.level.solid[ cell ]) mask[ move.index ] = 0f;
		}
		boolean left = false;
		for (float m : mask) left |= m > 0.5f;
		if (!left) mask[ Action.WAIT.index ] = 1f;
	}
}
