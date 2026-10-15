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

	/**
	 * Ages the parked dialog by one frame.
	 *
	 * <p>A dialog the game opened is on screen for as long as the player looks at it, and some of them
	 * refuse to do anything until they have been up a while. {@code Chasm}'s "jump?" prompt is the one
	 * that matters here: its {@code onSelect} and its {@code hide} are both gated on an {@code elapsed}
	 * counter it increments in its own {@code update()}, and {@code PixelScene.update()} is the only
	 * thing that ever calls it. Headlessly there is no {@code PixelScene}, so the counter never moved,
	 * the option never fired, and the environment was offered the game's own question and then had the
	 * answer thrown away by the game's own code - measured: option 0 pressed, {@code jumpConfirmed}
	 * still false, hero still standing where he was.
	 *
	 * <p>So this calls it, <b>once per environment step, which is once per frame</b> - the same
	 * approximation {@code ReplayPlayer} already makes, where one recorded step is at most one rendered
	 * frame. The gate is therefore the game's own 0.2 s rather than something invented here, and the
	 * price is the game's own: the world is frozen behind a modal dialog, so those steps are decisions
	 * that change nothing. {@code Game.elapsed} is 1/60 headlessly, so a chasm costs about twelve of
	 * them.
	 *
	 * <p>Uniform rather than special-cased for the chasm. Every dialog the game parks is aged the same
	 * way, and the ones with no timing guard simply accrue a counter nothing reads.
	 */
	public static void age(){
		Window w = window();
		if (w == null) return;
		w.update();
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

		return fired;
	}

	/**
	 * Closes the window without picking an option.
	 *
	 * The stand-in for {@code GameScene.cancel()}, which is what a rendered game does to a dialog the
	 * player backs out of. Nothing the game itself does here, so this is the one place the slot is
	 * emptied on the caller's behalf.
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