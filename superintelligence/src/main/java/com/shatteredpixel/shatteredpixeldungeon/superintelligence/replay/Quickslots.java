package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

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
	 * Replaces the bindings that decide what a slot index names with those captured in {@code encoded}.
	 *
	 * <p><b>Only those.</b> {@code Dungeon.quickslot} also holds bindings on items the slot head cannot
	 * address - a duelist's starting {@code Rapier} is bound to quickslot 0 and is equipped, so
	 * {@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper#refreshSlots}
	 * never sees it and no index can name it. Wiping those as well is a small loss that stays small only
	 * while the hero never takes that item off: unequip it and it lands in the backpack still bound in
	 * the trainer's world, unbound in the viewer's, and every later slot index names a different item.
	 * A binding the recording cannot describe is the game's to keep.
	 *
	 * <p>Everything else is cleared first, so an item that was bound in the live game but not in the
	 * recording stops claiming a slot. Leaving a stale binding in place is what made slot 0 resolve
	 * differently.
	 *
	 * <p>Items the recording does not mention are left unbound, and an unparsable entry is skipped
	 * rather than aborting playback: a partial restore is a wrong slot, but throwing here would lose the
	 * rest of the run and tell the reader nothing about which entry was at fault.
	 */
	public static void restore( String encoded ){
		if (Dungeon.hero == null) return;

		for (int slot = 0; slot < Dungeon.quickslot.SIZE; slot++){
			Item bound = Dungeon.quickslot.getItem( slot );
			if (bound != null && namesASlot( bound )) Dungeon.quickslot.clearSlot( slot );
		}

		if (encoded == null || encoded.isEmpty()) return;

		for (String pair : encoded.split( String.valueOf( PAIR ))){
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

	/** True when a recorded slot index could name this item at all: it is reachable from the backpack. */
	private static boolean namesASlot( Item item ){
		for (Item carried : Dungeon.hero.belongings.backpack){
			if (carried == item ) return true;
		}
		return false;
	}

	/**
	 * Puts the bindings a step's slot index was chosen against back in force, or says why it cannot.
	 *
	 * <p><b>The one place playback resolves a step's slot index against its bindings.</b> The viewer and
	 * the headless verifier both call this, and that is the point: they resolve a recorded index the same
	 * way or one of them is not checking the other. They used not to - the viewer restored the bindings and
	 * {@code ReplayIO.verify} did not - which is why a recording could verify
	 * {@code [OK] replay reproduced in 660 steps} and halt the viewer at step 18 of the same 660.
	 *
	 * <p><b>An empty binding set is a state, not a missing field.</b> A hero who drops the one item bound
	 * to a quickslot has none left, and every later slot step legitimately records nothing - measured on a
	 * fresh trainer run: 211 SLOT and INVENTORY steps, one of them carrying bindings, the rest empty
	 * because the policy had dropped the Waterskin at step 0.
	 *
	 * <p>So the question is not asked of the bindings at all, because answering it from them cannot work:
	 * "none recorded" is equally true of a run in which the hero had nothing bound at every slot step,
	 * which resolves perfectly from backpack order. It is asked of the *other* fields the same wire
	 * carried - health, engine time, inventory - whose absence is a signature nothing else produces. See
	 * {@link Replay#wireDroppedStepFields()}.
	 *
	 * @return {@code null} when the step's bindings are in force, or a message naming what is wrong
	 */
	public static String prepare( Replay.Step step, int stepIndex, boolean wireDroppedFields ){
		String mode = step.mode == null ? "" : step.mode;
		if (!mode.equals( EnvMode.SLOT.name() ) && !mode.equals( EnvMode.INVENTORY.name() )){
			//Left alone rather than cleared. Only a SLOT or INVENTORY step resolves a slot index, and
			//the trainer's bindings evolve with the game between two such steps - equipping one rebinds,
			//dropping unbinds. Clearing here would put the viewer behind the trainer from its very first
			//WORLD step, and the bindings it would be clearing are the ones `HeroClass.initHero` set.
			return null;
		}

		if (step.quickslots == null || step.quickslots.isEmpty()){
			if (!wireDroppedFields){
				//Nothing was bound at this moment, which is a state rather than a gap: the index resolves
				//from backpack order alone.
				restore( null );
				return null;
			}
			return "step " + stepIndex + " names slot " + step.slot
					+ " but records no quickslot bindings, and neither does this recording carry health,"
					+ " engine time or inventory. Its writer sent four of the nine step fields, so the index"
					+ " cannot be resolved and the data was never recorded";
		}
		if (!applicable( step.quickslots )){
			return "step " + stepIndex + " records quickslots [" + step.quickslots
					+ "] that do not match what the hero is carrying; cannot resolve slot " + step.slot;
		}

		restore( step.quickslots );
		return null;
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
