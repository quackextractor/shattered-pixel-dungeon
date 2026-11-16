package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.CorrosiveGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.SmokeScreen;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.StormCloud;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barkskin;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barrier;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.BlobImmunity;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Corrosion;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Frost;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FrostImbue;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MagicalSight;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Roots;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Stamina;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Weakness;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Elemental;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfFrost;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfParalyticGas;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfPurity;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfCleansing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfCorrosiveGas;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfDivineInspiration;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfDragonsBreath;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfEarthenArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfMagicalSight;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfMastery;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfShielding;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfShroudingFog;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfSnapFreeze;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfStamina;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfStormClouds;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Greatsword;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.Random;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

final class PotionScenarios {

	private PotionScenarios(){}

	private static final long DR_SAMPLE_SEED = 7L;

	private static final int DR_SAMPLES = 400;

	interface Trigger {
		void fire( Driver d, Class< ? extends Item > type );
	}

	static final Trigger USE = Driver::use;

	static final Trigger THROW = ( d, type ) -> {
		d.throwItem( type );
		d.aimNearest();
	};

	static final Trigger AIM = ( d, type ) -> {
		d.use( type );
		d.aimNearest();
	};

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( effect( "potion.Frost", PotionOfFrost.class, THROW, ( world, kit ) -> {
			frostTarget( world, kit );
			Buff.affect( rat(), Roots.class, 100f );
		}, 10, "the frost potion never chilled the rat into a freeze",
				() -> {
			Mob rat = rat();
			return () -> frozen( rat );
		}).covers());
		all.add( purity().covers( BlobImmunity.class ));
		all.add( effect( "potion.Cleansing", PotionOfCleansing.class, USE, ( world, kit ) -> {
			kit.buff( Poison.class ).set( 50 );
			kit.buff( Weakness.class, 50f );
			kit.buff( Cripple.class, 50f );
		}, 0, "the cleansing left a negative buff on the hero", () -> () -> !negative() ).covers());
		all.add( effect( "potion.CorrosiveGas", PotionOfCorrosiveGas.class, THROW, gasTarget(), 3, "the corrosive gas never corroded the rat", () -> {
			Mob rat = rat();
			int hp = rat.HP;
			return () -> volume( CorrosiveGas.class ) > 0 && rat.buff( Corrosion.class ) != null && rat.HP < hp;
		}).covers());
		all.add( effect( "potion.ParalyticGas", PotionOfParalyticGas.class, THROW, ( world, kit ) -> {
			kit.buff( BlobImmunity.class, 100f );
			target( world, 100 );
		}, 3, "the paralytic gas never paralysed the rat", () -> {
			Mob rat = rat();
			return () -> rat.buff( Paralysis.class ) != null && rat.paralysed > 0;
		}).covers());
		all.add( effect( "potion.DivineInspiration", PotionOfDivineInspiration.class, ( d, type ) -> {
			d.use( type );
			d.require( d.env().mode() == EnvMode.MENU, "the potion asked for no tier: " + d.env().mode() );
			d.menu( 0 );
		}, ( world, kit ) -> {}, 0, "the potion added no tier-1 talent point", () -> {
			int points = Dungeon.hero.talentPointsAvailable( 1 );
			return () -> Dungeon.hero.talentPointsAvailable( 1 ) > points;
		}).covers());
		all.add( effect( "potion.DragonsBreath", PotionOfDragonsBreath.class, AIM, held( 100 ), 3, "the dragon's breath never set the rat burning", () -> {
			Mob rat = rat();
			int hp = rat.HP;
			return () -> rat.buff( Burning.class ) != null && rat.HP < hp;
		}).covers());
		all.add( earthenArmor() );
		all.add( effect( "potion.MagicalSight", PotionOfMagicalSight.class, USE, ( world, kit ) -> {}, 0, "the magical sight widened no view", () -> {
			int seen = seen();
			return () -> Dungeon.hero.buff( MagicalSight.class ) != null && seen() > seen;
		}).then( d -> wearsOff( d, MagicalSight.class, 60 )).covers( MagicalSight.class ));
		all.add( effect( "potion.Mastery", PotionOfMastery.class, ( d, type ) -> {
			d.use( type );
			d.pick( Greatsword.class );
		}, ( world, kit ) -> kit.give( new Greatsword() ), 0, "the mastery left the greatsword's strength requirement", () -> {
			Greatsword sword = Dungeon.hero.belongings.getItem( Greatsword.class );
			int req = sword.STRReq();
			return () -> sword.STRReq() < req;
		}).covers());
		all.add( effect( "potion.Shielding", PotionOfShielding.class, USE, ( world, kit ) -> {}, 0, "the potion raised no barrier", () -> {
			int shield = Dungeon.hero.shielding();
			return () -> Dungeon.hero.buff( Barrier.class ) != null && Dungeon.hero.shielding() > shield;
		}).then( d -> {
			int shield = Dungeon.hero.shielding();
			d.expect( d.until( () -> Dungeon.hero.shielding() < shield, 5, () -> d.act( Action.WAIT )), "the barrier never wore down from " + shield );
		}).covers( Barrier.class ));
		all.add( effect( "potion.ShroudingFog", PotionOfShroudingFog.class, THROW, PotionScenarios::watcher, 2, "the fog never hid the hero from the rat", () -> {
			Mob rat = rat();
			int hero = Dungeon.hero.pos;
			return () -> volume( SmokeScreen.class ) > 0 && !rat.fieldOfView[ hero ];
		}).before( d -> {
			d.act( Action.WAIT );
			d.require( rat().fieldOfView != null && rat().fieldOfView[ Dungeon.hero.pos ], "the rat did not see the hero before the fog" );
		}).covers( SmokeScreen.class ));
		all.add( effect( "potion.SnapFreeze", PotionOfSnapFreeze.class, THROW, PotionScenarios::frostTarget, 1, "the snap freeze neither froze nor rooted the rat", () -> {
			Mob rat = rat();
			return () -> frozen( rat ) && rat.buff( Roots.class ) != null;
		}).covers());
		all.add( stamina().covers( Stamina.class ));
		all.add( effect( "potion.StormClouds", PotionOfStormClouds.class, THROW, ( world, kit ) -> {
			Mob fiery = world.mob( new Elemental.FireElemental(), world.cellAtRange( 3, true ));
			Buff.affect( fiery, Paralysis.class, 100f );
		}, 3, "the storm cloud never hurt the fire elemental", () -> {
			Mob fiery = new World().find( Elemental.FireElemental.class );
			int hp = fiery.HP;
			return () -> volume( StormCloud.class ) > 0 && fiery.HP < hp;
		}).covers( StormCloud.class ));
		return all;
	}

	static final class Effect {
		private final String id;
		private final Class< ? extends Item > type;
		private final Trigger trigger;
		private final Scenario.Setup arena;
		private final int waits;
		private final String failure;
		private final Supplier< BooleanSupplier > arm;
		private Scenario.Script before = d -> {};
		private Scenario.Script after = d -> {};

		private Effect( String id, Class< ? extends Item > type, Trigger trigger, Scenario.Setup arena, int waits, String failure,
				Supplier< BooleanSupplier > arm ){
			this.id = id;
			this.type = type;
			this.trigger = trigger;
			this.arena = arena;
			this.waits = waits;
			this.failure = failure;
			this.arm = arm;
		}

		Effect before( Scenario.Script script ){
			before = script;
			return this;
		}

		Effect then( Scenario.Script script ){
			after = script;
			return this;
		}

		Scenario covers( Class< ? >... extra ){
			return new Scenario( id, "use " + type.getSimpleName(), HeroClass.WARRIOR,
					( world, kit ) -> {
						world.clearMobs();
						kit.sturdy();
						kit.give( Reflection.newInstance( type ));
						arena.build( world, kit );
					},
					d -> {
						before.run( d );
						BooleanSupplier holds = arm.get();
						d.require( !holds.getAsBoolean(), "the effect held before the " + type.getSimpleName() + " was used" );
						trigger.fire( d, type );
						d.expect( d.until( holds, waits, () -> d.act( Action.WAIT )), failure );
						after.run( d );
					} ).covers( type ).covers( extra );
		}
	}

	static Effect effect( String id, Class< ? extends Item > type, Trigger trigger, Scenario.Setup arena, int waits, String failure,
			Supplier< BooleanSupplier > arm ){
		return new Effect( id, type, trigger, arena, waits, failure, arm );
	}

	private static Scenario purity(){
		return new Scenario( "potion.Purity", "drink purity in toxic gas, then throw one at the gas", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new PotionOfPurity().quantity( 2 ));
					world.blob( ToxicGas.class, world.hero(), 2000 );
				},
				d -> {
					int hp = Dungeon.hero.HP;
					d.act( Action.WAIT );
					d.require( Dungeon.hero.HP < hp && Dungeon.hero.buff( BlobImmunity.class ) == null, "the gas did not hurt the hero before the potion" );
					d.use( PotionOfPurity.class );
					d.require( Dungeon.hero.buff( BlobImmunity.class ) != null, "the purity gave the hero no blob immunity" );
					int immune = Dungeon.hero.HP;
					for (int i = 0; i < 3; i++) d.act( Action.WAIT );
					d.expect( Dungeon.hero.HP >= immune && gasAt( Dungeon.hero.pos ) > 0, "the hero lost HP from " + immune + " to "
							+ Dungeon.hero.HP + " in the gas under blob immunity" );
					int cell = d.world().adjacentFreeStraight();
					d.require( gasAt( cell ) > 0 && gasAt( Dungeon.hero.pos ) > 0, "no gas left around the hero to clear" );
					int total = volume( ToxicGas.class );
					d.throwItem( PotionOfPurity.class );
					d.step( Action.USE, d.direction( cell ));
					d.expect( gasAt( cell ) == 0 && gasAt( Dungeon.hero.pos ) == 0 && volume( ToxicGas.class ) < total,
							"the thrown purity left gas beside the hero" );
					wearsOff( d, BlobImmunity.class, 25 );
				} ).covers( PotionOfPurity.class );
	}

	private static Scenario earthenArmor(){
		Scenario s = effect( "potion.EarthenArmor", PotionOfEarthenArmor.class, USE, ( world, kit ) -> {}, 0,
				"the earthen armour raised the hero's mean damage reduction by less than 0.5", () -> {
			float dr = meanDr();
			return () -> Barkskin.currentLevel( Dungeon.hero ) > 0 && meanDr() > dr + 0.5f;
		}).then( d -> wearsOff( d, Barkskin.class, 120 )).covers( Barkskin.class );
		s.maxSteps = 140;
		return s;
	}

	static float meanDr(){
		return mean( Dungeon.hero::drRoll );
	}

	static float mean( IntSupplier roll ){
		Random.pushGenerator( DR_SAMPLE_SEED );
		try {
			int total = 0;
			for (int i = 0; i < DR_SAMPLES; i++) total += roll.getAsInt();
			return total / (float) DR_SAMPLES;
		} finally {
			Random.popGenerator();
		}
	}

	private static Scenario stamina(){
		Scenario s = new Scenario( "potion.Stamina", "drink stamina and walk faster until it wears off", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new PotionOfStamina() );
				},
				d -> {
					int home = Dungeon.hero.pos;
					int away = d.world().adjacentFreeStraight();
					float base = cost( () -> d.move( away ));
					d.use( PotionOfStamina.class );
					d.require( Dungeon.hero.buff( Stamina.class ) != null, "the potion gave the hero no stamina" );
					float fast = cost( () -> d.move( home ));
					d.expect( fast < base - 0.1f, "a step under stamina cost " + fast + " turns against " + base + " before it" );
					wearsOff( d, Stamina.class, 200 );
				} ).covers( PotionOfStamina.class );
		s.maxSteps = 220;
		return s;
	}

	static void wearsOff( Driver d, Class< ? extends Buff > type, int max ){
		int home = Dungeon.hero.pos;
		int away = d.world().adjacentFreeStraight();
		d.expect( d.until( () -> Dungeon.hero.buff( type ) == null, max, () -> d.move( Dungeon.hero.pos == home ? away : home )),
				type.getSimpleName() + " never wore off in " + max + " steps" );
	}

	static float cost( Runnable action ){
		float start = Actor.now();
		action.run();
		return Actor.now() - start;
	}

	static Mob rat(){
		return new World().find( Rat.class );
	}

	static Rat target( World world, int hp ){
		Rat rat = world.mob( new Rat(), world.cellAtRange( 3, true ));
		rat.HP = rat.HT = hp;
		return rat;
	}

	static Scenario.Setup held( int hp ){
		return ( world, kit ) -> Buff.affect( target( world, hp ), Paralysis.class, 100f );
	}

	static Scenario.Setup gasTarget(){
		return ( world, kit ) -> {
			kit.buff( BlobImmunity.class, 100f );
			held( 100 ).build( world, kit );
		};
	}

	private static void frostTarget( World world, HeroKit kit ){
		kit.buff( FrostImbue.class, 100f );
		target( world, 100 );
	}

	static void watcher( World world, HeroKit kit ){
		Rat rat = target( world, 100 );
		rat.state = rat.PASSIVE;
	}

	static boolean frozen( Mob rat ){
		return rat.buff( Frost.class ) != null && rat.paralysed > 0;
	}

	static boolean negative(){
		for (Buff buff : Dungeon.hero.buffs()) if (buff.type == Buff.buffType.NEGATIVE) return true;
		return false;
	}

	private static int seen(){
		int cells = 0;
		for (boolean seen : Dungeon.level.heroFOV) if (seen) cells++;
		return cells;
	}

	static int volume( Class< ? extends Blob > type ){
		Blob blob = Dungeon.level.blobs.get( type );
		return blob == null ? 0 : blob.volume;
	}

	private static int gasAt( int cell ){
		Blob gas = Dungeon.level.blobs.get( ToxicGas.class );
		return gas == null ? 0 : gas.cur[ cell ];
	}
}
