package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Alchemy;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Foliage;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.SacrificialFire;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Awareness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Shadows;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DemonSpawner;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfFrost;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WarHammer;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.DemonSpawnerRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.GardenRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.LaboratoryRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.MagicWellRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.MagicalFireRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SacrificeRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SentryRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SpecialRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.ToxicGasRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.TrapsRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.plants.Firebloom;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sorrowmoss;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.RoomScenarios.NONE;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.RoomScenarios.room;

final class RoomHazards {

	private RoomHazards(){}

	private static final int SACRIFICES = 4;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( demonSpawner() );
		all.add( garden().covers( Foliage.class, Shadows.class ));
		all.add( laboratory().covers( Alchemy.class ));
		all.add( magicWell() );
		all.add( magicalFire() );
		all.add( sacrifice().covers( SacrificialFire.class ));
		all.add( sentry().covers( SentryRoom.Sentry.class ));
		all.add( toxicGas() );
		all.add( traps() );
		return all;
	}

	private static Scenario demonSpawner(){
		return room( DemonSpawnerRoom.class, 22, "break the demon spawner in its room", ( world, kit ) -> {
			WarHammer hammer = new WarHammer();
			hammer.upgrade( 10 ).identify();
			kit.strength( 20 ).hero().belongings.weapon = hammer;
		}, d -> {
			RoomVisit.enter( d, d.world().room( DemonSpawnerRoom.class ));
			DemonSpawner spawner = d.world().find( DemonSpawner.class );
			RoomVisit.walk( d, spawner.pos, 20 );
			d.until( () -> !spawner.isAlive(), 20, () -> d.move( spawner.pos ));
			d.expect( !spawner.isAlive(), "twenty blows left the demon spawner at " + spawner.HP + " HP" );
		} );
	}

	private static Scenario garden(){
		return room( GardenRoom.class, 2, "stand in a garden's foliage and melt into its shadows", NONE, d -> {
			Hero hero = Dungeon.hero;
			Blob foliage = Dungeon.level.blobs.get( Foliage.class );
			d.require( foliage != null && foliage.volume > 0, "the garden grew no foliage" );
			d.require( hero.invisible == 0 && hero.buff( Shadows.class ) == null, "the hero was hidden before the garden" );
			int start = hero.pos;
			SpecialRoom room = d.world().room( GardenRoom.class );
			RoomVisit.enter( d, room );
			d.waitFor( () -> hero.buff( Shadows.class ) != null, 2 );
			d.expect( hero.buff( Shadows.class ) != null && hero.invisible > 0, "standing in the foliage cast no shadows over the hero" );
			d.move( RoomVisit.door( room ));
			d.move( start );
			d.waitFor( () -> hero.buff( Shadows.class ) == null, 4 );
			d.expect( hero.buff( Shadows.class ) == null && hero.invisible == 0, "the shadows outlasted the foliage by four turns" );
		} );
	}

	private static Scenario laboratory(){
		return room( LaboratoryRoom.class, 2, "brew at a laboratory's alchemy pot", ( world, kit ) -> {
			kit.emptyPack().energy( 20 );
			kit.give( new Sungrass.Seed() );
			kit.give( new Firebloom.Seed() );
			kit.give( new Sorrowmoss.Seed() );
		}, d -> {
			SpecialRoom room = d.world().room( LaboratoryRoom.class );
			d.require( !Notes.contains( Notes.Landmark.ALCHEMY ), "the alchemy pot was in the journal before the hero saw the laboratory" );
			RoomVisit.enter( d, room );
			d.expect( Notes.contains( Notes.Landmark.ALCHEMY ), "seeing the laboratory's alchemy did not note the pot in the journal" );
			int pot = RoomVisit.terrain( room, Terrain.ALCHEMY );
			RoomVisit.walk( d, pot, 20 );
			int crafted = Statistics.itemsCrafted;
			int potions = potions();
			d.brewRecipe( Potion.SeedToPotion.class );
			d.expect( Statistics.itemsCrafted == crafted + 1, "brewing at the laboratory pot moved itemsCrafted by "
					+ (Statistics.itemsCrafted - crafted) );
			d.expect( potions() > potions, "brewing three seeds at the laboratory pot gave no potion" );
		} );
	}

	private static int potions(){
		int count = 0;
		for (Item item : Dungeon.hero.belongings) if (item instanceof Potion) count += item.quantity();
		return count;
	}

	private static Scenario magicWell(){
		return room( MagicWellRoom.class, 2, "drink from a magic well", ( world, kit ) -> kit.health( 10, 1000 ), d -> {
			Hero hero = Dungeon.hero;
			SpecialRoom room = d.world().room( MagicWellRoom.class );
			RoomVisit.enter( d, room );
			int well = RoomVisit.terrain( room, Terrain.WELL );
			RoomVisit.walk( d, well, 20 );
			d.require( hero.HP < hero.HT && hero.buff( Awareness.class ) == null, "the hero was healed or aware before drinking" );
			d.move( well );
			d.expect( Dungeon.level.map[ well ] == Terrain.EMPTY_WELL, "the magic well was not drunk: terrain " + Dungeon.level.map[ well ] );
			d.expect( hero.HP == hero.HT || hero.buff( Awareness.class ) != null, "the magic well neither healed nor revealed" );
		} );
	}

	private static Scenario magicalFire(){
		return room( MagicalFireRoom.class, 2, "put out a magical fire with frost and take what it guarded",
				( world, kit ) -> kit.give( new PotionOfFrost() ), d -> {
			SpecialRoom room = d.world().room( MagicalFireRoom.class );
			RoomVisit.enter( d, room );
			Blob fire = Dungeon.level.blobs.get( MagicalFireRoom.EternalFire.class );
			d.require( fire != null && fire.volume > 0, "the magical fire room has no fire" );
			List< Integer > flames = new ArrayList<>();
			for (int cell = 0; cell < fire.cur.length; cell++) if (fire.cur[ cell ] > 0 && RoomVisit.inside( room, cell )) flames.add( cell );
			int flame = RoomVisit.nearest( flames, "magical flame" );
			Set< Integer > guarded = new HashSet<>();
			for (Heap heap : Dungeon.level.heaps.valueList()){
				if (RoomVisit.inside( room, heap.pos ) && !RoomVisit.reachable( heap.pos )) guarded.add( heap.pos );
			}
			RoomVisit.walk( d, flame, 20 );
			d.throwItem( PotionOfFrost.class );
			d.step( Action.USE, d.direction( flame ));
			d.waitFor( () -> fire.volume == 0, 4 );
			d.expect( fire.volume == 0, "the frost left the magical fire burning" );
			int cell = RoomVisit.heap( room, heap -> guarded.contains( heap.pos ), "item the fire guarded" );
			d.expect( RoomVisit.take( d, cell ), "the item behind the magical fire was not picked up" );
		} );
	}

	private static Scenario sacrifice(){
		return room( SacrificeRoom.class, 2, "sacrifice rats on an altar until it gives its reward", ( world, kit ) -> {
			int altar = altar( world.room( SacrificeRoom.class ));
			int placed = 0;
			for (int offset : PathFinder.NEIGHBOURS8){
				if (placed < SACRIFICES && world.free( altar + offset )){
					Rat rat = world.mob( new Rat(), altar + offset );
					rat.state = rat.PASSIVE;
					rat.HP = 1;
					placed++;
				}
			}
			if (placed < SACRIFICES) throw new NoFixture( "no room for " + SACRIFICES + " rats around the altar" );
		}, d -> {
			Blob fire = Dungeon.level.blobs.get( SacrificialFire.class );
			SpecialRoom room = d.world().room( SacrificeRoom.class );
			int altar = altar( room );
			int volume = fire.volume;
			d.require( Dungeon.level.heaps.get( altar ) == null, "the altar held a reward before any sacrifice" );
			RoomVisit.enter( d, room );
			RoomVisit.walk( d, altar, 20 );
			if (Dungeon.hero.pos != altar) d.move( altar );
			d.until( () -> Dungeon.level.heaps.get( altar ) != null, 16, () -> {
				Mob rat = RoomVisit.besideHero( Rat.class );
				if (rat != null) d.move( rat.pos );
				else d.act( Action.WAIT );
			});
			d.expect( fire.volume < volume, "the sacrifices left the fire at " + fire.volume + " of " + volume );
			d.expect( Dungeon.level.heaps.get( altar ) != null, SACRIFICES + " rats slain on the altar earned no reward" );
		} );
	}

	private static int altar( SpecialRoom room ){
		Blob fire = Dungeon.level.blobs.get( SacrificialFire.class );
		if (fire != null) for (int cell = 0; cell < fire.cur.length; cell++) if (fire.cur[ cell ] > 0 && RoomVisit.inside( room, cell )) return cell;
		throw new NoFixture( "the sacrifice room has no sacrificial fire" );
	}


	private static Scenario sentry(){
		return room( SentryRoom.class, 2, "walk past a sentry and take its zap", NONE, d -> {
			SpecialRoom room = d.world().room( SentryRoom.class );
			RoomVisit.enter( d, room );
			SentryRoom.Sentry sentry = RoomVisit.mob( room, SentryRoom.Sentry.class );
			int hp = Dungeon.hero.HP;
			d.until( () -> Dungeon.hero.HP < hp, 14, () -> {
				if (Dungeon.level.adjacent( Dungeon.hero.pos, sentry.pos )) d.act( Action.WAIT );
				else d.move( PathFinder.getStep( Dungeon.hero.pos, sentry.pos, RoomVisit.walkable( sentry.pos )));
			});
			d.expect( Dungeon.hero.HP < hp, "the sentry never zapped the hero walking past it" );
		} );
	}

	private static Scenario toxicGas(){
		return room( ToxicGasRoom.class, 2, "brave a toxic gas room for its gold", NONE, d -> {
			Blob gas = Dungeon.level.blobs.get( ToxicGas.class );
			d.require( gas != null && gas.volume > 0, "the toxic gas room holds no gas" );
			SpecialRoom room = d.world().room( ToxicGasRoom.class );
			RoomVisit.enter( d, room );
			int hp = Dungeon.hero.HP;
			d.expect( RoomVisit.take( d, RoomVisit.heap( room, RoomScenarios.ANY, "treasure" )), "the treasure in the gas was not picked up" );
			d.expect( Dungeon.hero.HP < hp, "the toxic gas never hurt the hero" );
		} );
	}

	private static Scenario traps(){
		return room( TrapsRoom.class, 2, "step onto a trap in a traps room", ( world, kit ) -> {
			Trap trap = world.level().traps.get( RoomVisit.insideDoor( world.room( TrapsRoom.class )));
			if (trap == null || !trap.active) throw new NoFixture( "the traps room has no trap inside its door" );
		}, d -> {
			SpecialRoom room = d.world().room( TrapsRoom.class );
			int inside = RoomVisit.insideDoor( room );
			Trap trap = Dungeon.level.traps.get( inside );
			d.move( RoomVisit.door( room ));
			d.require( trap.active, "the " + trap.name() + " was spent before the hero stepped on it" );
			Set< Buff > buffs = new HashSet<>( Dungeon.hero.buffs() );
			int depth = Dungeon.depth;
			d.move( inside );
			d.expect( !trap.active || Dungeon.depth != depth || RoomScenarios.newDebuff( buffs ),
					"stepping onto the " + trap.name() + " set nothing off" );
		} );
	}
}
