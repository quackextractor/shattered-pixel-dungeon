package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.LeatherArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingStone;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class ModeScenarios {

	private ModeScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( cancel( "SLOT", ( w, k ) -> {}, d -> d.act( Action.USE )));
		all.add( cancel( "INVENTORY", ( w, k ) -> {}, d -> d.act( Action.OPEN_INVENTORY )));
		all.add( cancel( "TALENT", ( w, k ) -> k.level( 2 ), d -> d.act( Action.TALENT )));
		all.add( cancel( "ALCHEMY", ( w, k ) -> {
			w.terrain( w.adjacentFree(), Terrain.ALCHEMY );
			k.energy( 20 );
			k.give( new Sungrass.Seed().quantity( 3 ));
		}, d -> d.act( Action.ALCHEMY )));
		all.add( cancel( "MENU", ( w, k ) -> k.give( new LeatherArmor() ), d -> d.use( LeatherArmor.class )));
		all.add( cancel( "TALENT", "on-stairs", ( w, k ) -> k.level( 2 ), d -> {
			d.require( Dungeon.level.getTransition( Dungeon.hero.pos ) != null, "the hero is not standing on stairs" );
			d.act( Action.TALENT );
		} ).depth( 3 ));
		all.add( cancel( "TALENT", "on-heap", ( w, k ) -> {
			k.level( 2 );
			w.drop( new Food(), w.hero() );
		}, d -> d.act( Action.TALENT )));
		all.add( cancel( "ALCHEMY", "on-heap", ( w, k ) -> {
			w.terrain( w.adjacentFree(), Terrain.ALCHEMY );
			k.energy( 20 );
			k.give( new Sungrass.Seed().quantity( 3 ));
			w.drop( new Food(), w.hero() );
		}, d -> d.act( Action.ALCHEMY )));
		all.add( dropPickup() );
		all.add( aimCancel() );
		all.add( aimEmpty() );
		all.add( aimNoEnemy() );
		all.add( interactDirection() );
		return all;
	}

	private static Scenario cancel( String mode, Scenario.Setup extra, Scenario.Script enter ){
		return cancel( mode, null, extra, enter );
	}

	private static Scenario cancel( String mode, String where, Scenario.Setup extra, Scenario.Script enter ){
		return new Scenario( "cancel." + mode + (where == null ? "" : "." + where), "leave " + mode + " without acting", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					extra.build( world, kit );
				},
				d -> {
					int depth = Dungeon.depth;
					int pos = Dungeon.hero.pos;
					Heap heap = Dungeon.level.heaps.get( pos );
					int size = heap == null ? 0 : heap.size();
					enter.run( d );
					d.require( d.env().mode().name().equals( mode ), "entering " + mode + " left the env in " + d.env().mode() );
					float clock = Actor.now();
					d.cancel();
					d.expect( d.env().mode() == EnvMode.WORLD, "CANCEL in " + mode + " left " + d.env().mode() );
					d.expect( Actor.now() == clock, "CANCEL in " + mode + " spent " + (Actor.now() - clock) + " turns" );
					d.expect( Dungeon.depth == depth && Dungeon.hero.pos == pos, "entering " + mode + " moved the hero from depth "
							+ depth + " cell " + pos + " to depth " + Dungeon.depth + " cell " + Dungeon.hero.pos );
					Heap now = Dungeon.level.heaps.get( pos );
					d.expect( now == heap && (now == null ? 0 : now.size()) == size, "entering " + mode + " disturbed the heap under the hero" );
				} );
	}

	private static Scenario dropPickup(){
		return new Scenario( "drop-pickup", "drop an item and pick it up", HeroClass.WARRIOR,
				( world, kit ) -> world.clearMobs(),
				d -> {
					int here = Dungeon.hero.pos;
					d.drop( Food.class );
					Heap heap = Dungeon.level.heaps.get( here );
					d.expect( heap != null && heap.peek() instanceof Food, "DROP of Food left no Food under the hero" );
					d.move( d.world().adjacentFree() );
					d.move( here );
					if (Dungeon.hero.belongings.getItem( Food.class ) == null) d.step( Action.INTERACT, 0 );
					d.expect( Dungeon.hero.belongings.getItem( Food.class ) != null, "the dropped Food was not picked up again" );
				} );
	}

	private static Scenario aimCancel(){
		return new Scenario( "aim-cancel", "cancel an aimed wand", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new WandOfMagicMissile() );
					world.mob( new Rat(), world.cellAtRange( 3, true ));
				},
				d -> {
					WandOfMagicMissile wand = Dungeon.hero.belongings.getItem( WandOfMagicMissile.class );
					int charges = wand.curCharges;
					d.use( WandOfMagicMissile.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the wand did not ask for a target: " + d.env().mode() );
					float clock = Actor.now();
					d.cancel();
					d.expect( wand.curCharges == charges, "a cancelled zap spent a charge" );
					d.expect( Actor.now() == clock, "a cancelled zap spent " + (Actor.now() - clock) + " turns" );
					d.expect( d.env().mode() == EnvMode.WORLD, "a cancelled zap left " + d.env().mode() );
				} );
	}

	private static Scenario aimEmpty(){
		return new Scenario( "aim-empty", "throw at an empty cell", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					world.mob( new Rat(), world.cellAtRange( 4, true ));
				},
				d -> {
					int target = d.world().adjacentFree();
					d.use( ThrowingStone.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the stone did not ask for a target: " + d.env().mode() );
					d.step( Action.USE, d.direction( target ));
					Heap heap = Dungeon.level.heaps.get( target );
					d.expect( heap != null && heap.peek() instanceof ThrowingStone, "the stone did not land on cell " + target );
				} );
	}

	private static Scenario aimNoEnemy(){
		return new Scenario( "aim-no-enemy", "zap with no enemy in view", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.give( new WandOfMagicMissile() );
				},
				d -> {
					WandOfMagicMissile wand = Dungeon.hero.belongings.getItem( WandOfMagicMissile.class );
					int charges = wand.curCharges;
					int target = d.world().adjacentFree();
					d.use( WandOfMagicMissile.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the wand did not ask for a target: " + d.env().mode() );
					d.step( Action.USE, d.direction( target ));
					d.expect( wand.curCharges == charges - 1, "a zap at cell " + target + " left " + wand.curCharges
							+ " of " + charges + " charges, wand still carried " + Dungeon.hero.belongings.contains( wand ));
				} );
	}

	private static Scenario interactDirection(){
		return new Scenario( "interact-direction", "pick the second of two heaps by moving onto it", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					int[] cells = twoFreeDirections( world );
					world.drop( new Food(), cells[ 0 ] );
					world.drop( new ThrowingStone(), cells[ 1 ] );
				},
				d -> {
					int far = -1;
					for (int o = 8; o >= 1 && far < 0; o--){
						int cell = d.env().mapper().targetCell( o );
						Heap heap = Dungeon.level.heaps.get( cell );
						if (heap != null && heap.peek() instanceof ThrowingStone) far = o;
					}
					d.require( far > 0, "the second heap is not beside the hero" );
					int cell = d.env().mapper().targetCell( far );
					d.move( cell );
					d.expect( Dungeon.hero.pos == cell || Dungeon.level.heaps.get( cell ) == null,
							"a move toward direction " + far + " did not reach cell " + cell );
				} );
	}

	private static int[] twoFreeDirections( World world ){
		int near = -1, far = -1;
		for (int o = 1; o <= 8; o++){
			int[] offset = com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper.TARGET_OFFSETS[ o ];
			int cell = world.hero() + offset[ 0 ] + offset[ 1 ] * Dungeon.level.width();
			if (!world.free( cell )) continue;
			if (near < 0 && o < 5) near = cell;
			else if (o >= 6) far = cell;
		}
		if (near < 0 || far < 0) throw new NoFixture( "no free cell beside the hero both below direction 5 and above 5" );
		return new int[]{ near, far };
	}
}
