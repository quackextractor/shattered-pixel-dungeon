package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.watabou.utils.PathFinder;

final class Chasms {

	private Chasms(){}

	static int pitBeside( int[] beside ){
		for (int c = 0; c < Dungeon.level.length(); c++){
			if (!Dungeon.level.pit[c] || Dungeon.level.solid[c]) continue;
			for (int d : PathFinder.NEIGHBOURS8){
				int n = c + d;
				if (n < 0 || n >= Dungeon.level.length()) continue;
				if (!Dungeon.level.insideMap(n)) continue;
				if (!Dungeon.level.passable[n] || Dungeon.level.pit[n] || Dungeon.level.solid[n]) continue;
				if (Actor.findChar(n) != null) continue;
				beside[0] = n;
				return c;
			}
		}
		return -1;
	}

	static Action toward( int from, int to ){
		for (Action a : Action.values()){
			if (a.directional() && from + a.dx + a.dy * Dungeon.level.width() == to) return a;
		}
		return null;
	}
}
