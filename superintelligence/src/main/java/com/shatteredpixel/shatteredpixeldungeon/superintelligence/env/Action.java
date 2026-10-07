package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

/**
 * The primitive actions the agent can choose from.
 *
 * Interaction is a single action rather than nine. Walking into a cell can mean walk, attack,
 * loot, unlock, take stairs or talk to an NPC, and Hero.handle decides which. Exposing that
 * resolution as one action lets the policy learn intent - fight, loot, descend - instead of
 * having to rediscover which HeroAction subtype a given tile implies.
 *
 * Targeting and inventory are separate heads rather than baked-in actions. The environment
 * pauses for them (see EnvMode), which keeps the network's output width independent of how much
 * the hero is carrying, and avoids spending a target prediction on turns that are not aiming.
 *
 * Legality is decided per turn by ActionMapper.actionMask.
 */
public enum Action {

	/**
	 * Eight movement directions. Maps onto SPDAction.N / NE / E / SE / S / SW / W / NW.
	 *
	 * <p><b>{@code dy} is negated relative to a screen row.</b> The convention comes from the game:
	 * {@code Level.pointToCell(p)} is {@code p.x + p.y * width}, and
	 * {@code CellSelector.directionFromAction} returns {@code (0,-1)} for north. So north is a
	 * <i>decrease</i> in row index, which means {@code MOVE_N} carries {@code dy = -1}.
	 *
	 * <p>It carried {@code +1}, and every vertical direction was inverted as a result - {@code MOVE_N}
	 * walked south, {@code MOVE_SE} walked north. The horizontal pair was always right, which is why it
	 * looked plausible: the two axes were written from opposite assumptions and neither was checked
	 * against the game's.
	 *
	 * <p>Consequences, since it went unnoticed for so long. The action <i>space</i> is complete and
	 * every cell is reachable, so an agent trained from scratch still learns to navigate - it just
	 * learns that the action it thinks is north goes south, which is harmless for exploration and
	 * corrosive for anything that reasons about a goal. It made every north/south reading of a recorded
	 * run wrong, including mine: a trace showed {@code MOVE_N} stepping from row 40 to row 42.
	 * {@code actioncheck} now asserts each direction against the game's own convention.
	 */
	MOVE_N  ( Kind.WORLD, 0, -1 ),
	MOVE_NE ( Kind.WORLD, 1, -1 ),
	MOVE_E  ( Kind.WORLD, 1,  0 ),
	MOVE_SE ( Kind.WORLD, 1,  1 ),
	MOVE_S  ( Kind.WORLD, 0,  1 ),
	MOVE_SW ( Kind.WORLD, -1,  1 ),
	MOVE_W  ( Kind.WORLD, -1,  0 ),
	MOVE_NW ( Kind.WORLD, -1, -1 ),

	// TIME actions advance time without naming a cell.

	//Never masked out: the policy must always have at least one legal choice or the softmax
	//normalises NaN, and waiting is genuinely always available to a human player.
	WAIT    ( Kind.TIME,  0, 0 ),

	/** Rest until healed or interrupted. Costs hunger. */
	REST    ( Kind.TIME,  0, 0 ),

	/** Search the current tile. Disarms traps, can find items. */
	SEARCH  ( Kind.TIME,  0, 0 ),

	/** Act on the best adjacent target: mob, heap, door, stairs, NPC, chest, alchemy pot. */
	INTERACT( Kind.WORLD, 0, 0 ),

	/** Use an inventory slot. Enters EnvMode.SLOT, then TARGETING if the item needs an aim. */
	USE     ( Kind.ITEM,  0, 0 ),

	/** Drop an inventory slot onto the current tile. */
	DROP    ( Kind.ITEM,  0, 0 ),

	/** Open the inventory pane, entering EnvMode.INVENTORY. */
	OPEN_INVENTORY ( Kind.MENU, 0, 0 ),

	/** Answer the open dialog with option {@code slot} of the option head. */
	MENU_SELECT ( Kind.MENU, 0, 0 ),

	/** Close the open dialog or the inventory pane. */
	CANCEL ( Kind.MENU, 0, 0 );

	public enum Kind {
		/** Resolved by the game's own cell selector into a HeroAction. */
		WORLD,
		/** Advances time without naming a cell. */
		TIME,
		/** Operates on an inventory slot. */
		ITEM,
		/** Only legal while a dialog or the inventory pane is open. */
		MENU
	}

	public final Kind kind;

	/** Grid offset the action names, relative to the hero. Zero for non-directional actions. */
	public final int dx, dy;

	/** Index into the flat action array. */
	public final int index;

	private static final Action[] VALUES = values();

	Action( Kind kind, int dx, int dy ){
		this.kind = kind;
		this.dx = dx;
		this.dy = dy;
		this.index = ordinal();
	}

	/** True for the eight directional actions, whose cell the direction selects. */
	public boolean directional(){
		return kind == Kind.WORLD && (dx != 0 || dy != 0);
	}

	public static Action fromIndex( int i ){
		return (i >= 0 && i < VALUES.length) ? VALUES[i] : null;
	}

	public static int size(){
		return VALUES.length;
	}

	/** Named rather than indexed in replay files, so reordering cannot invalidate old replays. */
	@Override
	public String toString(){
		return name();
	}
}