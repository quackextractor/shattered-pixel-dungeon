package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.WaterOfAwareness;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.WaterOfHealth;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Awareness;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.Firebloom;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sorrowmoss;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class FeatureScenarios {

	private FeatureScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( well( "well.Health", WaterOfHealth.class ));
		all.add( well( "well.Awareness", WaterOfAwareness.class ));
		all.add( chasm() );
		all.add( chasmThenMove() );
		all.add( grass() );
		all.add( plant( "plant.Sungrass", Sungrass.Seed::new ));
		all.add( plant( "plant.Firebloom", Firebloom.Seed::new ));
		all.add( plant( "plant.Sorrowmoss", Sorrowmoss.Seed::new ));
		return all;
	}

	private static Scenario well( String id, Class< ? extends com.shatteredpixel.shatteredpixeldungeon.actors.blobs.WellWater > water ){
		return new Scenario( id, "drink from a well", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.health( 10, 1000 );
					int cell = world.adjacentFreeStraight();
					world.terrain( cell, Terrain.WELL );
					world.blob( water, cell, 1 );
				},
				d -> {
					int cell = d.world().beside( Terrain.WELL );
					d.move( cell );
					d.expect( Dungeon.level.map[ cell ] == Terrain.EMPTY_WELL, "the well was not drunk: terrain " + Dungeon.level.map[ cell ] );
					if (water == WaterOfAwareness.class) d.expect( Dungeon.hero.buff( Awareness.class ) != null,
							"Water of Awareness gave no Awareness" );
					else d.expect( Dungeon.hero.HP == Dungeon.hero.HT, "Water of Health left the hero at " + Dungeon.hero.HP );
				} );
	}

	private static Scenario chasm(){
		return new Scenario( "chasm", "jump into a chasm", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					world.terrain( world.adjacentFreeStraight(), Terrain.CHASM );
				},
				d -> {
					int chasm = d.world().beside( Terrain.CHASM );
					d.move( chasm );
					d.require( d.env().mode() == EnvMode.MENU, "stepping at the chasm asked nothing: " + d.env().mode() );
					d.expect( Dungeon.hero.pos != chasm, "the hero entered the chasm without being asked" );
					d.menu( 0 );
					d.act( Action.WAIT );
					d.expect( Dungeon.depth == 2, "answering yes left the hero on depth " + Dungeon.depth + " at " + Dungeon.hero.pos );
				} );
	}

	private static Scenario chasmThenMove(){
		return new Scenario( "chasm-then-move", "jump into a chasm and act at once on the floor below", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					world.terrain( world.adjacentFreeStraight(), Terrain.CHASM );
				},
				d -> {
					int chasm = d.world().beside( Terrain.CHASM );
					d.move( chasm );
					d.require( d.env().mode() == EnvMode.MENU, "stepping at the chasm asked nothing: " + d.env().mode() );
					d.menu( 0 );
					d.move( d.world().adjacentFree() );
					d.act( Action.WAIT );
					d.act( Action.WAIT );
					d.expect( Dungeon.depth == 2, "the jump left the hero on depth " + Dungeon.depth );
				} );
	}

	private static Scenario grass(){
		return new Scenario( "grass", "trample high grass", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					world.terrain( world.adjacentFreeStraight(), Terrain.HIGH_GRASS );
				},
				d -> {
					int cell = d.world().beside( Terrain.HIGH_GRASS );
					d.move( cell );
					d.expect( Dungeon.level.map[ cell ] == Terrain.GRASS, "high grass was not trampled: terrain " + Dungeon.level.map[ cell ] );
				} );
	}

	private static Scenario plant( String id, Supplier< Plant.Seed > seed ){
		return new Scenario( id, "step on a plant", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Dungeon.level.plant( seed.get(), world.adjacentFreeStraight() );
				},
				d -> {
					int cell = -1;
					for (int offset : com.watabou.utils.PathFinder.NEIGHBOURS8){
						if (Dungeon.level.plants.get( Dungeon.hero.pos + offset ) != null) cell = Dungeon.hero.pos + offset;
					}
					d.require( cell >= 0, "no plant beside the hero" );
					d.move( cell );
					d.expect( Dungeon.level.plants.get( cell ) == null, "the plant was not triggered" );
				} );
	}
}
