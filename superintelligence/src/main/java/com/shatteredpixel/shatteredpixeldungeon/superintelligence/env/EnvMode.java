package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

/**
 * What the agent's next choice means.
 *
 * The environment is a small state machine, which is how research.md's "Context Switching and
 * Menus" requirement is met. Mode is derived from observable game state rather than remembered
 * from history, so a replayed or resumed episode lands in the right mode with no extra state.
 */
public enum EnvMode {

	/** Ordinary dungeon turn. Actions name a cell; items may require an aim. */
	WORLD,

	/**
	 * The game is waiting for a target cell, having been asked to aim by a wand, a throwable or
	 * an armor ability. The next step's slot index is an offset into ActionMapper.TARGET_OFFSETS.
	 */
	TARGETING,

	/** An inventory slot is being chosen, after USE or DROP. The next step's slot index picks it. */
	SLOT,

	/** A dialog is open. The next step's slot index picks one of the window's options. */
	MENU,

	/** The inventory pane is open: equip, unequip and identify rather than explore. */
	INVENTORY;

	/** True when the next step's slot index names a board cell rather than a slot or option. */
	public boolean slotIsTarget(){
		return this == TARGETING;
	}
}