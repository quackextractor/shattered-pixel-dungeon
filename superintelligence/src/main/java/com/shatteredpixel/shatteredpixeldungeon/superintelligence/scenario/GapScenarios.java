package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Shopkeeper;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.CloakOfShadows;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.EtherealChains;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingStone;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;

import java.util.Arrays;
import java.util.List;

final class GapScenarios {

	private GapScenarios(){}

	static List< Scenario > all(){
		return Arrays.asList( unequip(), cloak(), shopBuy(), throwOnly(), unknownPotionThrow() );
	}

	private static void wornAction( Driver d, Item worn, String what ){
		if (d.slotOf( worn.getClass() ) < 0){
			d.gap( "G5: the worn " + worn.getClass().getSimpleName() + " is in no slot, so the policy cannot " + what );
			d.act( Action.USE );
			if (d.env().mode() != EnvMode.WORLD) d.cancel();
			return;
		}
		d.use( worn.getClass() );
	}

	private static Scenario unequip(){
		return new Scenario( "unequip", "take off worn armour", HeroClass.WARRIOR,
				( world, kit ) -> world.clearMobs(),
				d -> {
					Item armor = Dungeon.hero.belongings.armor;
					wornAction( d, armor, "take it off" );
					d.expect( Dungeon.hero.belongings.armor == armor, "the armour changed without being used" );
				} );
	}

	private static Scenario cloak(){
		return new Scenario( "artifact.Cloak", "activate the Rogue's equipped cloak", HeroClass.ROGUE,
				( world, kit ) -> world.clearMobs(),
				d -> {
					CloakOfShadows cloak = (CloakOfShadows) Dungeon.hero.belongings.artifact;
					d.require( cloak != null, "the Rogue wears no cloak" );
					wornAction( d, cloak, "activate it" );
				} );
	}

	private static Scenario chains(){
		return new Scenario( "artifact.EtherealChains", "cast ethereal chains at an enemy", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new EtherealChains() );
					world.mob( new Rat(), world.cellAtRange( 4, true ));
				},
				d -> {
					EtherealChains chains = Dungeon.hero.belongings.getItem( EtherealChains.class );
					Rat rat = d.world().find( Rat.class );
					d.use( EtherealChains.class );
					int from = rat.pos, hero = Dungeon.hero.pos;
					if (d.env().mode() == EnvMode.TARGETING) d.aimNearest();
					d.expect( rat.pos != from || Dungeon.hero.pos != hero, "the cast moved neither the rat at " + from
							+ " nor the hero at " + hero );
					d.expect( Dungeon.level.adjacent( rat.pos, Dungeon.hero.pos ), "the chains were aimed at a rat 4 cells"
							+ " away and pulled nothing: equipped " + (Dungeon.hero.belongings.artifact == chains) );
					d.expect( Dungeon.hero.belongings.contains( chains ), "using the chains left the hero without them:"
							+ " equipped " + (Dungeon.hero.belongings.artifact == chains) + ", on the floor "
							+ onFloor( chains ));
				} );
	}

	private static boolean onFloor( Item item ){
		for (Heap heap : Dungeon.level.heaps.valueList()) if (heap.items.contains( item )) return true;
		return false;
	}

	private static Scenario shopBuy(){
		return new Scenario( "shop-buy", "buy from a shop", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.gold( 500 );
					world.drop( new Food(), lowDirectionFree( world ) ).type = Heap.Type.FOR_SALE;
					world.mob( new Shopkeeper(), world.adjacentFree() );
				},
				d -> {
					int gold = Dungeon.gold;
					int heap = -1;
					for (int o = 1; o <= 3 && heap < 0; o++){
						Heap h = Dungeon.level.heaps.get( d.env().mapper().targetCell( o ));
						if (h != null && h.type == Heap.Type.FOR_SALE) heap = o;
					}
					d.require( heap > 0, "no item for sale beside the hero" );
					d.step( Action.INTERACT, heap );
					if (GameScene.answerableWindow() != null && !WindowBridge.open()){
						d.gap( "G7: buying opened " + GameScene.answerableWindow().getClass().getSimpleName()
								+ ", which WindowBridge cannot answer" );
					}
					d.waitFor( () -> d.env().mode() == EnvMode.WORLD, 5 );
					d.expect( d.env().mode() == EnvMode.WORLD, "the shop left the env in " + d.env().mode() );
					d.expect( Dungeon.gold == gold, "gold moved from " + gold + " to " + Dungeon.gold );
				} );
	}

	private static int lowDirectionFree( World world ){
		for (int o = 1; o <= 3; o++){
			int[] offset = com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper.TARGET_OFFSETS[ o ];
			int cell = world.hero() + offset[ 0 ] + offset[ 1 ] * Dungeon.level.width();
			if (world.free( cell )) return cell;
		}
		throw new NoFixture( "no free cell north, north-east or east of the hero" );
	}

	private static Scenario throwOnly(){
		return new Scenario( "throw-only", "throw with nothing else in the pack", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().emptyPack();
					kit.give( new ThrowingStone().quantity( 3 ));
					world.mob( new Rat(), world.cellAtRange( 3, true ));
				},
				d -> {
					ThrowingStone stones = Dungeon.hero.belongings.getItem( ThrowingStone.class );
					d.use( ThrowingStone.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the stone did not ask for a target: " + d.env().mode() );
					d.aimNearest();
					d.expect( stones.quantity() == 2, "3 stones and a throw left " + stones.quantity() );
				} );
	}

	private static Scenario unknownPotionThrow(){
		return new Scenario( "potion-unknown-throw", "throw an unidentified potion", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.giveUnknown( new PotionOfToxicGas() );
					world.mob( new Rat(), world.cellAtRange( 3, true ));
				},
				d -> {
					d.use( PotionOfToxicGas.class );
					if (d.env().mode() == EnvMode.TARGETING) d.aimNearest();
					else d.gap( "G8: an unidentified PotionOfToxicGas was drunk; USE never offers a throw" );
				} );
	}
}
