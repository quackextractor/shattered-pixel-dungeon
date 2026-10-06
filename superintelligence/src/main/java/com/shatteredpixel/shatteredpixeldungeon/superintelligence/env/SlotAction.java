package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;

/**
 * Applies {@link Action#USE} and {@link Action#DROP} to a chosen inventory slot.
 *
 * Uses go through {@code Item.execute(hero, action)}, the same entry point {@code WndUseItem}
 * uses, so energy cost, hunger cost, identification and buff application stay the game's own code.
 *
 * Two cases need handling around that call. Aiming items do not resolve the aim themselves, they
 * request one through {@code GameScene.selectCell}; that request is left pending for
 * {@link ActionMapper} to service with the agent's cell. And potions that can be both drunk and
 * thrown default to a CHOOSE action that opens another dialog, so DRINK is chosen directly -
 * throwing a potion is rare and the sub-dialog would otherwise sit on every one of them.
 */
public class SlotAction {

	/** The item whose USE is waiting for a slot or an aim. */
	private static Item pendingUseItem = null;

	private SlotAction() {}

	public static Item pendingUseItem(){
		return pendingUseItem;
	}

public static void clearPendingUseItem(){
		pendingUseItem = null;
	}

	/**
	 * Abandons an aim in progress.
	 *
	 * Throwing only installs a cell listener, so nothing has left the backpack yet and cancelling
	 * costs nothing but the turn. The hero still has to be released: the throw had already put him
	 * mid-action, and without {@code next()} he never became ready again and the episode stalled.
	 */
	public static boolean cancelPendingUse( Hero hero ){
		pendingUseItem = null;
		GameScene.clearPendingCellListener();
		hero.next();
		return true;
	}

	/** Handles the agent's choice while in EnvMode.SLOT or EnvMode.INVENTORY. */
	public static boolean execute( Hero hero, Item item, Action action, boolean allowEquipping ){
		if (action == Action.CANCEL){
			clearPendingUseItem();
			return true;
		}
		if (item == null) return false;
		if (action == Action.DROP) return drop( hero, item );
		return use( hero, item, allowEquipping );
	}

	/**
	 * Uses, throws or equips {@code item}.
	 *
	 * @return true when the use needs an aim, in which case the caller enters
	 *         {@link EnvMode#TARGETING}
	 */
public static boolean use( Hero hero, Item item, boolean allowEquipping ){
		//Weapon extends EquipableItem, so a missile weapon is equipable too, and checking
		//equipability first equipped a throwing stone instead of throwing it. usesTargeting is what
		//distinguishes the two: a missile is thrown or cast, everything else equipable is equipped.
		//Getting this backwards also explained a spurious aim step - the old code returned
		//toggleEquip's "equipped" boolean, which the env read as "needs an aim", so every weapon
		//change invented a TARGETING step while genuinely thrown items never reached one.
		boolean equippable = allowEquipping && item instanceof EquipableItem;

		if (equippable && !item.usesTargeting){
			toggleEquip( hero, (EquipableItem) item );
			return false;
		}

		String action = resolveAction( hero, item );
		if (action == null){
			//Still spend the turn. Bailing out without releasing the hero left it permanently
			//mid-action, and the pipeline never saw it become ready again, so the episode ended as
			//STALLED rather than merely wasting the turn.
			if (equippable) toggleEquip( hero, (EquipableItem) item );
			else hero.next();
			return false;
		}

		//clear any stale aim request so a fresh one is unambiguous
		GameScene.clearPendingCellListener();

		item.setCurrent( hero );
		item.execute( hero, action );

		if (item.usesTargeting || GameScene.pendingCellListener() != null ){
			pendingUseItem = item;
			return true;
		}

		hero.next();

		//False, not true: the item was consumed and the turn is over, so there is nothing to aim.
		//Returning true here sent the env into TARGETING after every potion and every pickup.
		return false;
	}

/** Casts or throws {@code item}, mirroring Item.cast minus the missile sprite. */
	public static void castAt( Hero hero, Item item, int cell ){
		item.throwAt( hero, cell );
		hero.sprite.zap( item.throwPos( hero, cell ) );
		clearPendingUseItem();
	}

	public static boolean drop( Hero hero, Item item ){
		if (item.isEquipped( hero )) return false;
		item.setCurrent( hero );
		item.execute( hero, Item.AC_DROP );
		return true;
	}

/** Equips or unequips, going through the game's own strength and curse checks. */
	private static boolean toggleEquip( Hero hero, EquipableItem item ){
		boolean changed;

		if (item.isEquipped( hero )){
			item.doUnequip( hero, true );
			changed = true;
		} else {
			changed = item.doEquip( hero );
		}

		//The turn is spent either way, so the hero always has to be released. Returning early on a
		//refused equip skipped hero.next(), which left the hero permanently mid-action: the
		//pipeline never saw the hero become ready again, so every episode that touched a
		//curse-limited or over-strength item ended as STALLED within a dozen turns.
		hero.next();

		return changed;
	}

	/**
	 * Picks the action string to execute.
	 *
	 * Walks the item's declared actions rather than assuming a usable default, so unidentified
	 * consumables - whose default is DRINK but which also offer IDENTIFY - still resolve.
	 */
	private static String resolveAction( Hero hero, Item item ){
		String def = item.defaultAction();

		if (def != null && def.equals( Potion.AC_CHOOSE )){
			def = Potion.AC_DRINK;
		}
		if (def != null && !def.equals( Item.AC_DROP )) return def;

		for (String candidate : item.actions( hero )){
			if (!candidate.equals( Item.AC_DROP ) && !candidate.equals( Item.AC_THROW )){
				return candidate;
			}
		}
		return null;
	}
}