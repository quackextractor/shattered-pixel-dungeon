package com.shatteredpixel.shatteredpixeldungeon.desktop.replay;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Array;

final class RenderQueue {

	private static final String GAME_CALLBACK = "com.watabou.noosa.Game$";

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
		} catch (ReflectiveOperationException | RuntimeException notLwjgl){
			return false;
		}
		return false;
	}
}
