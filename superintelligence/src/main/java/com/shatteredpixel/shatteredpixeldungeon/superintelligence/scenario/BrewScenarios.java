package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blizzard;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Electricity;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Inferno;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ArcaneArmor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Chill;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FireImbue;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Frost;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FrostImbue;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Ooze;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Regeneration;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ToxicImbue;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DM100;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Honeypot;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Recipe;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfFrost;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfLevitation;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfLiquidFlame;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfParalyticGas;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.brews.AquaBrew;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.brews.BlizzardBrew;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.brews.CausticBrew;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.brews.InfernalBrew;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.brews.ShockingBrew;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.brews.UnstableBrew;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.elixirs.ElixirOfAquaticRejuvenation;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.elixirs.ElixirOfArcaneArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.elixirs.ElixirOfDragonsBlood;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.elixirs.ElixirOfFeatherFall;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.elixirs.ElixirOfHoneyedHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.elixirs.ElixirOfIcyTouch;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.elixirs.ElixirOfMight;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.elixirs.ElixirOfToxicEssence;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfCorrosiveGas;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfDragonsBreath;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfEarthenArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfSnapFreeze;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfStormClouds;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.GooBlob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.THROW;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.USE;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.effect;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.gasTarget;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.held;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.rat;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.volume;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.wearsOff;

final class BrewScenarios {

	private BrewScenarios(){}

	private static final int STRIKES = 8;

	private static final int BOLT = 10;

	private static final int ARCANE_INTERVAL = 80;

