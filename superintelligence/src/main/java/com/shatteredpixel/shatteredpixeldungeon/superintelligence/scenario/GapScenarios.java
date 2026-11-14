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
		return Arrays.asList( unequip(), cloak(), cloakAlone(), chains(), shopBuy(), throwOnly(), unknownPotionThrow() );
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
					d.expect( Dungeon.hero.belongings.armor == null && Dungeon.hero.belongings.contains( armor ),
							"using the worn armour left it " + (Dungeon.hero.belongings.armor == armor ? "worn" : "gone") );
				} );
	}

	private static Scenario cloak(){
		return new Scenario( "artifact.Cloak", "activate the Rogue's equipped cloak", HeroClass.ROGUE,
				( world, kit ) -> world.clearMobs(),
				d -> {
					CloakOfShadows cloak = (CloakOfShadows) Dungeon.hero.belongings.artifact;
					d.require( cloak != null, "the Rogue wears no cloak" );
					wornAction( d, cloak, "activate it" );
					d.expect( Dungeon.hero.belongings.artifact == cloak && Dungeon.hero.invisible > 0,
							"using the worn cloak did not hide the hero: worn " + (Dungeon.hero.belongings.artifact == cloak) );
				} );
	}

	private static Scenario cloakAlone(){
		return new Scenario( "artifact.Cloak-alone", "activate the worn cloak with nothing else to use", HeroClass.ROGUE,
				( world, kit ) -> {
					world.clearMobs();
					kit.emptyPack();
				},
				d -> {
					CloakOfShadows cloak = (CloakOfShadows) Dungeon.hero.belongings.artifact;
					d.require( cloak != null, "the Rogue wears no cloak" );
					wornAction( d, cloak, "activate it" );
					d.expect( Dungeon.hero.invisible > 0, "using the worn cloak with an empty pack did not hide the hero" );
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
					d.require( Dungeon.hero.belongings.artifact == chains, "USE did not equip the chains" );
					d.require( d.env().mode() == EnvMode.WORLD, "equipping the chains asked for a target: " + d.env().mode() );
					int from = rat.pos, hero = Dungeon.hero.pos;
					d.use( EtherealChains.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the worn chains did not ask for a target: " + d.env().mode() );
					d.aimNearest();
					d.expect( Dungeon.hero.belongings.contains( chains ), "casting the chains left the hero without them" );
					d.expect( rat.pos != from || Dungeon.hero.pos != hero, "casting the worn chains at the rat moved nothing" );
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
					d.require( d.env().mode() == EnvMode.MENU, "the trade window did not reach the agent: " + d.env().mode() );
					d.menu( 0 );
					d.expect( d.env().mode() == EnvMode.WORLD, "the shop left the env in " + d.env().mode() );
					d.expect( Dungeon.gold < gold, "buying left gold at " + Dungeon.gold );
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
					d.throwItem( ThrowingStone.class );
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
					d.throwItem( PotionOfToxicGas.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the potion did not ask for a target: " + d.env().mode() );
					d.aimNearest();
					d.expect( Dungeon.hero.belongings.getItem( PotionOfToxicGas.class ) == null, "the potion is still carried" );
					d.expect( Dungeon.level.blobs.get( com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas.class ) != null,
							"the thrown potion released no gas" );
				} );
	}
}
