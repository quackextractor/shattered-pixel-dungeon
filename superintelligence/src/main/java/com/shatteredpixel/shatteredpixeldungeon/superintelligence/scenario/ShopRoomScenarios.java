package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Shopkeeper;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.ShopRoom;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class ShopRoomScenarios {

	private ShopRoomScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( buy() );
		return all;
	}

	private static Scenario buy(){
		return new Scenario( "shop.buy", "buy an item from a forced shop", HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy().gold( 10000 );
					RoomVisit.atDoor( world, world.room( ShopRoom.class ));
				},
				d -> {
					ShopRoom room = d.world().room( ShopRoom.class );
					RoomVisit.enter( d, room );

					Heap heap = null;
					for (Heap h : Dungeon.level.heaps.valueList()){
						if (!RoomVisit.inside( room, h.pos )) continue;
						if (h.type != Heap.Type.FOR_SALE) continue;
						if (h.size() != 1) continue;
						Item it = h.peek();
						if (it == null || Shopkeeper.sellPrice( it ) > Dungeon.gold) continue;
						heap = h;
						break;
					}
					if (heap == null) throw new NoFixture( "no affordable for-sale item in the shop" );

					int heapCell = heap.pos;
					Class< ? extends Item > boughtType = heap.peek().getClass();
					int packBefore = held( boughtType );

					RoomVisit.walk( d, heapCell, 40 );
					d.require( Dungeon.level.adjacent( Dungeon.hero.pos, heapCell ),
							"the hero did not reach the shop item" );

					int goldBefore = Dungeon.gold;
					int price = Shopkeeper.sellPrice( heap.peek() );

					d.move( heapCell );
					d.require( d.env().mode() == EnvMode.MENU,
							"the trade window did not reach the agent: " + d.env().mode() );

					d.menu( 0 );
					d.expect( d.env().mode() == EnvMode.WORLD, "the shop left the env in " + d.env().mode() );
					d.expect( held( boughtType ) > packBefore, "the bought item is not in the pack" );
					d.expect( Dungeon.gold < goldBefore, "buying left gold at " + Dungeon.gold );
					d.expect( Dungeon.gold == goldBefore - price,
							"buying changed gold from " + goldBefore + " to " + Dungeon.gold );
					d.expect( Dungeon.level.heaps.get( heapCell ) == null,
							"the shopkeeper's stock kept the item" );
				}).depth( 6 ).seeds( "SCN-1" ).prepare( RoomForcing.special( ShopRoom.class ))
				.covers( ShopRoom.class );
	}

	private static int held( Class< ? extends Item > type ){
		int count = 0;
		for (Item item : Dungeon.hero.belongings) if (item.getClass() == type) count += item.quantity();
		return count;
	}
}
