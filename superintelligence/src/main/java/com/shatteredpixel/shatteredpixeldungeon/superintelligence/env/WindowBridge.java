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
		return w instanceof WndOptions || w instanceof WndUpgrade || !buttons( w ).isEmpty();
	}

	private static final java.util.Set< String > CHOICE_WINDOWS = java.util.Set.of( "WndSadGhost", "WndWandmaker", "WndImpOld",
			"WndBlacksmith", "WndChooseSubclass", "WndChooseAbility" );

	private static final java.util.ArrayDeque< Window > parents = new java.util.ArrayDeque<>();

	private static final com.watabou.noosa.Group holding = new com.watabou.noosa.Group();

	private static boolean choice( Window w ){
		for (Class< ? > c = w.getClass(); c != null && c != Window.class; c = c.getEnclosingClass() != null ? c.getEnclosingClass() : c.getSuperclass()){
			if (CHOICE_WINDOWS.contains( c.getSimpleName() )) return true;
		}
		return false;
	}

	private static java.util.List< com.shatteredpixel.shatteredpixeldungeon.ui.Button > buttons( Window w ){
		java.util.List< com.shatteredpixel.shatteredpixeldungeon.ui.Button > buttons = new java.util.ArrayList<>();
		if (w == null || w instanceof WndOptions || !(w instanceof WndTradeItem || choice( w ))) return buttons;
		for (com.watabou.noosa.Gizmo g : w.living( com.watabou.noosa.Gizmo.class )){
			if (g instanceof RedButton && ((RedButton) g).active && g.visible) buttons.add( (RedButton) g );
			if (g instanceof com.shatteredpixel.shatteredpixeldungeon.ui.ItemButton && g.visible && g.active
					&& ((com.shatteredpixel.shatteredpixeldungeon.ui.ItemButton) g).item() != null){
				buttons.add( ((com.shatteredpixel.shatteredpixeldungeon.ui.ItemButton) g).slot() );
			}
		}
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
		return (w instanceof WndOptions) ? ((WndOptions) w).optionCount() : buttons( w ).size();
	}

	public static boolean optionSelectable( int index ){
		Window w = window();
		if (w instanceof WndUpgrade) return ((WndUpgrade) w).optionSelectable( index );
		if (w instanceof WndOptions) return ((WndOptions) w).optionSelectable( index );
		return index >= 0 && index < buttons( w ).size();
	}

public static boolean selectOption( int index ){
		Window w = window();
		if (w instanceof WndUpgrade) return ((WndUpgrade) w).selectOption( index );
		if (w instanceof WndOptions) return ((WndOptions) w).selectOption( index );
		java.util.List< com.shatteredpixel.shatteredpixeldungeon.ui.Button > buttons = buttons( w );
		boolean parked = w != null && w == GameScene.headlessWindow();
		if (index < 0 || index >= buttons.size() || !buttons.get( index ).press()) return false;
		Window shown = GameScene.headlessWindow();
		if (parked && shown != null && shown != w && !destroyed( w )){
			holding.add( w );
			parents.push( w );
		}
		return true;
	}

	private static boolean destroyed( Window w ){
		return w.living( com.watabou.noosa.Gizmo.class ).isEmpty();
	}

	private static void restoreParent(){
		if (GameScene.headlessWindow() != null) return;
		while (!parents.isEmpty()){
			Window parent = parents.pop();
			if (!destroyed( parent )){
				holding.remove( parent );
				GameScene.show( parent );
				return;
			}
		}
	}

	public static void forgetParents(){
		for (Window parent : parents) holding.remove( parent );
		parents.clear();
	}


	public static void dismissUnanswerable(){
		restoreParent();
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