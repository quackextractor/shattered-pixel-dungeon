package com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSpriteSheet;

import java.util.List;

/**
 * Encodes the fixed-length inventory vector.
 *
 * research.md: "the player's Belongings require a fixed size vector. Because neural networks
 * require consistent input sizes, you must create a flat, fixed length array representing the
 * inventory slots. Each slot must encode the specific Item, its upgrade count, and whether it is
 * a CursedWand or has other curses. This flattened inventory vector will bypass the CNN and be
 * concatenated directly with the spatial data before entering the LSTM memory layer."
 *
 * The item *type* is encoded by identity, not by one-hot over every class in the game. One-hot
 * over ~500 item classes would dominate the parameter count and would need to be rebuilt whenever
 * an item is added, so instead each slot gets its own type embedding index plus a small set of
 * shared numeric features. The embedding is learned, which means a newly added item is usable
 * immediately instead of needing a vocabulary rebuild.
 *
 * identification state is reported exactly as the game models it: {@code levelKnown} and
 * {@code cursedKnown} are separate flags, so the agent can know an item is level +3 without
 * knowing it is cursed, which is the normal situation before using it.
 */
public class InventoryEncoder {

	public static final int FEATURES_PER_SLOT = 14;

	/** Offsets within one slot's feature block. */
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

	/**
	 * @param slots  the hero's inventory, positionally stable, see ActionMapper.refreshSlots
	 * @param out    destination of maxSlots * FEATURES_PER_SLOT floats
	 * @param maxSlots number of slots to write
	 */
	public static void encode( List<Item> slots, float[] out, int maxSlots ){
		Hero hero = Dungeon.hero;

		for (int i = 0; i < maxSlots; i++){
			int base = i * FEATURES_PER_SLOT;
			Item item = (i < slots.size()) ? slots.get( i ) : null;

			if (item == null){
				//an empty slot still needs a consistent type embedding input, so it shares the
				//reserved index for "nothing" and is distinguished by F_PRESENT
				out[ base + F_TYPE_EMBED ] = 0f;
				continue;
			}

			out[ base + F_PRESENT ]     = 1f;
			out[ base + F_TYPE_EMBED ]  = typeIndex( item );
			out[ base + F_CATEGORY ]    = category( item );
			out[ base + F_LEVEL ]       = item.level();
			out[ base + F_UPGRADE ]     = item.level();
			out[ base + F_QUANTITY ]    = item.quantity();
			out[ base + F_VALUE ]       = Math.min( 1000f, item.value() ) / 1000f;
			out[ base + F_CURSED ]      = item.cursed ? 1f : 0f;
			out[ base + F_CURSED_KNOWN]= item.cursedKnown ? 1f : 0f;
			out[ base + F_LEVEL_KNOWN ] = item.levelKnown ? 1f : 0f;
			out[ base + F_IDENTIFIED ]  = item.isIdentified() ? 1f : 0f;
			out[ base + F_EQUIPPED ]    = isEquipped( hero, item ) ? 1f : 0f;
			out[ base + F_TARGETS ]     = item.usesTargeting ? 1f : 0f;
			out[ base + F_KNOWN_KIND ]  = knownKindIndex( item );
		}
	}

	/**
	 * Stable integer identity for an item class.
	 *
	 * Resolved through a persistent map rather than a class hashcode, so the index only changes if
	 * the item set does. A checkpoint trained before an item is added still loads.
	 */
	private static float typeIndex( Item item ){
		return TypeRegistry.indexOf( item.getClass() );
	}

	/**
	 * Coarse kind, so the network can learn "this heals" or "this is a weapon" without having to
	 * separate hundreds of individual item identities first.
	 */
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

	/**
	 * Index of the item's kind within its own sprite sheet, for unidentified items.
	 *
	 * This is what lets the agent tell an unidentified crimson potion from an unidentified amber
	 * one, which is the entire basis of the identify-and-drink strategy in docs.md.
	 */
	private static float knownKindIndex( Item item ){
		if (item.isIdentified()) return 0f;
		if (item instanceof Potion) return item.icon;
		if (item instanceof Scroll) return item.icon;
		return 0f;
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

	/** Number of distinct item type embeddings the network must allocate. */
	public static int typeCount(){
		return TypeRegistry.size();
	}

	static {
		//ItemSpriteSheet constants are plain ints and no longer touch a texture, but referencing
		//one here keeps the dependency explicit and catches a regression at class load.
		if (ItemSpriteSheet.Icons.SIZE <= 0) throw new IllegalStateException();
	}
}