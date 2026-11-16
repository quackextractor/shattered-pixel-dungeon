package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.WaterOfAwareness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Awareness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bless;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Levitation;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.RatKing;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Honeypot;
import com.shatteredpixel.shatteredpixeldungeon.items.EnergyCrystal;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.KingsCrown;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.GoldenKey;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfLevitation;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.Runestone;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.MissileWeapon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.RegularLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.RatKingRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretArtilleryRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretChestChasmRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretGardenRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretHoardRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretHoneypotRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretLaboratoryRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretLarderRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretLibraryRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretMazeRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretRunestoneRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretSummoningRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretWellRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.SummoningTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.plants.Firebloom;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sorrowmoss;
import com.shatteredpixel.shatteredpixeldungeon.plants.Starflower;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class SecretRoomScenarios {

	private SecretRoomScenarios(){}

	private static final int SECRET_DEPTH = 3;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( pickUp( SecretArtilleryRoom.class, MissileWeapon.class ));
		all.add( pickUp( SecretHoardRoom.class, Gold.class ));
		all.add( pickUp( SecretHoneypotRoom.class, Honeypot.class ));
		all.add( pickUp( SecretLarderRoom.class, Food.class ));
		all.add( pickUp( SecretLibraryRoom.class, Scroll.class ));
		all.add( pickUp( SecretRunestoneRoom.class, Runestone.class ));
		all.add( chestChasm() );
		all.add( garden() );
		all.add( laboratory() );
		all.add( maze() );
		all.add( summoning() );
		all.add( well() );
		all.add( ratKing() );
		return all;
	}

	private static Scenario secret( Class< ? extends SecretRoom > type, String mechanic, Scenario.Setup extra, Scenario.Script feature ){
		return new Scenario( "secret." + type.getSimpleName(), mechanic, HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					ScrollOfTeleportation.appear( Dungeon.hero, outside( room( type )));
					extra.build( world, kit );
				},
				d -> {
					reveal( d, room( type ));
					feature.run( d );
				} ).depth( SECRET_DEPTH ).prepare( RoomForcing.secret( type )).covers( type );
	}

	private static Scenario pickUp( Class< ? extends SecretRoom > type, Class< ? extends Item > item ){
		String name = item.getSimpleName();
		return secret( type, "find a secret room and take a " + name, ( world, kit ) -> {}, d -> {
			int before = held( item );
			int heap = nearestHeap( room( type ), h -> h.type == Heap.Type.HEAP && holds( h, item ), false );
			d.require( heap >= 0, "no reachable heap in the room holds a " + name );
			walk( d, heap, true, false, 40 );
			d.expect( held( item ) > before, "walking onto the heap at " + heap + " left " + held( item ) + " " + name + ", "
					+ before + " before" );
		} );
	}

	private static Scenario chestChasm(){
		return secret( SecretChestChasmRoom.class, "levitate across a chasm to a locked chest",
				( world, kit ) -> kit.give( new PotionOfLevitation() ),
				d -> {
					Room room = room( SecretChestChasmRoom.class );
					d.use( PotionOfLevitation.class );
					d.require( Dungeon.hero.buff( Levitation.class ) != null, "the potion of levitation gave no Levitation" );
					int key = nearestHeap( room, h -> holds( h, GoldenKey.class ), true );
					d.require( key >= 0, "no golden key lies across the chasm" );
					walk( d, key, true, true, 10 );
					int chest = -1;
					for (Heap heap : Dungeon.level.heaps.valueList()){
						if (heap.type == Heap.Type.LOCKED_CHEST && (chest < 0 || Dungeon.level.distance( key, heap.pos ) < Dungeon.level.distance( key, chest ))){
							chest = heap.pos;
						}
					}
					d.require( chest >= 0, "no locked chest in the room" );
					walk( d, chest, true, true, 6 );
					Heap left = Dungeon.level.heaps.get( chest );
					d.expect( left == null || left.type == Heap.Type.HEAP, "the locked chest at " + chest + " stayed " + (left == null ? null : left.type) );
					d.expect( Dungeon.depth == SECRET_DEPTH, "the hero fell to depth " + Dungeon.depth );
				} );
	}

	private static Scenario garden(){
		return secret( SecretGardenRoom.class, "find a secret garden and step on its starflower", ( world, kit ) -> {}, d -> {
			int flower = -1;
			Room room = room( SecretGardenRoom.class );
			for (Plant plant : Dungeon.level.plants.valueList()){
				if (plant instanceof Starflower && room.inside( Dungeon.level.cellToPoint( plant.pos ))) flower = plant.pos;
			}
			d.require( flower >= 0, "the garden has no starflower" );
			d.require( Dungeon.hero.buff( Bless.class ) == null, "the hero was blessed before the starflower" );
			walk( d, flower, true, false, 30 );
			d.expect( Dungeon.level.plants.get( flower ) == null, "the starflower at " + flower + " was not trampled" );
			d.expect( Dungeon.hero.buff( Bless.class ) != null, "the starflower gave no Bless" );
		} );
	}

	private static Scenario laboratory(){
		return secret( SecretLaboratoryRoom.class, "find a secret laboratory and brew at its pot",
				( world, kit ) -> {
					kit.emptyPack();
					kit.give( new Sungrass.Seed() );
					kit.give( new Firebloom.Seed() );
					kit.give( new Sorrowmoss.Seed() );
				},
				d -> {
					Room room = room( SecretLaboratoryRoom.class );
					int crystal = nearestHeap( room, h -> h.type == Heap.Type.HEAP && holds( h, EnergyCrystal.class ), false );
					d.require( crystal >= 0, "no reachable energy crystal in the laboratory" );
					int energy = Dungeon.energy;
					walk( d, crystal, true, false, 20 );
					d.expect( Dungeon.energy > energy, "the laboratory's crystals left energy at " + Dungeon.energy );
					int pot = terrainIn( room, Terrain.ALCHEMY );
					d.require( pot >= 0, "the laboratory has no pot" );
					walk( d, pot, false, false, 30 );
					int crafted = Statistics.itemsCrafted;
					d.brew( Item.class );
					d.expect( Statistics.itemsCrafted == crafted + 1, "itemsCrafted moved by " + (Statistics.itemsCrafted - crafted) );
				} );
	}

	private static Scenario maze(){
		Scenario scenario = secret( SecretMazeRoom.class, "find a secret maze and open the chest at its end", ( world, kit ) -> {}, d -> {
			int prize = nearestHeap( room( SecretMazeRoom.class ), h -> h.type == Heap.Type.CHEST, false );
			d.require( prize >= 0, "no reachable chest in the maze" );
			walk( d, prize, true, false, 300 );
			Heap left = Dungeon.level.heaps.get( prize );
			d.expect( left == null || left.type == Heap.Type.HEAP, "the maze's chest at " + prize + " stayed " + (left == null ? null : left.type) );
		} );
		scenario.maxSteps = 360;
		return scenario;
	}

	private static Scenario summoning(){
		return secret( SecretSummoningRoom.class, "find a secret summoning room and spring a trap", ( world, kit ) -> {}, d -> {
			int door = doorCell( room( SecretSummoningRoom.class ));
			int inward = door + (door - Dungeon.hero.pos);
			Trap trap = Dungeon.level.traps.get( inward );
			d.require( trap instanceof SummoningTrap && trap.active, "the cell inside the door holds " + trap );
			d.move( door );
			int mobs = Dungeon.level.mobs.size();
			d.move( inward );
			d.expect( !trap.active, "the summoning trap at " + inward + " did not fire, hero at " + Dungeon.hero.pos );
			d.expect( Dungeon.level.mobs.size() > mobs, "the summoning trap summoned nothing: " + Dungeon.level.mobs.size() + " mobs" );
		} );
	}

	private static Scenario well(){
		return secret( SecretWellRoom.class, "find a secret well and drink", ( world, kit ) -> kit.health( 10, 1000 ), d -> {
			int well = terrainIn( room( SecretWellRoom.class ), Terrain.WELL );
			d.require( well >= 0, "the room has no well" );
			Blob awareness = Dungeon.level.blobs.get( WaterOfAwareness.class );
			boolean aware = awareness != null && awareness.cur[ well ] > 0;
			d.require( Dungeon.hero.buff( Awareness.class ) == null, "the hero had Awareness before the well" );
			int hp = Dungeon.hero.HP;
			walk( d, well, true, false, 12 );
			d.expect( Dungeon.level.map[ well ] == Terrain.EMPTY_WELL, "the well was not drunk: terrain " + Dungeon.level.map[ well ] );
			if (aware) d.expect( Dungeon.hero.buff( Awareness.class ) != null, "Water of Awareness gave no Awareness" );
			else d.expect( Dungeon.hero.HP == Dungeon.hero.HT && Dungeon.hero.HP > hp, "Water of Health left the hero at " + Dungeon.hero.HP );
		} );
	}

	private static Scenario ratKing(){
		return new Scenario( "secret.RatKingRoom", "find the Rat King's hidden room and talk to him", HeroClass.WARRIOR,
				( world, kit ) -> {
					RatKing king = world.find( RatKing.class );
					world.clearMobs( king );
					kit.sturdy();
					kit.give( new KingsCrown() );
					ScrollOfTeleportation.appear( Dungeon.hero, outside( room( RatKingRoom.class )));
				},
				d -> {
					reveal( d, room( RatKingRoom.class ));
					RatKing king = d.world().find( RatKing.class );
					d.require( king.state == king.SLEEPING, "the Rat King was awake before the hero came" );
					walk( d, king.pos, false, false, 12 );
					d.move( king.pos );
					d.expect( king.state != king.SLEEPING, "walking into the Rat King did not wake him" );
					d.until( () -> d.env().mode() == EnvMode.MENU, 6, () -> {
						walk( d, king.pos, false, false, 6 );
						d.move( king.pos );
					} );
					d.require( d.env().mode() == EnvMode.MENU, "talking to the Rat King with a crown opened " + d.env().mode() );
					d.menu( 2 );
					d.expect( d.env().mode() == EnvMode.WORLD, "declining the Rat King left the env in " + d.env().mode() );
					d.expect( Dungeon.hero.armorAbility == null, "declining the Rat King gave " + Dungeon.hero.armorAbility );
				} ).depth( 5 ).covers( RatKingRoom.class, RatKing.class );
	}

	private static Room room( Class< ? extends Room > type ){
		if (!(Dungeon.level instanceof RegularLevel)) throw new NoFixture( "depth " + Dungeon.depth + " has no rooms" );
		for (Room room : ((RegularLevel) Dungeon.level).rooms()) if (type.isInstance( room )) return room;
		throw new NoFixture( "the floor has no " + type.getSimpleName() );
	}

	private static int doorCell( Room room ){
		Room.Door door = ((SecretRoom) room).entrance();
		if (door == null) throw new NoFixture( room.getClass().getSimpleName() + " has no door" );
		return Dungeon.level.pointToCell( door );
	}

	private static int outside( Room room ){
		Room.Door door = ((SecretRoom) room).entrance();
		int dx = door.x == room.left ? -1 : door.x == room.right ? 1 : 0;
		int dy = door.y == room.top ? -1 : door.y == room.bottom ? 1 : 0;
		Level level = Dungeon.level;
		int cell = doorCell( room ) + dx + dy * level.width();
		if (!level.passable[ cell ] || level.pit[ cell ] || level.traps.get( cell ) != null || Actor.findChar( cell ) != null){
			throw new NoFixture( "the cell outside the " + room.getClass().getSimpleName() + " door is blocked" );
		}
		return cell;
	}

	private static void reveal( Driver d, Room room ){
		int door = doorCell( room );
		String name = room.getClass().getSimpleName();
		d.require( Dungeon.level.map[ door ] == Terrain.SECRET_DOOR, "the " + name + " door at " + door + " is not hidden: terrain "
				+ Dungeon.level.map[ door ] );
		d.act( Action.SEARCH );
		d.require( Dungeon.level.map[ door ] != Terrain.SECRET_DOOR && Dungeon.level.passable[ door ],
				"SEARCH beside the " + name + " door left terrain " + Dungeon.level.map[ door ] );
	}

	private static boolean[] open( boolean flying ){
		Level level = Dungeon.level;
		boolean[] open = new boolean[ level.length() ];
		for (int i = 0; i < open.length; i++){
			Trap trap = level.traps.get( i );
			open[ i ] = (level.passable[ i ] && !level.pit[ i ] || flying && level.pit[ i ])
					&& (trap == null || !trap.visible) && level.plants.get( i ) == null;
		}
		return open;
	}

	private static void walk( Driver d, int cell, boolean onto, boolean flying, int max ){
		boolean[] open = open( flying );
		open[ cell ] = true;
		for (int i = 0; i < max && d.env().running() && Dungeon.hero.pos != cell
				&& (onto || !Dungeon.level.adjacent( Dungeon.hero.pos, cell )); i++){
			int next = PathFinder.getStep( Dungeon.hero.pos, cell, open );
			d.require( next >= 0, "no path from " + Dungeon.hero.pos + " to " + cell );
			d.move( next );
		}
	}

	private static int nearestHeap( Room room, Predicate< Heap > wanted, boolean flying ){
		boolean[] open = open( flying );
		PathFinder.buildDistanceMap( Dungeon.hero.pos, open );
		int best = -1;
		for (Heap heap : Dungeon.level.heaps.valueList()){
			if (!room.inside( Dungeon.level.cellToPoint( heap.pos )) || !wanted.test( heap )) continue;
			if (PathFinder.distance[ heap.pos ] == Integer.MAX_VALUE) continue;
			if (best < 0 || PathFinder.distance[ heap.pos ] < PathFinder.distance[ best ]) best = heap.pos;
		}
		return best;
	}

	private static int terrainIn( Room room, int terrain ){
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (Dungeon.level.map[ cell ] == terrain && room.inside( Dungeon.level.cellToPoint( cell ))) return cell;
		}
		return -1;
	}

	private static boolean holds( Heap heap, Class< ? extends Item > type ){
		for (Item item : heap.items) if (type.isInstance( item )) return true;
		return false;
	}

	private static int held( Class< ? extends Item > type ){
		if (type == Gold.class) return Dungeon.gold;
		int count = 0;
		for (Item item : Dungeon.hero.belongings) if (type.isInstance( item )) count += item.quantity();
		return count;
	}
}
