package com.shatteredpixel.shatteredpixeldungeon.desktop.replay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Array;

final class RenderQueue {

	private static final String GAME_CALLBACK = "com.watabou.noosa.Game$";

	private static boolean warned = false;

	private RenderQueue(){}

	@SuppressWarnings( "unchecked" )
	static boolean gamePending(){
		if (Gdx.app == null) return false;
		try {
			java.lang.reflect.Field field = Gdx.app.getClass().getDeclaredField( "runnables" );
			field.setAccessible( true );
			Array< Runnable > queue = (Array< Runnable >) field.get( Gdx.app );
			synchronized (queue){
				for (Runnable work : queue) if (work.getClass().getName().startsWith( GAME_CALLBACK )) return true;
			}
		} catch (ReflectiveOperationException | RuntimeException unreadable){
			if (!warned){
				warned = true;
				System.err.println( "[WARN] the viewer cannot read " + Gdx.app.getClass().getName() + ".runnables ("
						+ unreadable.getClass().getSimpleName() + "), so it will not wait for a window the game posts to"
						+ " the render thread; issue 48's late Blacksmith window can diverge again" );
			}
			return false;
		}
		return false;
	}
}
