package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Freezing;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barkskin;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Healing;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invisibility;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Regeneration;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.WellFed;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Dewdrop;
import com.shatteredpixel.shatteredpixeldungeon.items.EnergyCrystal;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Recipe;
import com.shatteredpixel.shatteredpixeldungeon.items.Waterskin;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Berry;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Blandfruit;
import com.shatteredpixel.shatteredpixeldungeon.items.food.ChargrilledMeat;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.food.FrozenCarpaccio;
import com.shatteredpixel.shatteredpixeldungeon.items.food.MeatPie;
import com.shatteredpixel.shatteredpixeldungeon.items.food.MysteryMeat;
import com.shatteredpixel.shatteredpixeldungeon.items.food.PhantomMeat;
import com.shatteredpixel.shatteredpixeldungeon.items.food.SmallRation;
import com.shatteredpixel.shatteredpixeldungeon.items.food.StewedMeat;
import com.shatteredpixel.shatteredpixeldungeon.items.food.SupplyRation;
import com.shatteredpixel.shatteredpixeldungeon.items.journal.AlchemyPage;
import com.shatteredpixel.shatteredpixeldungeon.items.journal.GuidePage;
import com.shatteredpixel.shatteredpixeldungeon.items.journal.Guidebook;
import com.shatteredpixel.shatteredpixeldungeon.journal.Document;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class FoodScenarios {

	private FoodScenarios(){}

	private static final float HUNGER = Hunger.HUNGRY + 110;

	private static final int WAITS = 3;

	private static final String ALCHEMY_PAGE = "Potions";

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( food( "food.Berry", Berry.class, ( world, kit ) -> kit.give( new Berry().quantity( 2 )), d -> d.use( Berry.class ), WAITS,
				"the second berry dropped no seed under the hero", () -> {
			int seeds = seedsUnderHero();
			return () -> seedsUnderHero() > seeds;
		}));
		all.add( food( "food.PhantomMeat", PhantomMeat.class, given( PhantomMeat::new ), d -> {}, WAITS,
				"the phantom meat gave no invisibility, bark and quarter heal", () -> {
			int hp = Dungeon.hero.HP;
			return () -> Dungeon.hero.buff( Invisibility.class ) != null && Barkskin.currentLevel( Dungeon.hero ) > 0
					&& Dungeon.hero.HP >= hp + Dungeon.hero.HT / 4;
		}));
		all.add( food( "food.SmallRation", SmallRation.class, given( SmallRation::new ), d -> {}, 0, "", null ));
		all.add( food( "food.SupplyRation", SupplyRation.class, given( SupplyRation::new ), d -> {}, WAITS,
				"the supply ration healed the hero less than 5 HP", () -> {
			int hp = Dungeon.hero.HP;
			return () -> Dungeon.hero.HP >= hp + 5;
		}));
		all.add( food( "food.MeatPie", MeatPie.class, ( world, kit ) -> {
			kit.give( new MeatPie() );
			kit.hero().buff( Regeneration.class ).detach();
		}, d -> {}, 25, "the well fed buff healed no HP with regeneration off", () -> {
			int hp = Dungeon.hero.HP;
			return () -> Dungeon.hero.buff( WellFed.class ) != null && Dungeon.hero.HP > hp;
		}).covers( WellFed.class ));
		all.add( food( "food.cook-fruit", Blandfruit.class, pot( Blandfruit::new, Sungrass.Seed::new ),
				d -> brewed( d, Blandfruit.CookFruit.class, Blandfruit.class, 1 ), WAITS, "the sunfruit gave no healing", () -> {
			int hp = Dungeon.hero.HP;
			return () -> Dungeon.hero.buff( Healing.class ) != null && Dungeon.hero.HP >= hp + 20;
		}).covers( Blandfruit.CookFruit.class ));
		all.add( stew( "food.stew-1", StewedMeat.oneMeat.class, 1 ));
		all.add( stew( "food.stew-2", StewedMeat.twoMeat.class, 2 ));
		all.add( stew( "food.stew-3", StewedMeat.threeMeat.class, 3 ));
		all.add( food( "food.chargrill", ChargrilledMeat.class, cooker( Fire.class ), d -> cookOnFloor( d, Fire.class, ChargrilledMeat.class ),
				0, "", null ));
		all.add( food( "food.carpaccio", FrozenCarpaccio.class, ( world, kit ) -> {
			cooker( Freezing.class ).build( world, kit );
			kit.buff( Cripple.class, 50f );
		}, d -> cookOnFloor( d, Freezing.class, FrozenCarpaccio.class ), WAITS, "the frozen carpaccio had none of its four effects", () -> {
			Hero hero = Dungeon.hero;
			int hp = hero.HP;
			return () -> hero.buff( Invisibility.class ) != null || Barkskin.currentLevel( hero ) > 0
					|| hero.buff( Cripple.class ) == null || hero.HP >= hp + hero.HT / 4;
		}).seeds( "SCN-1" ));
		all.add( pickup( "pickup.Gold", () -> new Gold( 50 ), Gold.class, ( world, kit ) -> {}, "the gold did not rise by 50", () -> {
			int gold = Dungeon.gold;
			return () -> Dungeon.gold >= gold + 50;
		}, d -> {} ));
		all.add( pickup( "pickup.Dewdrop", Dewdrop::new, Dewdrop.class, ( world, kit ) -> kit.emptyPack().health( 50, 100 ),
				"the dewdrop healed the hero less than 5 HP", () -> {
			int hp = Dungeon.hero.HP;
			return () -> Dungeon.hero.HP >= hp + 5;
		}, d -> {} ));
		all.add( pickup( "pickup.Waterskin", Dewdrop::new, Dewdrop.class, ( world, kit ) -> kit.health( 50, 100 ),
				"the dewdrop did not go into the waterskin", () -> {
			Waterskin skin = Dungeon.hero.belongings.getItem( Waterskin.class );
			int volume = skin.volume();
			return () -> skin.volume() > volume;
		}, FoodScenarios::drink ).covers( Waterskin.class ));
		all.add( pickup( "pickup.EnergyCrystal", () -> new EnergyCrystal( 5 ), EnergyCrystal.class, ( world, kit ) -> {},
				"the energy did not rise by 5", () -> {
			int energy = Dungeon.energy;
			return () -> Dungeon.energy >= energy + 5;
		}, d -> {} ));
		all.add( pickup( "pickup.AlchemyPage", FoodScenarios::alchemyPage, AlchemyPage.class, ( world, kit ) -> {},
				"the alchemy guide page was not marked found", page( () -> Document.ALCHEMY_GUIDE, ALCHEMY_PAGE ), d -> {} ));
		all.add( pickup( "pickup.GuidePage", FoodScenarios::guidePage, GuidePage.class, ( world, kit ) -> {},
				"the adventurer's guide page was not marked found", page( () -> Document.ADVENTURERS_GUIDE, Document.GUIDE_SEARCHING ), d -> {} ));
		all.add( pickup( "pickup.Guidebook", Guidebook::new, Guidebook.class, ( world, kit ) -> {},
				"the guidebook did not mark its intro page found", page( () -> Document.ADVENTURERS_GUIDE, Document.GUIDE_INTRO ), d -> {} ));
		return all;
	}

	private static Scenario food( String id, Class< ? extends Food > type, Scenario.Setup arena, Scenario.Script prepare, int waits,
			String failure, Supplier< BooleanSupplier > arm ){
		return new Scenario( id, "eat " + type.getSimpleName() + " hungry", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.health( 50, 100 );
					kit.hero().buff( Hunger.class ).affectHunger( -HUNGER );
					arena.build( world, kit );
				},
				d -> {
					prepare.run( d );
					Hunger hunger = Dungeon.hero.buff( Hunger.class );
					int before = hunger.hunger();
					d.require( before >= Hunger.HUNGRY, "the hero is not hungry before the " + type.getSimpleName() + ": " + before );
					BooleanSupplier extra = arm == null ? null : arm.get();
					d.require( extra == null || !extra.getAsBoolean(), "the effect held before the " + type.getSimpleName() + " was eaten" );
					d.use( type );
					d.expect( hunger.hunger() < before, "eating the " + type.getSimpleName() + " left hunger at " + hunger.hunger()
							+ " from " + before );
					if (extra != null) d.expect( d.until( extra, waits, () -> d.act( Action.WAIT )), failure );
				} ).covers( type );
	}

	private static Scenario stew( String id, Class< ? extends Recipe > recipe, int meat ){
		return food( id, StewedMeat.class, pot( () -> new MysteryMeat().quantity( meat )),
				d -> brewed( d, recipe, StewedMeat.class, meat ), 0, "", null ).covers( recipe );
	}

	private static Scenario.Setup given( Supplier< Item > item ){
		return ( world, kit ) -> kit.give( item.get() );
	}

	@SafeVarargs
	static Scenario.Setup pot( Supplier< Item >... ingredients ){
		return ( world, kit ) -> {
			kit.emptyPack().energy( 20 );
			for (Supplier< Item > ingredient : ingredients) kit.give( ingredient.get() );
			world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
		};
	}

	static void brewed( Driver d, Class< ? extends Recipe > recipe, Class< ? extends Item > product, int quantity ){
		int crafted = Statistics.itemsCrafted;
		d.brewRecipe( recipe );
		d.require( Statistics.itemsCrafted == crafted + 1, "itemsCrafted moved by " + (Statistics.itemsCrafted - crafted) );
		d.act( Action.WAIT );
		Item made = Dungeon.hero.belongings.getItem( product );
		d.require( made != null && made.quantity() == quantity, recipe.getSimpleName() + " made " + made + " instead of "
				+ quantity + " " + product.getSimpleName() );
	}

	private static Scenario.Setup cooker( Class< ? extends Blob > blob ){
		return ( world, kit ) -> {
			kit.give( new MysteryMeat() );
			world.blob( blob, world.adjacentFreeStraight(), 6 );
		};
	}

	private static void cookOnFloor( Driver d, Class< ? extends Blob > blob, Class< ? extends Food > product ){
		int cell = -1;
		for (int offset : PathFinder.NEIGHBOURS8) if (cell < 0 && blobAt( blob, Dungeon.hero.pos + offset ) > 0) cell = Dungeon.hero.pos + offset;
		d.require( cell >= 0, "no " + blob.getSimpleName() + " beside the hero" );
		int at = cell;
		d.throwItem( MysteryMeat.class );
		d.require( d.env().mode() == EnvMode.TARGETING, "the meat did not ask for a target: " + d.env().mode() );
		d.step( Action.USE, d.direction( at ));
		d.require( d.until( () -> heaped( at, product ) && blobAt( blob, at ) == 0, 10, () -> d.act( Action.WAIT )),
				"the meat thrown into " + blob.getSimpleName() + " at " + at + " never became " + product.getSimpleName() );
		d.move( at );
		d.require( Dungeon.hero.belongings.getItem( product ) != null, "the hero did not pick up the " + product.getSimpleName() );
	}

	private static Scenario pickup( String id, Supplier< Item > item, Class< ? extends Item > type, Scenario.Setup arena, String failure,
			Supplier< BooleanSupplier > arm, Scenario.Script after ){
		return new Scenario( id, "pick up " + type.getSimpleName() + " from the floor", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					arena.build( world, kit );
					world.drop( item.get(), world.adjacentFreeStraight() );
				},
				d -> {
					int cell = -1;
					for (int offset : PathFinder.NEIGHBOURS8) if (cell < 0 && heaped( Dungeon.hero.pos + offset, type )) cell = Dungeon.hero.pos + offset;
					d.require( cell >= 0, "no " + type.getSimpleName() + " beside the hero" );
					BooleanSupplier holds = arm.get();
					d.require( !holds.getAsBoolean(), "the effect held before the " + type.getSimpleName() + " was picked up" );
					d.move( cell );
					d.expect( !heaped( cell, type ), "the " + type.getSimpleName() + " at " + cell + " was not picked up" );
					d.expect( holds.getAsBoolean(), failure );
					after.run( d );
				} ).covers( type );
	}

	private static void drink( Driver d ){
		Waterskin skin = Dungeon.hero.belongings.getItem( Waterskin.class );
		int volume = skin.volume();
		int hp = Dungeon.hero.HP;
		d.use( Waterskin.class );
		d.expect( skin.volume() < volume && Dungeon.hero.HP >= hp + 5, "drinking the waterskin left volume " + skin.volume()
				+ " from " + volume + " and HP " + Dungeon.hero.HP + " from " + hp );
	}

	private static Item alchemyPage(){
		AlchemyPage page = new AlchemyPage();
		page.page( ALCHEMY_PAGE );
		return page;
	}

	private static Item guidePage(){
		GuidePage page = new GuidePage();
		page.page( Document.GUIDE_SEARCHING );
		return page;
	}

	private static Supplier< BooleanSupplier > page( Supplier< Document > document, String page ){
		return () -> () -> document.get().isPageFound( page );
	}

	private static boolean heaped( int cell, Class< ? extends Item > type ){
		Heap heap = Dungeon.level.heaps.get( cell );
		if (heap != null) for (Item item : heap.items) if (type.isInstance( item )) return true;
		return false;
	}

	private static int seedsUnderHero(){
		Heap heap = Dungeon.level.heaps.get( Dungeon.hero.pos );
		int seeds = 0;
		if (heap != null) for (Item item : heap.items) if (item instanceof Plant.Seed) seeds++;
		return seeds;
	}

	private static int blobAt( Class< ? extends Blob > type, int cell ){
		Blob blob = Dungeon.level.blobs.get( type );
		return blob == null ? 0 : blob.cur[ cell ];
	}
}
