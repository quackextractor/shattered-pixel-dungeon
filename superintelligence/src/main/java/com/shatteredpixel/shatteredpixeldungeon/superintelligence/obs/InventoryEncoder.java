package com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.ExoticPotion;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.Ring;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ExoticScroll;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;

import java.util.List;

public class InventoryEncoder {

	public static final int FEATURES_PER_SLOT = 14;

	private static final int F_PRESENT      = 0;
	private static final int F_TYPE_EMBED  = 1;
	private static final int F_CATEGORY    = 2;
	private static final int F_LEVEL       = 3;
	private static final int F_UPGRADE     = 4;
	private static final int F_QUANTITY    = 5;
	private static final int F_VALUE       = 6;
	private static final int F_CURSED      = 7;
	private static final int F_CURSED_KNOWN= 8;
	private static final int F_LEVEL_KNOWN = 9;
	private static final int F_IDENTIFIED  = 10;
	private static final int F_EQUIPPED    = 11;
	private static final int F_TARGETS     = 12;
	private static final int F_KNOWN_KIND  = 13;

	private InventoryEncoder() {}

	public static void encode( List<Item> slots, float[] out, int maxSlots ){
		Hero hero = Dungeon.hero;

		for (int i = 0; i < maxSlots; i++){
			int base = i * FEATURES_PER_SLOT;
			Item item = (i < slots.size()) ? slots.get( i ) : null;

			if (item == null){

				out[ base + F_TYPE_EMBED ] = 0f;
				continue;
			}

			out[ base + F_PRESENT ]     = 1f;
			out[ base + F_TYPE_EMBED ]  = typeIndex( item );
			out[ base + F_CATEGORY ]    = category( item );
			out[ base + F_LEVEL ]       = item.visiblyUpgraded();
			out[ base + F_UPGRADE ]     = item.visiblyUpgraded();
			out[ base + F_QUANTITY ]    = item.quantity();
			out[ base + F_VALUE ]       = Math.min( 1000f, item.value() ) / 1000f;
			out[ base + F_CURSED ]      = item.visiblyCursed() ? 1f : 0f;
			out[ base + F_CURSED_KNOWN]= item.cursedKnown ? 1f : 0f;
			out[ base + F_LEVEL_KNOWN ] = item.levelKnown ? 1f : 0f;
			out[ base + F_IDENTIFIED ]  = item.isIdentified() ? 1f : 0f;
			out[ base + F_EQUIPPED ]    = isEquipped( hero, item ) ? 1f : 0f;
			out[ base + F_TARGETS ]     = item.usesTargeting ? 1f : 0f;
			out[ base + F_KNOWN_KIND ]  = knownKindIndex( item );
		}
	}

	private static float typeIndex( Item item ){
		return TypeRegistry.indexOf( visibleClass( item ) );
	}

	private static Class<?> visibleClass( Item item ){
		if (item instanceof ExoticPotion && !((Potion) item).isKnown()) return ExoticPotion.class;
		if (item instanceof Potion && !((Potion) item).isKnown()) return Potion.class;
		if (item instanceof ExoticScroll && !((Scroll) item).isKnown()) return ExoticScroll.class;
		if (item instanceof Scroll && !((Scroll) item).isKnown()) return Scroll.class;
		if (item instanceof Ring && !((Ring) item).isKnown()) return Ring.class;
		return item.getClass();
	}

	private static float category( Item item ){
		if (item instanceof Wand)         return 1;
		if (item instanceof Potion)       return 2;
		if (item instanceof Scroll)       return 3;
		if (item instanceof Weapon)       return 4;
		if (item instanceof Armor)        return 5;
		if (item instanceof EquipableItem)return 6;
		if (item.value() > 0)            return 7;
		return 0;
	}

	private static float knownKindIndex( Item item ){
		if (item.icon == -1) return 0f;
		boolean shown = item.isIdentified() || (item instanceof Ring && ((Ring) item).isKnown());
		return shown ? item.icon + 1 : 0f;
	}

	private static boolean isEquipped( Hero hero, Item item ){
		Belongings b = hero.belongings;
		return item == b.weapon()
				|| item == b.armor()
				|| item == b.artifact()
				|| item == b.misc()
				|| item == b.ring()
				|| item == b.secondWep();
	}

	public static int typeCount(){
		return TypeRegistry.size();
	}

	static {

		if (ItemSpriteSheet.Icons.SIZE <= 0) throw new IllegalStateException();
	}
}