	private static final float ARCANE_MARGIN = 1.5f;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( brewed( effect( "brew.AquaBrew", AquaBrew.class, THROW, held( 100 ), 1, "the aqua brew spread no water or pushed no rat", () -> {
			Mob rat = rat();
			int water = water();
			int pos = rat.pos;
			return () -> water() > water && rat.pos != pos;
		}).covers(), AquaBrew.Recipe.class, 8, PotionOfStormClouds::new ));
		all.add( brewed( effect( "brew.BlizzardBrew", BlizzardBrew.class, THROW, gasTarget(), 2, "the blizzard never chilled the rat", () -> {
			Mob rat = rat();
			return () -> volume( Blizzard.class ) > 0 && (rat.buff( Chill.class ) != null || rat.buff( Frost.class ) != null);
		}).covers( Blizzard.class ), BlizzardBrew.Recipe.class, 1, PotionOfFrost::new ));
		all.add( brewed( effect( "brew.CausticBrew", CausticBrew.class, THROW, held( 100 ), 3, "the caustic brew never oozed the rat", () -> {
			Mob rat = rat();
			int hp = rat.HP;
			return () -> rat.buff( Ooze.class ) != null && rat.HP < hp;
		}).covers(), CausticBrew.Recipe.class, 1, PotionOfToxicGas::new, GooBlob::new ));
		all.add( brewed( effect( "brew.InfernalBrew", InfernalBrew.class, THROW, gasTarget(), 3, "the inferno never burned the rat", () -> {
			Mob rat = rat();
			int hp = rat.HP;
			return () -> volume( Inferno.class ) > 0 && rat.HP < hp;
		}).covers( Inferno.class ), InfernalBrew.Recipe.class, 1, PotionOfLiquidFlame::new ));
		all.add( brewed( effect( "brew.ShockingBrew", ShockingBrew.class, THROW, gasTarget(), 3, "the electricity never shocked the rat", () -> {
			Mob rat = rat();
			int hp = rat.HP;
			return () -> volume( Electricity.class ) > 0 && rat.HP < hp;
		}).covers(), ShockingBrew.Recipe.class, 1, PotionOfParalyticGas::new ));
		all.add( brewed( effect( "brew.UnstableBrew", UnstableBrew.class, USE, ( world, kit ) -> kit.health( 50, 100 ), 1,
				"the unstable brew gave no potion effect", () -> {
			Hero hero = Dungeon.hero;
			Set< Class< ? > > had = positives();
			int lvl = hero.lvl;
			int exp = hero.exp;
			return () -> !had.containsAll( positives() ) || hero.lvl > lvl || hero.exp > exp;
		}).covers(), UnstableBrew.Recipe.class, 1, PotionOfHealing::new, Sungrass.Seed::new ));
		all.add( brewed( effect( "elixir.ElixirOfAquaticRejuvenation", ElixirOfAquaticRejuvenation.class, USE, ( world, kit ) -> {
			kit.health( 50, 100 );
			kit.hero().buff( Regeneration.class ).detach();
			world.terrain( world.hero(), Terrain.WATER );
		}, 5, "the aquatic rejuvenation healed nothing in water with regeneration off", () -> {
			int hp = Dungeon.hero.HP;
			return () -> Dungeon.hero.buff( ElixirOfAquaticRejuvenation.AquaHealing.class ) != null && Dungeon.hero.HP > hp;
		}).covers(), ElixirOfAquaticRejuvenation.Recipe.class, 1, PotionOfHealing::new, GooBlob::new ));
		all.add( brewed( arcaneArmor(), ElixirOfArcaneArmor.Recipe.class, 1, PotionOfEarthenArmor::new, GooBlob::new ));
		all.add( brewed( effect( "elixir.ElixirOfDragonsBlood", ElixirOfDragonsBlood.class, USE, BrewScenarios::struck, 0,
				"the dragon's blood gave no fire imbue", () -> () -> Dungeon.hero.buff( FireImbue.class ) != null )
				.then( strikes( Burning.class )).covers( FireImbue.class ), ElixirOfDragonsBlood.Recipe.class, 1, PotionOfDragonsBreath::new ));
		all.add( brewed( effect( "elixir.ElixirOfFeatherFall", ElixirOfFeatherFall.class, USE,
				( world, kit ) -> world.terrain( world.adjacentFreeStraight(), Terrain.CHASM ), 0, "the feather fall gave no feather buff",
				() -> () -> Dungeon.hero.buff( ElixirOfFeatherFall.FeatherBuff.class ) != null ).then( BrewScenarios::fall ).covers(),
				ElixirOfFeatherFall.Recipe.class, 1, PotionOfLevitation::new ));
		all.add( brewed( effect( "elixir.ElixirOfHoneyedHealing", ElixirOfHoneyedHealing.class, USE, ( world, kit ) -> {
			kit.health( 50, 100 );
			kit.hero().buff( Hunger.class ).affectHunger( -(Hunger.HUNGRY + 10) );
		}, 3, "the honeyed healing healed no HP or fed no hunger", () -> {
			int hp = Dungeon.hero.HP;
			Hunger hunger = Dungeon.hero.buff( Hunger.class );
			int fed = hunger.hunger();
			return () -> Dungeon.hero.HP > hp && hunger.hunger() < fed;
		}).covers(), ElixirOfHoneyedHealing.Recipe.class, 1, PotionOfHealing::new, Honeypot.ShatteredPot::new ));
		all.add( brewed( effect( "elixir.ElixirOfIcyTouch", ElixirOfIcyTouch.class, USE, BrewScenarios::struck, 0,
				"the icy touch gave no frost imbue", () -> () -> Dungeon.hero.buff( FrostImbue.class ) != null ).then( d -> {
			strikes( Chill.class ).run( d );
			wearsOff( d, FrostImbue.class, 60 );
		}).covers( FrostImbue.class ), ElixirOfIcyTouch.Recipe.class, 1, PotionOfSnapFreeze::new ));
		all.add( brewed( effect( "elixir.ElixirOfMight", ElixirOfMight.class, USE, ( world, kit ) -> kit.health( 20, 20 ), 0,
				"the might added no strength point and no max HP", () -> {
			Hero hero = Dungeon.hero;
			int str = hero.STR;
			int ht = hero.HT;
			return () -> hero.STR == str + 1 && hero.HT > ht && hero.buff( ElixirOfMight.HTBoost.class ) != null;
		}).covers(), ElixirOfMight.Recipe.class, 1, PotionOfStrength::new ));
		all.add( brewed( effect( "elixir.ElixirOfToxicEssence", ElixirOfToxicEssence.class, USE, BrewScenarios::struck, 4,
				"the toxic essence released no gas that hurt the rat and spared the hero", () -> {
			Mob rat = rat();
			int hp = rat.HP;
			int hero = Dungeon.hero.HP;
			return () -> volume( ToxicGas.class ) > 0 && rat.HP < hp && Dungeon.hero.HP >= hero;
		}).covers( ToxicImbue.class ), ElixirOfToxicEssence.Recipe.class, 1, PotionOfCorrosiveGas::new ));
		return all;
	}

	@SafeVarargs
	private static Scenario brewed( Scenario use, Class< ? extends Recipe > recipe, int made, Supplier< Item >... ingredients ){
		Class< ? extends Item > product = recipe.getEnclosingClass().asSubclass( Item.class );
		Scenario s = new Scenario( use.id, "brew then use " + product.getSimpleName(), use.hero,
				( world, kit ) -> {
					use.setup.build( world, kit );
					FoodScenarios.pot( ingredients ).build( world, kit );
				},
				d -> {
					FoodScenarios.brewed( d, recipe, product, made );
					use.script.run( d );
				} ).covers( recipe );
		s.covers.addAll( use.covers );
		s.seeds = use.seeds;
		s.depth = use.depth;
		s.dirtyEnd = use.dirtyEnd;
		s.judgeReach = use.judgeReach;
		s.maxSteps = use.maxSteps;
		return s;
	}

	private static Scenario arcaneArmor(){
		float[] bare = new float[ 1 ];
		return effect( "elixir.ElixirOfArcaneArmor", ElixirOfArcaneArmor.class, USE, ( world, kit ) -> {}, 0,
				"the arcane armour gave no arcane armour", () -> {
			bare[ 0 ] = boltTaken();
			return () -> Dungeon.hero.buff( ArcaneArmor.class ) != null;
		}).then( d -> {
			float armoured = boltTaken();
			d.expect( armoured < bare[ 0 ] - ARCANE_MARGIN, "a " + BOLT + "-damage lightning bolt took " + armoured
					+ " HP on average under the hero's arcane armour against " + bare[ 0 ] + " before the drink" );
		}).covers( ArcaneArmor.class );
	}

	private static float boltTaken(){
		ArcaneArmor worn = Dungeon.hero.buff( ArcaneArmor.class );
		return PotionScenarios.mean( () -> {
			SpellProbes.Dummy probe = new SpellProbes.Dummy( Char.Alignment.ALLY, Dungeon.hero.pos );
			if (worn != null) Buff.affect( probe, ArcaneArmor.class ).set( worn.level(), ARCANE_INTERVAL );
			probe.damage( BOLT, new DM100.LightningBolt() );
			return probe.taken();
		});
	}

	private static void fall( Driver d ){
		int hp = Dungeon.hero.HP;
		d.move( d.world().beside( Terrain.CHASM ));
		d.require( d.env().mode() == EnvMode.MENU, "stepping at the chasm asked nothing: " + d.env().mode() );
		d.menu( 0 );
		d.act( Action.WAIT );
		Hero hero = Dungeon.hero;
		d.expect( Dungeon.depth == 2 && hero.HP >= hp && hero.buff( Cripple.class ) == null && hero.buff( Bleeding.class ) == null,
				"the feather fall landed on depth " + Dungeon.depth + " with HP " + hero.HP + " from " + hp );
		wearsOff( d, ElixirOfFeatherFall.FeatherBuff.class, 60 );
	}

	private static void struck( World world, HeroKit kit ){
		int cell = world.adjacentFree();
		world.terrain( cell, Terrain.EMPTY );
		Rat rat = world.mob( new Rat(), cell );
		rat.HP = rat.HT = 100;
		Buff.affect( rat, Paralysis.class, 100f );
	}

	private static Scenario.Script strikes( Class< ? extends Buff > mark ){
		return d -> {
			Mob rat = rat();
			int hp = rat.HP;
			d.require( rat.buff( mark ) == null, "the rat had " + mark.getSimpleName() + " before the first strike" );
			d.expect( d.until( () -> rat.buff( mark ) != null, STRIKES, () -> d.move( rat.pos )),
					STRIKES + " strikes took the rat from " + hp + " to " + rat.HP + " HP without " + mark.getSimpleName() );
		};
	}

	private static int water(){
		int cells = 0;
		for (int terrain : Dungeon.level.map) if (terrain == Terrain.WATER) cells++;
		return cells;
	}

	private static Set< Class< ? > > positives(){
		Set< Class< ? > > out = new HashSet<>();
		for (Buff buff : Dungeon.hero.buffs()) if (buff.type == Buff.buffType.POSITIVE) out.add( buff.getClass() );
		return out;
	}
}
