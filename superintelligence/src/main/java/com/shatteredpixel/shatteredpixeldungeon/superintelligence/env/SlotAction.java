package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;


public class SlotAction {


	private static Item pendingUseItem = null;

	private SlotAction() {}

	public static Item pendingUseItem(){
		return pendingUseItem;
	}

public static void clearPendingUseItem(){
		pendingUseItem = null;
	}


	public static boolean cancelPendingUse( Hero hero ){
		pendingUseItem = null;
		GameScene.clearPendingCellListener();
		hero.next();
		return true;
	}


	public static boolean execute( Hero hero, Item item, Action action, boolean allowEquipping ){
		if (action == Action.CANCEL){
			clearPendingUseItem();
			return true;
		}
		if (item == null) return false;
		if (action == Action.DROP) return drop( hero, item );
		if (action == Action.THROW) return throwItem( hero, item );
		if (action == Action.ITEM_ALT) return alt( hero, item );
		return use( hero, item, allowEquipping );
	}


public static boolean isBareEquipToggle( Hero hero, Item item, boolean allowEquipping ){
		if (item == null) return false;
		if (!(allowEquipping && item instanceof EquipableItem)) return false;
		return !item.usesTargeting && !activates( hero, item, resolveAction( hero, item ));
	}

	public static boolean stuckOn( Hero hero, Item item, boolean allowEquipping ){
		return item.isEquipped( hero ) && item.cursed && isBareEquipToggle( hero, item, allowEquipping )
				&& hero.buff( com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MagicImmune.class ) == null
				&& (!hero.belongings.lostInventory() || item.keptThroughLostInventory());
	}

	private static boolean activates( Hero hero, Item item, String action ){
		return item.isEquipped( hero ) && action != null
				&& !action.equals( EquipableItem.AC_UNEQUIP ) && !action.equals( EquipableItem.AC_EQUIP );
	}


	public static boolean use( Hero hero, Item item, boolean allowEquipping ){

		boolean equippable = allowEquipping && item instanceof EquipableItem;

		String action = resolveAction( hero, item );
		boolean activates = activates( hero, item, action );

		if (equippable && !item.usesTargeting && !activates){
			toggleEquip( hero, (EquipableItem) item );
			return false;
		}

		if (action == null){

			if (equippable) toggleEquip( hero, (EquipableItem) item );
			else hero.next();
			return false;
		}

		GameScene.clearPendingCellListener();

		item.setCurrent( hero );
		item.execute( hero, action );

		if (GameScene.aimPending() || (item.usesTargeting && !(item instanceof MagesStaff) && !action.equals( EquipableItem.AC_EQUIP ))){
			pendingUseItem = item;
			return true;
		}

		hero.next();

		return false;
	}


	public static void castAt( Hero hero, Item item, int cell ){
		item.throwAt( hero, cell );
		hero.sprite.zap( item.throwPos( hero, cell ) );
		clearPendingUseItem();
	}

	public static boolean pick( Item item ){
		WndBag.ItemSelector selector = GameScene.answerableSelector();
		if (selector == null) return false;
		if (item != null && !selector.itemSelectable( item )) return false;
		GameScene.clearSelector();
		selector.onSelect( item );
		if (GameScene.answerableSelector() == selector) GameScene.clearSelector();
		return true;
	}

	public static boolean throwable( Hero hero, Item item ){
		return item != null && !item.isEquipped( hero ) && item.actions( hero ).contains( Item.AC_THROW );
	}

	public static boolean anyAlt( Hero hero, java.util.List< Item > items ){
		for (Item item : items) if (item != null && altAction( hero, item ) != null) return true;
		return false;
	}

	public static boolean alt( Hero hero, Item item ){
		if (item == null) return false;
		String action = altAction( hero, item );
		if (action == null) return false;
		GameScene.clearPendingCellListener();
		item.setCurrent( hero );
		item.execute( hero, action );
		if (GameScene.aimPending() || (item.usesTargeting && !(item instanceof MagesStaff))){
			pendingUseItem = item;
			return true;
		}
		hero.next();
		return false;
	}

	private static String altAction( Hero hero, Item item ){
		if (item == null) return null;
		String primary = resolveAction( hero, item );
		for (String candidate : item.actions( hero )){
			if (candidate.equals( Item.AC_DROP ) || candidate.equals( Item.AC_THROW )) continue;
			if (candidate.equals( EquipableItem.AC_EQUIP ) || candidate.equals( EquipableItem.AC_UNEQUIP )) continue;
			if (candidate.equals( Potion.AC_CHOOSE ) || candidate.equals( primary )) continue;
			return candidate;
		}
		return null;
	}

	static boolean anyThrowable( Hero hero, java.util.List< Item > items ){
		for (Item item : items) if (throwable( hero, item )) return true;
		return false;
	}

	public static boolean throwItem( Hero hero, Item item ){
		if (!throwable( hero, item )) return false;
		GameScene.clearPendingCellListener();
		item.setCurrent( hero );
		item.execute( hero, Item.AC_THROW );
		if (!GameScene.aimPending()) return false;
		pendingUseItem = item;
		return true;
	}

	public static boolean drop( Hero hero, Item item ){
		if (item.isEquipped( hero )) return false;
		item.setCurrent( hero );
		item.execute( hero, Item.AC_DROP );
		return true;
	}


	private static boolean toggleEquip( Hero hero, EquipableItem item ){
		boolean changed;

		if (item.isEquipped( hero )){
			item.doUnequip( hero, true );
			changed = true;
		} else {
			changed = item.doEquip( hero );
		}

		hero.next();

		return changed;
	}


	static String resolveAction( Hero hero, Item item ){
		String def = item.defaultAction();

		if (def != null && def.equals( Potion.AC_CHOOSE )){
			def = Potion.AC_DRINK;
		}
		java.util.ArrayList< String > actions = item.actions( hero );
		if (def != null && !def.equals( Item.AC_DROP ) && actions.contains( def )) return def;

		for (String candidate : actions){
			if (!candidate.equals( Item.AC_DROP ) && !candidate.equals( Item.AC_THROW )){
				return candidate;
			}
		}
		return null;
	}
}