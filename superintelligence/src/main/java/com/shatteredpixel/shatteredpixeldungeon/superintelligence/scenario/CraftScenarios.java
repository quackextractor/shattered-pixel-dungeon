package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClothArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.food.MeatPie;
import com.shatteredpixel.shatteredpixeldungeon.items.food.MysteryMeat;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Pasty;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.ExoticPotion;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfAccuracy;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRemoveCurse;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTransmutation;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ExoticScroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfEnchantment;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Dagger;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.Firebloom;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sorrowmoss;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class CraftScenarios {

	private CraftScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( picker( "picker.Upgrade", ScrollOfUpgrade.class, null, false, ClothArmor.class,
				item -> item.level() == 1, "the armour is not +1" ));
		all.add( picker( "picker.Identify", ScrollOfIdentify.class, RingOfAccuracy::new, true, RingOfAccuracy.class,
				Item::isIdentified, "the ring is not identified" ));
		all.add( picker( "picker.RemoveCurse", ScrollOfRemoveCurse.class, CraftScenarios::cursedDagger, true, Dagger.class,
				item -> !item.cursed, "the dagger is still cursed" ));
		all.add( picker( "picker.Transmutation", ScrollOfTransmutation.class, Dagger::new, false, Dagger.class,
				item -> !Dungeon.hero.belongings.contains( item ), "the dagger is still carried" ));
		all.add( picker( "picker.Enchantment", ScrollOfEnchantment.class, Dagger::new, false, Dagger.class,
				item -> ((Dagger) item).enchantment != null, "the dagger has no enchantment" ));
		all.add( pickerCancel() );
		all.add( brew( "brew.seeds", Potion.class, Sungrass.Seed::new, Firebloom.Seed::new, Sorrowmoss.Seed::new ));
		all.add( brew( "brew.exotic-potion", ExoticPotion.class, PotionOfHealing::new ));
		all.add( brew( "brew.exotic-scroll", ExoticScroll.class, ScrollOfIdentify::new ));
		all.add( brew( "brew.meat-pie", MeatPie.class, Pasty::new, Food::new, MysteryMeat::new ));
		return all;
	}

	private static Dagger cursedDagger(){
		Dagger dagger = new Dagger();
		dagger.cursed = true;
		return dagger;
	}

	private static Scenario picker( String id, Class< ? extends Item > scroll, Supplier< Item > target, boolean unknown,
			Class< ? extends Item > pick, Predicate< Item > done, String failure ){
		return new Scenario( id, "pick a target for " + scroll.getSimpleName(), HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.give( Reflection.newInstance( scroll ));
					if (target != null && unknown) kit.giveUnknown( target.get() );
					else if (target != null) kit.give( target.get() );
				},
				d -> {
					d.use( scroll );
					d.require( d.env().mode() == EnvMode.INVENTORY, scroll.getSimpleName() + " offered no picker: " + d.env().mode() );
					Item chosen = d.env().mapper().slot( d.slotOf( pick ));
					d.pick( pick );
					if (d.env().mode() == EnvMode.MENU) d.menu( 0 );
					d.expect( done.test( chosen ), failure );
				} );
	}

	private static Scenario pickerCancel(){
		return new Scenario( "picker-cancel", "back out of a picker", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.give( new ScrollOfUpgrade() );
				},
				d -> {
					d.use( ScrollOfUpgrade.class );
					d.require( d.env().mode() == EnvMode.INVENTORY, "the scroll offered no picker: " + d.env().mode() );
					d.cancel();
					d.expect( d.env().mode() == EnvMode.WORLD, "cancelling the picker left " + d.env().mode() );
					d.expect( Dungeon.hero.belongings.getItem( ScrollOfUpgrade.class ) != null, "cancelling the picker"
							+ " spent the scroll" );
				} );
	}

	@SafeVarargs
	private static Scenario brew( String id, Class< ? extends Item > product, Supplier< Item >... ingredients ){
		return new Scenario( id, "brew " + product.getSimpleName() + " at a pot", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.emptyPack().energy( 20 );
					for (Supplier< Item > ingredient : ingredients) kit.give( ingredient.get() );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					int crafted = Statistics.itemsCrafted;
					d.act( Action.ALCHEMY );
					d.require( d.env().mode() == EnvMode.ALCHEMY, "ALCHEMY entered " + d.env().mode() );
					int choice = -1;
					List< com.shatteredpixel.shatteredpixeldungeon.items.Recipe > recipes = d.env().mapper().alchemyChoices();
					for (int i = 0; i < recipes.size() && choice < 0; i++){
						if (yields( recipes.get( i ), product )) choice = i;
					}
					d.require( choice >= 0, "no offered recipe makes " + product.getSimpleName() + " from " + recipes );
					d.menu( choice );
					d.expect( Statistics.itemsCrafted == crafted + 1, "itemsCrafted moved by " + (Statistics.itemsCrafted - crafted) );
					d.expect( carries( product ), "no " + product.getSimpleName() + " in the pack after brewing" );
				} );
	}

	private static boolean yields( com.shatteredpixel.shatteredpixeldungeon.items.Recipe recipe, Class< ? extends Item > product ){
		for (java.util.ArrayList< Item > ingredients : com.shatteredpixel.shatteredpixeldungeon.superintelligence.env
				.AlchemyAction.ingredientSets( Dungeon.hero )){
			if (!recipe.testIngredients( new java.util.ArrayList<>( ingredients ))) continue;
			Item out = recipe.sampleOutput( new java.util.ArrayList<>( ingredients ));
			Class< ? > maker = recipe.getClass().getEnclosingClass();
			if (product.isInstance( out ) || (maker != null && product.isAssignableFrom( maker ))) return true;
		}
		return false;
	}

	private static boolean carries( Class< ? extends Item > product ){
		for (Item item : Dungeon.hero.belongings.backpack) if (product.isInstance( item )) return true;
		return false;
	}
}
