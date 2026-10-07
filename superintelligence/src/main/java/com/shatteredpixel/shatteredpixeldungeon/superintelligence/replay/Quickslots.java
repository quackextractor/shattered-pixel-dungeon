package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;

/**
 * Captures and restores {@link Dungeon#quickslot} bindings.
 *
 * <p>A recorded slot index is not self-describing. {@code ActionMapper.refreshSlots} puts every
 * quickslot-bound item into the slot it is bound to, and only then fills the remaining slots from the
 * backpack in order. So the item a slot index names depends on the bindings at the moment the index was
 * chosen, and the same index can name different items in two environments that agree on everything else.
 *
 * <p>This was not hypothetical. A recorded {@code DROP} on slot 0 resolved to the VelvetPouch in the
 * trainer and to the Waterskin in the viewer, because the trainer had the Waterskin bound to quickslot
 * 1 and the viewer had no bindings at all. The two dropped different items; dropping the bag changed
 * {@code Belongings.Backpack.capacity} from 21 to 20, and every later {@code INTERACT} resolved against a
 * different inventory.
 *
 * <p>Bindings are recorded per step rather than once per run because they change during a run -
 * equipping or unequipping rebinds a slot. Restoring is likewise done immediately before each recorded
 * step, from that step's own capture.
 *
 * <p>Items are named by class, which is enough because {@link Item} instances of one class are
 * interchangeable for this purpose: what matters is which class holds a given slot, since that is what
 * decides what a slot index resolves to.
 */
public class Quickslots {

	private static final char PAIR = ',';
	private static final char LINK = ':';

	/** Quickslot bindings for the current hero, as {@code ItemClass:slot} pairs joined by commas. */
	public static String capture(){
		if (Dungeon.hero == null) return "";
		StringBuilder sb = new StringBuilder();
		for (Item item : Dungeon.hero.belongings.backpack){
			int slot = Dungeon.quickslot.getSlot( item );
			if (slot < 0) continue;
			if (sb.length() > 0) sb.append( PAIR );
			sb.append( item.getClass().getSimpleName() ).append( LINK ).append( slot );
		}
		return sb.toString();
	}

	/**
	 * Replaces the hero's quickslot bindings with those captured in {@code encoded}.
	 *
	 * <p>Clears first, so an item that was bound in the live game but not in the recording stops
	 * claiming a slot. Leaving a stale binding in place is what made slot 0 resolve differently.
	 *
	 * <p>Items the recording does not mention are left unbound, and an unparsable entry is skipped
	 * rather than aborting playback: a partial restore is a wrong slot, but throwing here would lose the
	 * rest of the run and tell the reader nothing about which entry was at fault.
	 */
	public static void restore( String encoded ){
		if (Dungeon.hero == null) return;

		Dungeon.quickslot.reset();
		if (encoded == null || encoded.isEmpty()) return;

		for (String pair : encoded.split( String.valueOf( PAIR ) )){
			int link = pair.lastIndexOf( LINK );
			if (link <= 0 || link == pair.length() - 1) continue;

			String className = pair.substring( 0, link );
			int slot;
			try {
				slot = Integer.parseInt( pair.substring( link + 1 ));
			} catch (NumberFormatException e){
				continue;
			}

			for (Item item : Dungeon.hero.belongings.backpack){
				if (item.getClass().getSimpleName().equals( className )){
					Dungeon.quickslot.setSlot( slot, item );
					break;
				}
			}
		}
	}

	/**
	 * True when every pair names an item the hero actually carries.
	 *
	 * <p>Used by the viewer to refuse a recording whose bindings cannot be applied, rather than playing
	 * it with slots silently resolving to the wrong items.
	 */
	public static boolean applicable( String encoded ){
		if (Dungeon.hero == null) return false;
		if (encoded == null || encoded.isEmpty()) return true;

		for (String pair : encoded.split( String.valueOf( PAIR ) )){
			int link = pair.lastIndexOf( LINK );
			if (link <= 0 || link == pair.length() - 1) return false;
			String className = pair.substring( 0, link );

			boolean found = false;
			for (Item item : Dungeon.hero.belongings.backpack){
				if (item.getClass().getSimpleName().equals( className )){ found = true; break; }
			}
			if (!found) return false;
		}
		return true;
	}
}
