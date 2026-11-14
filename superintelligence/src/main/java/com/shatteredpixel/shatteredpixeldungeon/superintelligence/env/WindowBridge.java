package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndTradeItem;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndUpgrade;


public class WindowBridge {

	private WindowBridge() {}


	private static Window window(){
		return GameScene.answerableWindow();
	}


	public static boolean open(){
		Window w = window();
		return w instanceof WndOptions || w instanceof WndUpgrade || !tradeButtons( w ).isEmpty();
	}

	private static java.util.List< RedButton > tradeButtons( Window w ){
		java.util.List< RedButton > buttons = new java.util.ArrayList<>();
		if (!(w instanceof WndTradeItem)) return buttons;
		for (RedButton button : w.living( RedButton.class )) if (button.active && button.visible) buttons.add( button );
		return buttons;
	}


	public static void age( EnvConfig config ){
		Window w = window();
		if (w == null) return;
		ageSeconds = (config == null) ? 0.25f : config.dialogAgeSeconds;
		float elapsed = com.watabou.noosa.Game.elapsed;
		com.watabou.noosa.Game.elapsed = ageSeconds;
		try {
			w.update();
		} finally {
			com.watabou.noosa.Game.elapsed = elapsed;
		}
	}


	public static float ageSeconds = 0.25f;


	public static int optionCount(){
		Window w = window();
		if (w instanceof WndUpgrade) return 2;
		if (w instanceof WndTradeItem) return tradeButtons( w ).size();
		return (w instanceof WndOptions) ? ((WndOptions) w).optionCount() : 0;
	}

	public static boolean optionSelectable( int index ){
		Window w = window();
		if (w instanceof WndUpgrade) return ((WndUpgrade) w).optionSelectable( index );
		if (w instanceof WndTradeItem) return index >= 0 && index < tradeButtons( w ).size();
		return (w instanceof WndOptions) && ((WndOptions) w).optionSelectable( index );
	}

public static boolean selectOption( int index ){
		Window w = window();
		if (w instanceof WndUpgrade) return ((WndUpgrade) w).selectOption( index );
		if (w instanceof WndTradeItem){
			java.util.List< RedButton > buttons = tradeButtons( w );
			return index >= 0 && index < buttons.size() && buttons.get( index ).press();
		}
		if (!(w instanceof WndOptions)) return false;
		return ((WndOptions) w).selectOption( index );
	}


	public static void dismissUnanswerable(){
		Window w = window();
		if (w == null || open() || GameScene.answerableSelector() != null) return;
		w.onBackPressed();
		if (window() == w) w.hide();
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