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

	/**
	 * The window to answer, from whichever slot the game parked it in.
	 *
	 * <p>Reads {@code GameScene.answerableWindow()} rather than the headless slot directly, and that is
	 * the whole fix. {@code GameScene.show} fills the headless slot only when there is no scene, so a
	 * caller reading it directly reported "no dialog" in the rendered viewer no matter what the game had
	 * opened - and a recorded {@code MENU} step, which exists to answer exactly such a dialog, resolved
	 * against nothing there. Headless still sees what it always saw, so the trainer is unchanged.
	 */
	private static Window window(){
		return GameScene.answerableWindow();
	}

	/**
	 * True when a dialog the agent can actually answer is waiting.
	 *
	 * Deliberately narrower than "a window is showing". {@code GameScene.show} parks informational
	 * windows here too - "you cannot leave the dungeon yet" arrives this way and is by far the most
	 * common - and those have no options to choose. Treating them as open put the environment into
	 * MENU with nothing to select, so every such step was an invalid action and the episode sat in
	 * MENU until it stalled.
	 */
	public static boolean open(){
		return window() instanceof WndOptions;
	}

	/** Number of selectable options, or 0 when the window is not a WndOptions. */
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
		//The headless slot is cleared on both paths. With no scene that is the only place the window
		//was ever held; with a scene it was never held and clearing it is a no-op, so this stays
		//unconditional rather than branching on which case applies.
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