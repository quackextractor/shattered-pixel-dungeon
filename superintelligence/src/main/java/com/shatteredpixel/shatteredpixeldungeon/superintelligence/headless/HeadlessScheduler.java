package com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;

import java.util.ArrayDeque;

public final class HeadlessScheduler {

	private static final ArrayDeque< Runnable > parked = new ArrayDeque<>();

	private static boolean stepping;

	private HeadlessScheduler(){}

	public static boolean step(){
		parked.clear();
		boolean wantsMore;
		stepping = true;
		try {
			wantsMore = Actor.headlessStep();
		} finally {
			stepping = false;
		}
		if (parked.isEmpty()) return wantsMore;
		while (!parked.isEmpty()) parked.poll().run();
		return Actor.currentActor() != null;
	}

	static void afterAct( Char ch, Runnable completion ){
		if (stepping && ch instanceof Mob && ch == Actor.currentActor()) parked.add( completion );
		else completion.run();
	}
}
