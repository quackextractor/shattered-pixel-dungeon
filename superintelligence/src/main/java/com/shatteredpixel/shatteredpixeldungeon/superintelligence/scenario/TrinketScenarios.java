package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.ChaoticCenser;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.CrackedSpyglass;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.DimensionalSundial;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.ExoticCrystals;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.EyeOfNewt;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.FerretTuft;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.MimicTooth;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.MossyClump;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.ParchmentScrap;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.PetrifiedSeed;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.RatSkull;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.SaltCube;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.ShardOfOblivion;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.ThirteenLeafClover;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.TrapMechanism;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.Trinket;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.TrinketCatalyst;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.VialOfBlood;
import com.shatteredpixel.shatteredpixeldungeon.items.trinkets.WondrousResin;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

final class TrinketScenarios {

	private TrinketScenarios(){}

	private static final int LEVEL = 3;

	private static final int STEPS = 120;

	private static final int GAS_STEPS = 300;

	private static final int GAS_WAIT = 210;

	private static final int ENERGY = 40;

	private static final int FAR = 11;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( censer() );
		all.add( carried( CrackedSpyglass.class, 1, () -> CrackedSpyglass.extraLootChance()));
		all.add( carried( DimensionalSundial.class, -1, () -> DimensionalSundial.enemySpawnMultiplierDaytime()));
		all.add( carried( ExoticCrystals.class, 1, () -> ExoticCrystals.consumableExoticChance()));
		all.add( carried( EyeOfNewt.class, -1, () -> EyeOfNewt.visionRangeMultiplier()));
		all.add( carried( FerretTuft.class, 1, () -> FerretTuft.evasionMultiplier()));
		all.add( mimicTooth() );
		all.add( carried( MossyClump.class, 1, () -> MossyClump.overrideNormalLevelChance()));
		all.add( carried( ParchmentScrap.class, 1, () -> ParchmentScrap.enchantChanceMultiplier()));
		all.add( carried( PetrifiedSeed.class, 1, () -> PetrifiedSeed.grassLootMultiplier()));
		all.add( carried( RatSkull.class, 1, () -> RatSkull.exoticChanceMultiplier(), 5.0 ));
		all.add( carried( SaltCube.class, -1, () -> SaltCube.hungerGainMultiplier()));
		all.add( shardOfOblivion() );
		all.add( carried( ThirteenLeafClover.class, 1, () -> ThirteenLeafClover.alterHeroDamageChance()));
		all.add( carried( TrapMechanism.class, 1, () -> TrapMechanism.overrideNormalLevelChance()));
		all.add( carried( VialOfBlood.class, 1, () -> VialOfBlood.totalHealMultiplier()));
		all.add( carried( WondrousResin.class, 1, () -> WondrousResin.positiveCurseEffectChance()));
		all.add( catalyst() );
		all.add( upgrade() );
		return all;
	}

	private static Scenario carried( Class< ? extends Trinket > type, int sign, DoubleSupplier getter ){
		return carried( type, sign, getter, Double.NaN, ( world, kit ) -> {}, d -> {});
	}

	private static Scenario carried( Class< ? extends Trinket > type, int sign, DoubleSupplier getter, double expectedAfter ){
		return carried( type, sign, getter, expectedAfter, ( world, kit ) -> {}, d -> {});
	}

	private static Scenario carried( Class< ? extends Trinket > type, int sign, DoubleSupplier getter,
			Scenario.Setup fixture, Scenario.Script after ){
		return carried( type, sign, getter, Double.NaN, fixture, after );
	}

	private static Scenario carried( Class< ? extends Trinket > type, int sign, DoubleSupplier getter, double expectedAfter,
			Scenario.Setup fixture, Scenario.Script after ){
		String name = type.getSimpleName();
		Scenario s = new Scenario( "trinket." + name, "pick up a +" + LEVEL + " " + name + " from the floor", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Trinket trinket = Reflection.newInstance( type );
					trinket.upgrade( LEVEL );
					world.drop( trinket, world.adjacentFreeStraight());
					fixture.build( world, kit );
				},
				d -> {
					d.fixture( Dungeon.hero.belongings.getItem( type ) == null, "the hero already carried a " + name );
					double before = getter.getAsDouble();
					int cell = heapOf( type );
					d.fixture( cell >= 0, "no " + name + " heap beside the hero" );
					d.move( cell );
					if (Dungeon.hero.pos != cell) d.move( cell );
					d.require( Dungeon.hero.belongings.getItem( type ) != null, "the hero did not pick up the " + name
							+ " at " + cell + " from " + Dungeon.hero.pos );
					double got = getter.getAsDouble();
					if (Double.isNaN( expectedAfter )){
						d.expect( sign * (got - before) > 0, "the carried +" + LEVEL + " " + name
								+ " left its getter at " + got + " from " + before );
					} else {
						d.expect( got == expectedAfter, "the carried +" + LEVEL + " " + name
								+ " left its getter at " + got + " from " + before + ", expected " + expectedAfter );
					}
					after.run( d );
				} ).covers( type );
		s.maxSteps = STEPS;
		return s;
	}

	private static int heapOf( Class< ? extends Item > type ){
		for (int offset : PathFinder.NEIGHBOURS8){
			Heap heap = Dungeon.level.heaps.get( Dungeon.hero.pos + offset );
			if (heap == null) continue;
			for (Item item : heap.items) if (type.isInstance( item )) return heap.pos;
		}
		return -1;
	}

	private static Scenario censer(){
		Scenario s = carried( ChaoticCenser.class, 1, () -> ChaoticCenser.averageTurnsUntilGas(), TrinketScenarios::watched,
				TrinketScenarios::censerGas );
		s.maxSteps = GAS_STEPS;
		return s;
	}

	private static void watched( World world, HeroKit kit ){
		Rat rat = world.mob( new Rat(), farCell());
		rat.HP = rat.HT = 1000;
		Buff.affect( rat, Paralysis.class, 5000f );
		if (TargetHealthIndicator.instance == null) new TargetHealthIndicator();
		TargetHealthIndicator.instance.target( rat );
	}

	private static int farCell(){
		int hero = Dungeon.hero.pos;
		boolean[] open = new boolean[ Dungeon.level.length() ];
		for (int i = 0; i < open.length; i++) open[ i ] = Dungeon.level.passable[ i ] || Dungeon.level.avoid[ i ];
		PathFinder.buildDistanceMap( hero, open );
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (PathFinder.distance[ cell ] < FAR || PathFinder.distance[ cell ] == Integer.MAX_VALUE) continue;
			if (freeCell( cell )) return cell;
		}
		throw new NoFixture( "no free cell " + FAR + " steps from the hero" );
	}

	private static void censerGas( Driver d ){
		Rat rat = d.world().find( Rat.class );
		try {
			boolean seen = d.until( () -> Dungeon.level.heroFOV[ rat.pos ], 40, () -> d.walkTo( rat.pos, 1 ));
			d.require( seen, "the hero never came within sight of the rat at " + rat.pos + " from " + Dungeon.hero.pos );
			d.expect( d.until( () -> Dungeon.hero.buff( ChaoticCenser.CenserGasTracker.class ) != null, 10,
					() -> d.act( Action.WAIT )), "the carried censer armed no gas tracker" );
			java.util.Map< Class< ? extends Blob >, Integer > beforeGas = new java.util.LinkedHashMap<>();
			for (java.util.Map.Entry< Class< ? extends Blob >, Blob > entry : Dungeon.level.blobs.entrySet()){
				beforeGas.put( entry.getKey(), entry.getValue().volume );
			}
			boolean appeared = d.until( () -> gasAppeared( beforeGas ), GAS_WAIT, () -> d.act( Action.WAIT ));
			d.expect( appeared, "the censer spewed no gas in " + GAS_WAIT + " turns of watching the rat at " + rat.pos );
		} finally {
			TargetHealthIndicator.forgetInstance();
		}
	}

	private static boolean stealthyBeforePickup;

	private static Scenario mimicTooth(){
		return carried( MimicTooth.class, 1, () -> MimicTooth.mimicChanceMultiplier(), TrinketScenarios::stealthyMimic,
				TrinketScenarios::revealed );
	}

	private static void stealthyMimic( World world, HeroKit kit ){
		MimicTooth tooth = new MimicTooth();
		kit.give( tooth );
		Mimic mimic = Mimic.spawnAt( world.adjacentFreeStraight(), new Sword());
		if (!mimic.stealthy()) throw new NoFixture( "the mimic was not stealthy while the tooth was carried" );
		tooth.detach( kit.hero().belongings.backpack );
		stealthyBeforePickup = MimicTooth.stealthyMimics();
		world.mob( mimic, mimic.pos );
	}

	private static void revealed( Driver d ){
		Mimic mimic = d.world().find( Mimic.class );
		d.require( mimic != null, "the stealthy mimic was not on the level" );
		d.require( mimic.stealthy(), "the mimic was not stealthy while the tooth was carried" );
		d.require( mimic.alignment == Char.Alignment.NEUTRAL, "the mimic was already revealed" );
		d.require( MimicTooth.stealthyMimics() != stealthyBeforePickup,
				"the carried tooth did not change mimic stealthiness" );
		d.expect( d.until( () -> mimic.alignment == Char.Alignment.ENEMY, 6, () -> d.move( mimic.pos )),
				"six steps onto the stealthy mimic left it unrevealed" );
	}

	private static Scenario shardOfOblivion(){
		return carried( ShardOfOblivion.class, 1, () -> ShardOfOblivion.lootChanceMultiplier(), TrinketScenarios::unknownGear, d -> {});
	}

	private static void unknownGear( World world, HeroKit kit ){
		Item weapon = kit.hero().belongings.weapon;
		if (weapon == null) throw new NoFixture( "the hero wore no weapon to leave unidentified" );
		weapon.levelKnown = false;
	}

	private static Scenario catalyst(){
		return new Scenario( "trinket.catalyst", "brew a trinket catalyst and answer its window", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().emptyPack().energy( ENERGY );
					kit.give( new TrinketCatalyst());
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					int crafted = Statistics.itemsCrafted;
					d.brewRecipe( TrinketCatalyst.Recipe.class );
					d.require( d.env().mode() == EnvMode.MENU, "the catalyst asked no window: " + d.env().mode() );
					d.menu( 0 );
					d.require( d.env().mode() == EnvMode.MENU, "the offered trinket asked no confirmation: " + d.env().mode() );
					d.menu( 0 );
					d.require( firstTrinket() != null, "the answered catalyst left no trinket in the pack" );
					d.expect( Statistics.itemsCrafted == crafted + 1, "the catalyst moved itemsCrafted by "
							+ (Statistics.itemsCrafted - crafted) );
				} ).covers( TrinketCatalyst.class, TrinketCatalyst.Recipe.class );
	}

	private static Scenario upgrade(){
		return new Scenario( "trinket.upgrade", "brew a trinket upgrade and carry the raised trinket", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().emptyPack().energy( ENERGY );
					kit.give( new RatSkull());
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					int levelBefore = Dungeon.hero.belongings.getItem( RatSkull.class ).buffedLvl();
					int craftedBefore = Statistics.itemsCrafted;
					d.brewRecipe( Trinket.UpgradeTrinket.class );
					RatSkull raised = Dungeon.hero.belongings.getItem( RatSkull.class );
					d.require( raised != null, "the upgrade lost the trinket from the pack" );
					d.expect( raised.buffedLvl() == levelBefore + 1, "the upgrade left the trinket at +" + raised.buffedLvl()
							+ " from +" + levelBefore );
					d.expect( Statistics.itemsCrafted == craftedBefore + 1, "the upgrade moved itemsCrafted by "
							+ (Statistics.itemsCrafted - craftedBefore) );
				} ).covers( Trinket.UpgradeTrinket.class );
	}

	private static Trinket firstTrinket(){
		for (Item item : Dungeon.hero.belongings.backpack) if (item instanceof Trinket) return (Trinket) item;
		return null;
	}

	private static boolean freeCell( int cell ){
		com.shatteredpixel.shatteredpixeldungeon.levels.Level level = Dungeon.level;
		return cell >= 0 && cell < level.length() && level.insideMap( cell ) && level.passable[ cell ]
				&& !level.solid[ cell ] && !level.pit[ cell ]
				&& com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar( cell ) == null
				&& level.heaps.get( cell ) == null && level.traps.get( cell ) == null
				&& level.plants.get( cell ) == null && level.getTransition( cell ) == null;
	}

	private static boolean gasAppeared( java.util.Map< Class< ? extends Blob >, Integer > before ){
		for (java.util.Map.Entry< Class< ? extends Blob >, Blob > entry : Dungeon.level.blobs.entrySet()){
			int previous = before.getOrDefault( entry.getKey(), 0 );
			if (entry.getValue().volume > previous && entry.getValue().volume > 0) return true;
		}
		return false;
	}
}
