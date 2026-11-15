package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.AdrenalineSurge;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bless;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Frost;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FrostImbue;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Recharging;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Vertigo;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Blandfruit;
import com.shatteredpixel.shatteredpixeldungeon.plants.BlandfruitBush;
import com.shatteredpixel.shatteredpixeldungeon.plants.Blindweed;
import com.shatteredpixel.shatteredpixeldungeon.plants.Earthroot;
import com.shatteredpixel.shatteredpixeldungeon.plants.Fadeleaf;
import com.shatteredpixel.shatteredpixeldungeon.plants.Firebloom;
import com.shatteredpixel.shatteredpixeldungeon.plants.Icecap;
import com.shatteredpixel.shatteredpixeldungeon.plants.Mageroyal;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.plants.Rotberry;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sorrowmoss;
import com.shatteredpixel.shatteredpixeldungeon.plants.Starflower;
import com.shatteredpixel.shatteredpixeldungeon.plants.Stormvine;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.plants.Swiftthistle;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

final class PlantScenarios {

	private PlantScenarios(){}

	private static final int WAITS = 10;

	interface Arm {
		BooleanSupplier arm( int cell );
	}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( seed( BlandfruitBush.Seed.class, "the bush dropped no blandfruit", cell -> {
			int fruit = blandfruit();
			return () -> blandfruit() > fruit;
		}));
		all.add( seed( Blindweed.Seed.class, "the blindweed never blinded the hero", has( Blindness.class )));
		all.add( seed( Earthroot.Seed.class, "the earthroot gave the hero no bark armour", has( Earthroot.Armor.class )));
		all.add( seed( Fadeleaf.Seed.class, "the fadeleaf left the hero beside its cell",
				cell -> () -> Dungeon.level.distance( Dungeon.hero.pos, cell ) > 1 ));
		all.add( seed( Icecap.Seed.class, HeroClass.WARRIOR, PlantScenarios::frostArena, "the icecap never froze the rat beside it", cell -> {
			Mob rat = new World().find( Rat.class );
			return () -> rat.buff( Frost.class ) != null && rat.paralysed > 0;
		}));
		all.add( seed( Mageroyal.Seed.class, HeroClass.WARRIOR, ( world, kit ) -> kit.buff( Poison.class ).set( 50 ),
				"the mageroyal left a negative buff on the hero", cell -> () -> !negative() ));
		all.add( seed( Rotberry.Seed.class, HeroClass.HUNTRESS, PlantScenarios::warden, "the rotberry gave the warden no strength", cell -> {
			int str = Dungeon.hero.STR();
			return () -> Dungeon.hero.buff( AdrenalineSurge.class ) != null && Dungeon.hero.STR() > str;
		}).covers( AdrenalineSurge.class ));
		all.add( seed( Starflower.Seed.class, HeroClass.HUNTRESS, PlantScenarios::warden, "the starflower neither blessed nor recharged the warden",
				cell -> () -> Dungeon.hero.buff( Bless.class ) != null && Dungeon.hero.buff( Recharging.class ) != null ));
		all.add( seed( Stormvine.Seed.class, "the stormvine never gave the hero vertigo", has( Vertigo.class )));
		all.add( seed( Swiftthistle.Seed.class, "the swiftthistle gave the hero no time bubble", has( Swiftthistle.TimeBubble.class )));
		all.add( seed( Sungrass.Seed.class, HeroClass.WARRIOR, ( world, kit ) -> kit.health( 100, 1000 ),
				"the sungrass never healed the hero 20 HP", cell -> {
			int hp = Dungeon.hero.HP;
			return () -> Dungeon.hero.buff( Sungrass.Health.class ) != null && Dungeon.hero.HP >= hp + 20;
		}));
		all.add( seed( Firebloom.Seed.class, "the firebloom lit no fire", cell -> () -> volume( Fire.class ) > 0 ));
		all.add( seed( Sorrowmoss.Seed.class, "the sorrowmoss never poisoned the hero", has( Poison.class )));
		return all;
	}

	private static Scenario seed( Class< ? extends Plant.Seed > type, String failure, Arm arm ){
		return seed( type, HeroClass.WARRIOR, ( world, kit ) -> {}, failure, arm );
	}

	private static Scenario seed( Class< ? extends Plant.Seed > type, HeroClass hero, Scenario.Setup arena, String failure, Arm arm ){
		Class< ? > plant = type.getEnclosingClass();
		String name = plant.getSimpleName();
		return new Scenario( "seed." + name, "throw a " + name + " seed and step on the plant", hero,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( Reflection.newInstance( type ));
					arena.build( world, kit );
				},
				d -> {
					int cell = d.world().adjacentFreeStraight();
					d.throwItem( type );
					d.require( d.env().mode() == EnvMode.TARGETING, "the seed did not ask for a target: " + d.env().mode() );
					d.step( Action.USE, d.direction( cell ));
					d.require( plant.isInstance( Dungeon.level.plants.get( cell )), "no " + name + " grew at " + cell + ": " + Dungeon.level.plants.get( cell ));
					BooleanSupplier holds = arm.arm( cell );
					d.require( !holds.getAsBoolean(), "the effect held before the hero stepped on the " + name );
					d.move( cell );
					d.expect( Dungeon.level.plants.get( cell ) == null, "the " + name + " at " + cell + " was not triggered, hero at " + Dungeon.hero.pos );
					d.expect( d.until( holds, WAITS, () -> d.act( Action.WAIT )), failure );
				} ).covers( plant, type );
	}

	private static Arm has( Class< ? extends Buff > buff ){
		return cell -> () -> Dungeon.hero.buff( buff ) != null;
	}

	private static void warden( World world, HeroKit kit ){
		kit.subclass( HeroSubClass.WARDEN );
	}

	private static void frostArena( World world, HeroKit kit ){
		kit.buff( FrostImbue.class, 100f );
		int cell = world.adjacentFreeStraight();
		for (int offset : PathFinder.NEIGHBOURS8){
			int near = cell + offset;
			if (world.free( near ) && !world.level().adjacent( near, world.hero() )){
				Rat rat = world.mob( new Rat(), near );
				rat.state = rat.PASSIVE;
				return;
			}
		}
		throw new NoFixture( "no free cell beside the icecap's cell away from the hero" );
	}

	private static int blandfruit(){
		int fruit = 0;
		for (Heap heap : Dungeon.level.heaps.valueList()) for (Object item : heap.items) if (item instanceof Blandfruit) fruit++;
		return fruit;
	}

	private static boolean negative(){
		for (Buff buff : Dungeon.hero.buffs()) if (buff.type == Buff.buffType.NEGATIVE) return true;
		return false;
	}

	private static int volume( Class< ? extends Blob > type ){
		Blob blob = Dungeon.level.blobs.get( type );
		return blob == null ? 0 : blob.volume;
	}
}
