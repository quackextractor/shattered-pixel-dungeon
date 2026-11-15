package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.ArmoredStatue;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Wraith;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.CrystalKey;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.Key;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.Runestone;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.ArmoryRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.CryptRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.CrystalChoiceRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.CrystalPathRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.CrystalVaultRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.DemonSpawnerRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.LibraryRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.PitRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.PoolRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.RunestoneRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SpecialRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.StatueRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.StorageRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.TreasuryRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.WeakFloorRoom;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class RoomScenarios {

	private RoomScenarios(){}

	static final Scenario.Setup NONE = ( world, kit ) -> {};

	private static final String SEED_WITHOUT_ROOM = "SCN-1";

	private static final String SEED_WITHOUT_LIBRARY_OR_POOL = "SCN-0";

	static final Predicate< Heap > ANY = heap -> !(heap.peek() instanceof Key);

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( loot( ArmoryRoom.class, heap -> heap.peek() instanceof Weapon || heap.peek() instanceof Armor, "weapon or armour" ));
		all.add( loot( LibraryRoom.class, heap -> heap.peek() instanceof Scroll, "scroll" ).seeds( SEED_WITHOUT_LIBRARY_OR_POOL ));
		all.add( loot( RunestoneRoom.class, heap -> heap.peek() instanceof Runestone, "runestone" ));
		all.add( loot( StorageRoom.class, ANY, "stored item" ));
		all.add( loot( TreasuryRoom.class, heap -> heap.peek() instanceof Gold, "gold" ));
		all.add( crypt() );
		all.add( crystalChoice() );
		all.add( crystalPath() );
		all.add( crystalVault() );
		all.add( pit() );
		all.add( pool().seeds( SEED_WITHOUT_LIBRARY_OR_POOL ));
		all.add( statue() );
		all.add( weakFloor() );
		all.add( armoredStatue() );
		all.addAll( RoomHazards.all() );
		return all;
	}

	static Scenario room( Class< ? extends SpecialRoom > type, int depth, String mechanic, Scenario.Setup extra, Scenario.Script script ){
		Scenario scenario = new Scenario( "room." + type.getSimpleName(), mechanic, HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy();
					RoomVisit.atDoor( world, world.room( type ));
					extra.build( world, kit );
				}, script ).depth( depth ).seeds( SEED_WITHOUT_ROOM ).covers( type );
		return type == DemonSpawnerRoom.class ? scenario : scenario.prepare( RoomForcing.special( type ));
	}

	private static Scenario loot( Class< ? extends SpecialRoom > type, Predicate< Heap > wanted, String what ){
		return room( type, 2, "enter a " + type.getSimpleName() + " and take its " + what, NONE, d -> {
			SpecialRoom room = d.world().room( type );
			RoomVisit.enter( d, room );
			int cell = RoomVisit.heap( room, wanted, what );
			d.expect( RoomVisit.take( d, cell ), "the " + what + " in the " + type.getSimpleName() + " was not picked up" );
		} );
	}

	private static Scenario crypt(){
		return room( CryptRoom.class, 2, "open the tomb in a crypt", NONE, d -> {
			CryptRoom room = d.world().room( CryptRoom.class );
			RoomVisit.enter( d, room );
			int tomb = RoomVisit.heap( room, heap -> heap.type == Heap.Type.TOMB, "tomb" );
			int wraiths = count( Wraith.class );
			RoomVisit.walk( d, tomb, 20 );
			d.move( tomb );
			Heap heap = Dungeon.level.heaps.get( tomb );
			d.expect( heap == null || heap.type != Heap.Type.TOMB, "the tomb stayed shut" );
			d.expect( count( Wraith.class ) > wraiths, "opening the tomb raised no wraith" );
		} );
	}

	private static Scenario crystalChoice(){
		return room( CrystalChoiceRoom.class, 3, "open one of two crystal doors and take what is behind it", crystalKeys( 1 ), d -> {
			CrystalChoiceRoom room = d.world().room( CrystalChoiceRoom.class );
			RoomVisit.enter( d, room );
			openCrystalDoor( d, room );
			d.expect( crystalDoors( room ) == 1, "opening one crystal door left " + crystalDoors( room ) + " shut, not 1" );
			d.expect( RoomVisit.take( d, RoomVisit.heap( room, ANY, "reward behind the crystal door" )),
					"the reward behind the opened crystal door was not picked up" );
		} );
	}

	private static Scenario crystalPath(){
		return room( CrystalPathRoom.class, 3, "walk the crystal path, door by door", crystalKeys( 2 ), d -> {
			CrystalPathRoom room = d.world().room( CrystalPathRoom.class );
			RoomVisit.enter( d, room );
			int shut = crystalDoors( room );
			for (int i = 0; i < 2; i++){
				openCrystalDoor( d, room );
				d.expect( RoomVisit.take( d, RoomVisit.heap( room, ANY, "reward behind crystal door " + (i + 1) )),
						"the reward behind crystal door " + (i + 1) + " was not picked up" );
			}
			d.expect( crystalDoors( room ) == shut - 2, "two keys opened " + (shut - crystalDoors( room )) + " crystal doors, not 2" );
		} );
	}

	private static Scenario crystalVault(){
		return room( CrystalVaultRoom.class, 3, "open a crystal chest in a crystal vault", crystalKeys( 1 ), d -> {
			CrystalVaultRoom room = d.world().room( CrystalVaultRoom.class );
			RoomVisit.enter( d, room );
			int chest = RoomVisit.heap( room, heap -> heap.type == Heap.Type.CRYSTAL_CHEST, "crystal chest" );
			int keys = crystalKeys();
			d.expect( RoomVisit.take( d, chest ), "the crystal chest's prize was not picked up" );
			d.expect( crystalKeys() < keys, "the crystal chest opened without spending the crystal key" );
		} );
	}

	private static Scenario pit(){
		return room( PitRoom.class, 2, "open a pit room's crystal door and loot the remains", NONE, d -> {
			PitRoom room = d.world().room( PitRoom.class );
			RoomVisit.enter( d, room );
			int remains = RoomVisit.heap( room, heap -> heap.type == Heap.Type.SKELETON, "remains" );
			RoomVisit.walk( d, remains, 20 );
			d.move( remains );
			d.until( () -> count( Wraith.class ) == 0, 30, () -> {
				Mob wraith = RoomVisit.besideHero( Wraith.class );
				if (wraith != null) d.move( wraith.pos );
				else d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT );
			});
			d.expect( RoomVisit.take( d, remains ) && RoomVisit.take( d, remains ), "the remains in the pit room were not looted" );
		} );
	}

	private static Scenario pool(){
		return room( PoolRoom.class, 2, "cross a piranha pool for its chest", NONE, d -> {
			PoolRoom room = d.world().room( PoolRoom.class );
			RoomVisit.enter( d, room );
			int chest = RoomVisit.heap( room, heap -> heap.type == Heap.Type.CHEST, "pool chest" );
			int hp = Dungeon.hero.HP;
			d.expect( RoomVisit.take( d, chest ), "the chest across the pool was not picked up" );
			d.expect( Dungeon.hero.HP < hp, "no piranha bit the hero crossing the pool" );
		} );
	}

	private static Scenario statue(){
		return room( StatueRoom.class, 2, "wake the statue in a statue room", NONE, d -> {
			StatueRoom room = d.world().room( StatueRoom.class );
			RoomVisit.enter( d, room );
			com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Statue statue =
					RoomVisit.mob( room, com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Statue.class );
			int hp = statue.HP;
			d.require( statue.state == statue.PASSIVE, "the statue was awake before the hero struck it" );
			RoomVisit.walk( d, statue.pos, 20 );
			d.until( () -> !statue.isAlive() || (statue.HP < hp && statue.state == statue.HUNTING), 12, () -> d.move( statue.pos ));
			d.expect( !statue.isAlive() || statue.HP < hp, "twelve blows left the statue at " + statue.HP + " of " + hp );
			d.expect( !statue.isAlive() || statue.state == statue.HUNTING, "the struck statue never animated" );
		} );
	}

	private static Scenario weakFloor(){
		return room( WeakFloorRoom.class, 2, "fall through a weak floor to the next depth", NONE, d -> {
			WeakFloorRoom room = d.world().room( WeakFloorRoom.class );
			RoomVisit.enter( d, room );
			int chasm = RoomVisit.terrain( room, Terrain.CHASM );
			RoomVisit.walk( d, chasm, 10 );
			d.move( chasm );
			d.require( d.env().mode() == EnvMode.MENU, "stepping onto the weak floor asked nothing: " + d.env().mode() );
			d.menu( 0 );
			d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT );
			d.expect( Dungeon.depth == 3, "falling through the weak floor left the hero on depth " + Dungeon.depth );
		} );
	}

	private static Scenario armoredStatue(){
		return new Scenario( "room.ArmoredStatue", "an armoured statue fights the hero", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					ArmoredStatue statue = new ArmoredStatue();
					statue.createWeapon( false );
					world.hunting( statue, world.adjacentFree() );
				},
				d -> FoeFamily.brawl( d, ArmoredStatue.class, 12, null, "the armoured statue never hurt the hero" ) ).covers( ArmoredStatue.class );
	}

	private static Scenario.Setup crystalKeys( int keys ){
		return ( world, kit ) -> {
			for (int i = 0; i < keys; i++) Notes.add( new CrystalKey( Dungeon.depth ));
		};
	}

	private static int crystalKeys(){
		return Notes.keyCount( new CrystalKey( Dungeon.depth ));
	}

	private static int crystalDoors( SpecialRoom room ){
		int doors = 0;
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (Dungeon.level.map[ cell ] == Terrain.CRYSTAL_DOOR && RoomVisit.inside( room, cell )) doors++;
		}
		return doors;
	}

	private static void openCrystalDoor( Driver d, SpecialRoom room ){
		int door = RoomVisit.terrain( room, Terrain.CRYSTAL_DOOR );
		int keys = crystalKeys();
		RoomVisit.walk( d, door, 20 );
		d.move( door );
		d.waitFor( () -> Dungeon.level.map[ door ] != Terrain.CRYSTAL_DOOR, 3 );
		d.require( Dungeon.level.map[ door ] != Terrain.CRYSTAL_DOOR, "the crystal key did not open the crystal door at " + door );
		d.expect( crystalKeys() < keys, "the crystal door opened without spending a crystal key" );
	}

	static int count( Class< ? extends Mob > type ){
		int count = 0;
		for (Mob mob : Dungeon.level.mobs) if (type.isInstance( mob )) count++;
		return count;
	}

	static boolean newDebuff( java.util.Set< Buff > before ){
		for (Buff buff : Dungeon.hero.buffs()) if (buff.type == Buff.buffType.NEGATIVE && !before.contains( buff )) return true;
		return false;
	}
}
