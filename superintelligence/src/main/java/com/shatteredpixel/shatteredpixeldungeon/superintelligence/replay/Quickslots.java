package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.QuickSlot;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

public class Quickslots {

	private static final char PAIR = ',';
	private static final char LINK = ':';

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

	public static void restore( String encoded ){
		if (Dungeon.hero == null) return;

		for (int slot = 0; slot < QuickSlot.SIZE; slot++){
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

	private static boolean namesASlot( Item item ){
		for (Item carried : Dungeon.hero.belongings.backpack){
			if (carried == item ) return true;
		}
		return false;
	}

	public static String prepare( Replay.Step step, int stepIndex, boolean wireDroppedFields ){
		String mode = step.mode == null ? "" : step.mode;
		if (!mode.equals( EnvMode.SLOT.name() ) && !mode.equals( EnvMode.INVENTORY.name() )){

			return null;
		}

		if (step.quickslots == null || step.quickslots.isEmpty()){
			if (!wireDroppedFields){

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
