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
		if (allowEquipping && item instanceof EquipableItem){
			return toggleEquip( hero, (EquipableItem) item );
		}

		String action = resolveAction( hero, item );
		if (action == null) return false;

		//clear any stale aim request so a fresh one is unambiguous
		GameScene.clearPendingCellListener();

		item.setCurrent( hero );
		item.execute( hero, action );

		if (item.usesTargeting || GameScene.pendingCellListener() != null ){
			pendingUseItem = item;
			return true;
		}

		hero.next();
		return true;
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
		if (item.isEquipped( hero )){
			item.doUnequip( hero, true );
		} else if (!item.doEquip( hero )){
			//refused: cursed, or above the hero's strength. Staying unequipped is correct, and
			//reward.RewardModel penalises the attempt so the policy learns the rule.
			return false;
		}
		hero.next();
		return true;
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