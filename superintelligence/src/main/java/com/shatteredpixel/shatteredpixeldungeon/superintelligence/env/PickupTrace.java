package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;

import java.util.ArrayList;

public final class PickupTrace {

	private PickupTrace(){}

	private static final boolean ENABLED = System.getProperty( "spd.pickupTrace" ) != null;

	public static boolean enabled(){
		return ENABLED;
	}

	public static void drop( Hero hero, ActionMapper mapper, int slot ){
		if (!ENABLED) return;

		ArrayList<Item> slots = mapper.slots();
		Item inSlot = slot >= 0 && slot < slots.size() ? slots.get( slot ) : null;

		StringBuilder order = new StringBuilder();
		for (Item it : hero.belongings.backpack){
			order.append( it.getClass().getSimpleName() )
					.append( "(cat=" ).append(
							com.shatteredpixel.shatteredpixeldungeon.items.Generator.Category.order( it ) )
					.append( ",img=" ).append( it.image() ).append( ") " );
		}

		System.err.println( "[drop] slot=" + slot
				+ " item=" + (inSlot == null ? "null" : inSlot.getClass().getSimpleName())
				+ " equipped=" + (inSlot != null && inSlot.isEquipped( hero ))
				+ " bagRoom=" + bagRoom( hero.belongings.backpack )
				+ " order=" + order
				+ " qs=" + quickslotState( hero )
				+ " bags=" + bagList() );
	}

	public static void pickup( int cell, Heap h ){
		if (!ENABLED) return;

		StringBuilder items = new StringBuilder();
		h.items.forEach( i -> items.append( i.getClass().getSimpleName() ).append( " " ) );

		Hero hero = Dungeon.hero;
		System.err.println( "[pickup] cell=" + cell + " size=" + h.size()
				+ " backpack=" + bagRoom( hero.belongings.backpack )
				+ " bags=" + bagList()
				+ " heroClass=" + hero.heroClass
				+ " challenges=" + Dungeon.challenges
				+ " velvetDropped=" + Dungeon.LimitedDrops.VELVET_POUCH.dropped()
				+ " heroIdentity=" + System.identityHashCode( hero )
				+ " hero.belongings=" + System.identityHashCode( hero.belongings )
				+ " backpack=" + System.identityHashCode( hero.belongings.backpack )
				+ " pouch=" + System.identityHashCode( pouchOf( hero ) )
				+ " heapPouchIsSame=" + (heapPouch( h ) == pouchOf( hero ))
				+ " [" + items.toString().trim() + "]" );
	}

	private static String quickslotState( Hero hero ){
		StringBuilder sb = new StringBuilder();
		for (Item it : hero.belongings.backpack){
			int slot = Dungeon.quickslot.getSlot( it );
			sb.append( it.getClass().getSimpleName() ).append( "->" ).append( slot ).append( " " );
		}
		return sb.toString().trim();
	}

	private static Object heapPouch( Heap h ){
		for (Item i : h.items){
			if (i.getClass().getSimpleName().equals( "VelvetPouch" )) return i;
		}
		return null;
	}

	private static Object pouchOf( Hero hero ){
		for (Bag b : hero.belongings.getBags()){
			if (b.getClass().getSimpleName().equals( "VelvetPouch" )) return b;
		}
		return null;
	}

	private static String bagRoom( Bag bag ){
		int used = 0;
		for (Item i : bag) used++;
		return bag.getClass().getSimpleName() + " " + used + "/" + bag.capacity();
	}

	private static String bagList(){
		StringBuilder sb = new StringBuilder( "[" );
		for (Bag bag : Dungeon.hero.belongings.getBags()){
			sb.append( bag.getClass().getSimpleName() ).append( "{" );
			for (Item i : bag) sb.append( i.getClass().getSimpleName() ).append( "," );
			sb.append( "} " );
		}
		return sb.append( "]" ).toString();
	}
}