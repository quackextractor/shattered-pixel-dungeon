package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;


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
		return use( hero, item, allowEquipping );
	}


public static boolean isBareEquipToggle( Hero hero, Item item, boolean allowEquipping ){
		if (item == null) return false;
		if (!(allowEquipping && item instanceof EquipableItem)) return false;
		return !item.usesTargeting;
	}


	public static boolean use( Hero hero, Item item, boolean allowEquipping ){

		boolean equippable = allowEquipping && item instanceof EquipableItem;

		if (equippable && !item.usesTargeting){
			toggleEquip( hero, (EquipableItem) item );
			return false;
		}

		String action = resolveAction( hero, item );
		if (action == null){

			if (equippable) toggleEquip( hero, (EquipableItem) item );
			else hero.next();
			return false;
		}

		GameScene.clearPendingCellListener();

		item.setCurrent( hero );
		item.execute( hero, action );

		if (item.usesTargeting || GameScene.pendingCellListener() != null ){
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