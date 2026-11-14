package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.CrystalKey;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.GoldenKey;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.Key;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class WorldScenarios {

	private WorldScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( door() );
		all.add( secretDoor() );
		all.add( lockedDoor() );
		all.add( chest( Heap.Type.CHEST, null ));
		all.add( chest( Heap.Type.LOCKED_CHEST, () -> new GoldenKey( Dungeon.depth )));
		all.add( chest( Heap.Type.CRYSTAL_CHEST, () -> new CrystalKey( Dungeon.depth )));
		all.add( chest( Heap.Type.SKELETON, null ));
		all.add( mimic() );
		all.add( travelItem() );
		return all;
	}

	private static Scenario door(){
		return new Scenario( "door", "walk through a closed door", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					world.terrain( world.adjacentFreeStraight(), Terrain.DOOR );
				},
				d -> {
					int door = d.world().beside( Terrain.DOOR );
					d.move( door );
					d.expect( Dungeon.hero.pos == door, "the hero did not step into the door at " + door );
					d.expect( Dungeon.level.map[ door ] == Terrain.OPEN_DOOR, "the door did not open: terrain " + Dungeon.level.map[ door ] );
				} );
	}

	private static Scenario secretDoor(){
		return new Scenario( "secret-door", "search out a hidden door", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					world.terrain( world.adjacentFreeStraight(), Terrain.SECRET_DOOR );
				},
				d -> {
					int door = d.world().beside( Terrain.SECRET_DOOR );
					d.until( () -> Dungeon.level.map[ door ] != Terrain.SECRET_DOOR, 5, () -> d.act( Action.SEARCH ));
					d.expect( Dungeon.level.map[ door ] == Terrain.DOOR, "five searches beside a secret door left terrain "
							+ Dungeon.level.map[ door ] );
				} );
	}

	private static Scenario lockedDoor(){
		return new Scenario( "locked-door", "unlock a door with its key", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					world.terrain( world.adjacentFreeStraight(), Terrain.LOCKED_DOOR );
					world.drop( new IronKey( Dungeon.depth ), world.adjacentFree() );
				},
				d -> {
					int door = d.world().beside( Terrain.LOCKED_DOOR );
					fetchKey( d, IronKey.class );
					d.step( Action.INTERACT, 0 );
					d.expect( Dungeon.level.map[ door ] != Terrain.LOCKED_DOOR, "the key did not unlock the door" );
				} );
	}

	private static void fetchKey( Driver d, Class< ? extends Key > type ){
		int start = Dungeon.hero.pos;
		int keyCell = -1;
		for (int cell : com.watabou.utils.PathFinder.NEIGHBOURS8){
			Heap heap = Dungeon.level.heaps.get( start + cell );
			if (heap != null && type.isInstance( heap.peek() )) keyCell = start + cell;
		}
		d.require( keyCell >= 0, "no " + type.getSimpleName() + " beside the hero" );
		d.move( keyCell );
		d.expect( Dungeon.level.heaps.get( keyCell ) == null, "the " + type.getSimpleName() + " was not picked up" );
		d.move( start );
	}

	private static Scenario chest( Heap.Type type, Supplier< Key > key ){
		return new Scenario( "chest." + type.name(), "open a " + type.name(), HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					world.drop( new Food(), world.adjacentFreeStraight() ).type = type;
					if (key != null) world.drop( key.get(), world.adjacentFree() );
				},
				d -> {
					if (key != null) fetchKey( d, key.get().getClass() );
					int chest = -1;
					for (int cell : com.watabou.utils.PathFinder.NEIGHBOURS8){
						Heap heap = Dungeon.level.heaps.get( Dungeon.hero.pos + cell );
						if (heap != null && heap.type == type) chest = Dungeon.hero.pos + cell;
					}
					d.require( chest >= 0, "no " + type + " beside the hero" );
					d.step( Action.INTERACT, 0 );
					Heap heap = Dungeon.level.heaps.get( chest );
					d.expect( heap == null || heap.type == Heap.Type.HEAP, "the " + type + " is still closed" );
				} );
	}

	private static Scenario mimic(){
		return new Scenario( "chest.Mimic", "open a chest that is a mimic", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					int cell = world.adjacentFreeStraight();
					world.mob( Mimic.spawnAt( cell, new Food() ), cell );
				},
				d -> {
					Mimic mimic = d.world().find( Mimic.class );
					int hp = mimic.HP;
					d.move( mimic.pos );
					d.waitFor( () -> mimic.state == mimic.HUNTING || mimic.HP < hp, 3 );
					d.expect( mimic.state == mimic.HUNTING || mimic.HP < hp || !mimic.isAlive(), "the mimic neither woke nor took a hit" );
				} );
	}

	private static Scenario travelItem(){
		return new Scenario( "travel-item", "travel to a seen item", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					world.drop( new Food(), world.cellAtRange( 3, true ));
				},
				d -> {
					int food = Dungeon.hero.belongings.getItem( Food.class ).quantity();
					d.until( () -> Dungeon.hero.belongings.getItem( Food.class ).quantity() > food, 8,
							() -> d.act( Action.TRAVEL_ITEM ));
					d.expect( Dungeon.hero.belongings.getItem( Food.class ).quantity() > food, "eight TRAVEL_ITEM steps did"
							+ " not collect the Food 3 cells away" );
				} );
	}
}
