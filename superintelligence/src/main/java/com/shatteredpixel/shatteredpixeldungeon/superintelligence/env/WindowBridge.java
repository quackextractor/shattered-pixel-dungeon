package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;

/**
 * Drives dialogs that the game opened through {@code GameScene.show()}.
 *
 * Shops, blacksmiths, the resurrection prompt and assorted quest dialogs all reach the UI the same
 * way, so when there is no scene they all land in the same place. Answering them here rather than
 * in {@link SPDEnv} keeps the environment's step loop free of window-specific branching.
 *
 * Only {@link WndOptions} is answerable. Anything else is reported as unanswerable and the
 * environment treats the episode as stalled, which is the correct outcome: silently ignoring an
 * unanswerable dialog would hide a real gap in coverage.
 */
public class WindowBridge {

	private WindowBridge() {}

	public static boolean open(){
		return GameScene.headlessWindow() != null;
	}

	/** Number of selectable options, or 0 when the window is not a WndOptions. */
	public static int optionCount(){
		Window w = GameScene.headlessWindow();
		return (w instanceof WndOptions) ? ((WndOptions) w).optionCount() : 0;
	}

	public static boolean optionSelectable( int index ){
		Window w = GameScene.headlessWindow();
		return (w instanceof WndOptions) && ((WndOptions) w).optionSelectable( index );
	}

	public static boolean selectOption( int index ){
		Window w = GameScene.headlessWindow();
		if (!(w instanceof WndOptions)) return false;
		boolean fired = ((WndOptions) w).selectOption( index );
		if (fired) GameScene.clearHeadlessWindow();
		return fired;
	}

	/**
	 * Closes the window without picking an option.
	 *
	 * WndOptions buttons hide themselves on click, so the option body is not reached here; this
	 * simply drops the pending window, which is the same end state for every dialog that matters.
	 */
	public static boolean close(){
		if (!open()) return false;
		GameScene.clearHeadlessWindow();
		return true;
	}

	/** Simple class name of the pending window, for diagnostics. */
	public static String describe(){
		Window w = GameScene.headlessWindow();
		if (w == null) return "none";
		return w.getClass().getSimpleName() + "(" + optionCount() + " options)";
	}
}