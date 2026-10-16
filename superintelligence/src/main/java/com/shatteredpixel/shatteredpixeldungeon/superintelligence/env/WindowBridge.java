package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;


public class WindowBridge {

	private WindowBridge() {}


	private static Window window(){
		return GameScene.answerableWindow();
	}


	public static boolean open(){
		return window() instanceof WndOptions;
	}


	public static void age( EnvConfig config ){
		Window w = window();
		if (w == null) return;
		ageSeconds = (config == null) ? 0.2f : config.dialogAgeSeconds;
		w.update();
	}


	public static float ageSeconds = 0.2f;


	public static int optionCount(){
		Window w = window();
		return (w instanceof WndOptions) ? ((WndOptions) w).optionCount() : 0;
	}

	public static boolean optionSelectable( int index ){
		Window w = window();
		return (w instanceof WndOptions) && ((WndOptions) w).optionSelectable( index );
	}

public static boolean selectOption( int index ){
		Window w = window();
		if (!(w instanceof WndOptions)) return false;
		boolean fired = ((WndOptions) w).selectOption( index );

		return fired;
	}


	public static boolean close(){
		if (!open()) return false;
		GameScene.clearHeadlessWindow();
		return true;
	}


	public static String describe(){
		Window w = GameScene.headlessWindow();
		if (w == null) return "none";
		return w.getClass().getSimpleName() + "(" + optionCount() + " options)";
	}
}