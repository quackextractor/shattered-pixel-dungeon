package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MagicalSleep;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Terror;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Gnoll;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Sheep;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.LeatherArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfLiquidFlame;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRage;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.Runestone;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfAggression;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfAugmentation;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfBlast;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfBlink;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfClairvoyance;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfDeepSleep;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfDetectMagic;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfEnchantment;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfFear;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfFlock;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfIntuition;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfShock;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Dagger;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Longsword;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.THROW;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.effect;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.held;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.rat;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.target;

final class StoneScenarios {

	private StoneScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( effect( "stone.Aggression", StoneOfAggression.class, THROW, StoneScenarios::bait, 6,
				"the aggression never turned the gnoll on the rat", () -> {
			Mob rat = rat();
			int hp = rat.HP;
			return () -> rat.buff( StoneOfAggression.Aggression.class ) != null && rat.HP < hp;
		}).then( d -> d.expect( d.until( () -> rat().buff( StoneOfAggression.Aggression.class ) == null, 25, () -> d.act( Action.WAIT )),
				"the aggression never wore off the rat" )).covers());
		all.add( effect( "stone.Blast", StoneOfBlast.class, THROW, ( world, kit ) -> target( world, 100 ), 0,
				"the blast never hurt the rat", StoneScenarios::ratHurt ).covers());
		all.add( effect( "stone.Blink", StoneOfBlink.class, THROW, held( 100 ), 0, "the blink did not land the hero beside the rat", () -> {
			Mob rat = rat();
			int start = Dungeon.hero.pos;
			return () -> Dungeon.hero.pos != start && Dungeon.level.adjacent( Dungeon.hero.pos, rat.pos );
		}).covers());
		all.add( effect( "stone.Clairvoyance", StoneOfClairvoyance.class, THROW, ( world, kit ) -> target( world, 100 ), 0,
				"the clairvoyance mapped nothing", () -> {
			int mapped = ScrollScenarios.mapped();
			return () -> ScrollScenarios.mapped() > mapped;
		}).covers());
		all.add( effect( "stone.DeepSleep", StoneOfDeepSleep.class, THROW, StoneScenarios::hunter, 0,
				"the deep sleep never put the hunting rat to sleep", () -> {
			Mob rat = rat();
			int paralysed = rat.paralysed;
			return () -> rat.buff( MagicalSleep.class ) != null && rat.state == rat.SLEEPING && rat.paralysed > paralysed;
		}).then( d -> {
			Mob rat = rat();
			int pos = rat.pos;
			for (int i = 0; i < 3; i++) d.act( Action.WAIT );
			d.expect( rat.pos == pos && rat.buff( MagicalSleep.class ) != null, "the sleeping rat moved or woke unprovoked" );
		}).covers( MagicalSleep.class ));
		all.add( effect( "stone.Fear", StoneOfFear.class, THROW, StoneScenarios::hunter, 4, "the fear never drove the rat away", () -> {
			Mob rat = rat();
			int distance = Dungeon.level.distance( rat.pos, Dungeon.hero.pos );
			return () -> rat.buff( Terror.class ) != null && Dungeon.level.distance( rat.pos, Dungeon.hero.pos ) > distance;
		}).covers());
		all.add( effect( "stone.Flock", StoneOfFlock.class, THROW, ( world, kit ) -> target( world, 100 ), 0, "the flock raised no sheep", () -> {
			int sheep = sheep();
			return () -> sheep() > sheep;
		}).then( d -> d.expect( d.until( () -> sheep() == 0, 12, () -> d.act( Action.WAIT )), "the sheep never vanished" ))
				.covers( Sheep.class ));
		all.add( effect( "stone.Shock", StoneOfShock.class, THROW, ( world, kit ) -> {
			kit.give( new WandOfMagicMissile() ).curCharges = 0;
			target( world, 100 );
		}, 0, "the shock found no one to charge the wand from", () -> {
			int charges = wand().curCharges;
			return () -> wand().curCharges >= charges + 2;
		}).covers());
		all.add( picked( "stone.Augmentation", StoneOfAugmentation.class, LeatherArmor::new, LeatherArmor.class, "WndAugment",
				"the augmentation left the armour unaugmented", () -> {
			Armor armor = Dungeon.hero.belongings.getItem( LeatherArmor.class );
			return () -> armor.augment != Armor.Augment.NONE;
		}));
		all.add( picked( "stone.DetectMagic", StoneOfDetectMagic.class, Longsword::new, Longsword.class, null,
				"the detection left the longsword's curse unknown", () -> {
			Item sword = Dungeon.hero.belongings.getItem( Longsword.class );
			return () -> sword.cursedKnown;
		}));
		all.add( picked( "stone.Enchantment", StoneOfEnchantment.class, Dagger::new, Dagger.class, null,
				"the enchantment left the dagger plain", () -> {
			Dagger dagger = Dungeon.hero.belongings.getItem( Dagger.class );
			return () -> dagger.enchantment != null;
		}));
		all.add( intuition() );
		all.add( CraftScenarios.brew( "stone.brew", Runestone.class, ScrollOfRage::new ).covers( Scroll.ScrollToStone.class ));
		return all;
	}

	private static Scenario picked( String id, Class< ? extends Item > stone, java.util.function.Supplier< Item > item, Class< ? extends Item > pick, String window,
			String failure, java.util.function.Supplier< BooleanSupplier > arm ){
		return effect( id, stone, ( d, type ) -> {
			d.use( type );
			d.pick( pick );
			if (window != null) answer( d, window, 0 );
		}, ( world, kit ) -> kit.giveUnknown( item.get() ), 0, failure, arm ).covers();
	}

	private static Scenario intuition(){
		return effect( "stone.Intuition", StoneOfIntuition.class, ( d, type ) -> {
			d.use( type );
			d.pick( PotionOfLiquidFlame.class );
			answer( d, "WndGuess", new ArrayList<>( Potion.getUnknown() ).indexOf( PotionOfLiquidFlame.class ));
			answer( d, "WndGuess", 0 );
		}, ( world, kit ) -> kit.giveUnknown( new PotionOfLiquidFlame() ), 0, "the right guess left the liquid flame unknown",
				() -> () -> !Potion.getUnknown().contains( PotionOfLiquidFlame.class )).covers();
	}

	private static void answer( Driver d, String window, int option ){
		d.require( d.env().mode() == EnvMode.MENU && WindowBridge.describe().startsWith( window + "(" ),
				"expected " + window + ", saw " + d.env().mode() + " " + WindowBridge.describe() );
		d.menu( option );
	}

	private static BooleanSupplier ratHurt(){
		Mob rat = rat();
		int hp = rat.HP;
		return () -> !rat.isAlive() || rat.HP < hp;
	}

	private static void hunter( World world, HeroKit kit ){
		world.hunting( new Rat(), world.cellAtRange( 3, true ));
	}

	private static void bait( World world, HeroKit kit ){
		held( 100 ).build( world, kit );
		int rat = rat().pos;
		for (int offset : PathFinder.NEIGHBOURS8){
			int cell = rat + offset;
			if (world.free( cell ) && world.level().distance( cell, world.hero() ) == 4){
				world.hunting( new Gnoll(), cell );
				return;
			}
		}
		throw new NoFixture( "no free cell behind the rat for the gnoll" );
	}

	private static int sheep(){
		int sheep = 0;
		for (Mob mob : Dungeon.level.mobs) if (mob instanceof Sheep) sheep++;
		return sheep;
	}

	private static WandOfMagicMissile wand(){
		return Dungeon.hero.belongings.getItem( WandOfMagicMissile.class );
	}
}
