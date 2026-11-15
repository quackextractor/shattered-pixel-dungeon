package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Daze;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.GreaterHaste;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.AssassinsBlade;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.BattleAxe;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Crossbow;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Cudgel;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Dagger;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Dirk;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Flail;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Gauntlet;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Glaive;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Gloves;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Greataxe;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Greatshield;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Greatsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.HandAxe;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Katana;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Longsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Mace;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Quarterstaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Rapier;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.RoundShield;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.RunicBlade;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sai;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Scimitar;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Shortsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sickle;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Spear;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WarHammer;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WarScythe;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Whip;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WornShortsword;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.Random;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class WeaponAbilityScenarios {

	private WeaponAbilityScenarios(){}

	private static final long HIT_SAMPLE_SEED = 7L;

	private static final int HIT_SAMPLES = 400;

	private static final int FAR_RAT_HP = 1000;

	private static final int SPINS = 3;

	private static final int RAT_MAX_DR = 1;

	private static final int DODGING_RAT_EVASION = 10000;

	private static final Scenario.Script AIM = Driver::aimNearest;

	private static final Scenario.Script INSTANT = d -> {};

	private static final Scenario.Script FREE_CELL = d -> {
		d.require( d.env().mode() == EnvMode.TARGETING, "the ability asked for no cell: " + d.env().mode() );
		d.step( Action.USE, d.direction( d.world().adjacentFree() ));
	};

	private static final Scenario.Script ATTACK = d -> d.move( rat().pos );

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( ability( Rapier.class, true, AIM, lunged(), "the lunge neither moved the hero nor hurt the rat" )
				.covers().covers( Coverage.heroClass( HeroClass.DUELIST )));
		all.add( ability( Katana.class, true, AIM, lunged(), "the lunge neither moved the hero nor hurt the rat" ).covers());
		all.add( mace() );
		for (Class< ? extends MeleeWeapon > type : List.of( BattleAxe.class, Cudgel.class, HandAxe.class, WarHammer.class )){
			all.add( ability( type, false, AIM, () -> {
				Mob rat = rat();
				BooleanSupplier hurt = hurt().get();
				return () -> hurt.getAsBoolean() && rat.buff( Daze.class ) != null;
			}, "the heavy blow left the rat unhurt or undazed" ).covers());
		}
		all.add( sword() );
		for (Class< ? extends MeleeWeapon > type : List.of( WornShortsword.class, Shortsword.class, Longsword.class, Greatsword.class,
				Sai.class, Gloves.class, Gauntlet.class, RunicBlade.class )){
			all.add( ability( type, false, AIM, hurt(), "the strike left the rat unhurt" ).covers());
		}
		all.add( ability( Whip.class, false, INSTANT, hurt(), "the lash left the rat unhurt" ).covers());
		all.add( ability( Greataxe.class, false, AIM, hurt(), "the greataxe below half health left the rat unhurt" )
				.setup( ( world, kit ) -> kit.health( 400, 1000 )).covers());
		for (Class< ? extends MeleeWeapon > type : List.of( Spear.class, Glaive.class )){
			all.add( ability( type, true, AIM, pushed(), "the spike did not hurt the rat and push it back" ).covers());
		}
		for (Class< ? extends MeleeWeapon > type : List.of( Sickle.class, WarScythe.class )){
			all.add( ability( type, false, AIM, () -> {
				Mob rat = rat();
				return () -> rat.buff( Bleeding.class ) != null;
			}, "the harvest left the rat without bleeding" ).covers());
		}
		for (Class< ? extends MeleeWeapon > type : List.of( Dagger.class, Dirk.class, AssassinsBlade.class )){
			all.add( ability( type, false, FREE_CELL, () -> {
				int pos = Dungeon.hero.pos;
				return () -> Dungeon.hero.pos != pos && Dungeon.level.adjacent( pos, Dungeon.hero.pos ) && Dungeon.hero.invisible > 0;
			}, "the sneak left the hero in place or visible" ).covers());
		}
		for (Class< ? extends MeleeWeapon > type : List.of( Greatshield.class, RoundShield.class )){
			all.add( ability( type, false, INSTANT, () -> () -> Dungeon.hero.defenseSkill( rat() ) >= Char.INFINITE_EVASION,
					"the guard left the hero's evasion finite" ).covers());
		}
		all.add( ability( Quarterstaff.class, false, INSTANT, () -> {
			int evasion = Dungeon.hero.defenseSkill( rat() );
			return () -> Dungeon.hero.defenseSkill( rat() ) > evasion;
		}, "the defensive stance did not raise the hero's evasion" ).covers());
		all.add( ability( Scimitar.class, false, INSTANT, () -> {
			float delay = Dungeon.hero.attackDelay();
			return () -> Dungeon.hero.attackDelay() < delay;
		}, "the sword dance did not quicken the hero's attacks" ).covers());
		all.add( ability( Crossbow.class, false, INSTANT, () -> {
			Mob rat = rat();
			int pos = rat.pos;
			return () -> rat.isAlive() && rat.pos != pos;
		}, "the charged melee shot did not knock the rat back" ).follow( d -> {
			d.expect( Dungeon.hero.buff( Crossbow.ChargedShot.class ) != null, "the crossbow charged no shot" );
			ATTACK.run( d );
		}).covers());
		all.add( flail() );
		return all;
	}

	private static Scenario flail(){
		return ability( Flail.class, false, INSTANT, () -> {
			Mob rat = rat();
			int hp = rat.HP;
			int floor = spunFloor();
			return () -> rat.isAlive() && hp - rat.HP >= floor;
		}, "the spun flail did not land its guaranteed boosted hit" ).setup( ( world, kit ) -> new World().find( Rat.class ).defenseSkill = DODGING_RAT_EVASION
		).before( d -> {
			Mob rat = rat();
			float rate = hitRate( rat );
			float reach = unspunReach( spunFloor() );
			d.require( rate < 0.05f, "an unspun flail hit the evasive rat " + rate + " of the time" );
			d.require( reach < 0.25f, "an unspun flail rolled the spun floor " + spunFloor() + " " + reach + " of the time" );
		}).follow( d -> {
			for (int i = 1; i < SPINS; i++) d.use( Flail.class );
			Flail.SpinAbilityTracker spin = Dungeon.hero.buff( Flail.SpinAbilityTracker.class );
			d.require( spin != null && spin.spins == SPINS, "the flail did not spin " + SPINS + " times" );
			ATTACK.run( d );
		}).covers();
	}

	private static int spunFloor(){
		MeleeWeapon flail = (MeleeWeapon) Dungeon.hero.belongings.weapon;
		return flail.min() + SPINS * (8 + 2 * flail.buffedLvl()) - RAT_MAX_DR;
	}

	private static float unspunReach( int floor ){
		Random.pushGenerator( HIT_SAMPLE_SEED );
		try {
			int reached = 0;
			for (int i = 0; i < HIT_SAMPLES; i++) if (Dungeon.hero.damageRoll() >= floor) reached++;
			return reached / (float) HIT_SAMPLES;
		} finally {
			Random.popGenerator();
		}
	}

	private static final class Ability {
		private final Class< ? extends MeleeWeapon > type;
		private final boolean far;
		private final Scenario.Script aim;
		private final Supplier< BooleanSupplier > arm;
		private final String failure;
		private Scenario.Setup extra = ( world, kit ) -> {};
		private Scenario.Script before = d -> {};
		private Scenario.Script follow = d -> {};
		private Scenario.Script after = d -> {};

		private Ability( Class< ? extends MeleeWeapon > type, boolean far, Scenario.Script aim, Supplier< BooleanSupplier > arm,
				String failure ){
			this.type = type;
			this.far = far;
			this.aim = aim;
			this.arm = arm;
			this.failure = failure;
		}

		Ability setup( Scenario.Setup setup ){
			extra = setup;
			return this;
		}

		Ability before( Scenario.Script script ){
			before = script;
			return this;
		}

		Ability follow( Scenario.Script script ){
			follow = script;
			return this;
		}

		Ability then( Scenario.Script script ){
			after = script;
			return this;
		}

		Scenario covers( Class< ? >... more ){
			String name = type.getSimpleName();
			return new Scenario( "wability." + name, "use the " + name + "'s duelist ability", HeroClass.DUELIST,
					( world, kit ) -> {
						world.clearMobs();
						kit.sturdy();
						kit.strength( 20 );
						MeleeWeapon weapon = Reflection.newInstance( type );
						weapon.identify();
						kit.hero().belongings.weapon = weapon;
						weapon.activate( kit.hero() );
						MeleeWeapon.Charger charger = kit.buff( MeleeWeapon.Charger.class );
						charger.charges = charger.chargeCap();
						charger.partialCharge = 0;
						place( world, far );
						extra.build( world, kit );
					},
					d -> {
						before.run( d );
						float charge = charge();
						BooleanSupplier holds = arm.get();
						d.require( !holds.getAsBoolean(), "the effect held before the " + name + "'s ability" );
						d.use( type );
						aim.run( d );
						d.expect( charge() < charge, "the " + name + "'s ability left the charge at " + charge );
						follow.run( d );
						d.expect( holds.getAsBoolean(), failure );
						after.run( d );
					} ).covers( type ).covers( more );
		}
	}

	private static Ability ability( Class< ? extends MeleeWeapon > type, boolean far, Scenario.Script aim, Supplier< BooleanSupplier > arm,
			String failure ){
		return new Ability( type, far, aim, arm, failure );
	}

	private static void place( World world, boolean far ){
		int step = world.adjacentFreeWithRoomBehind( 2 ) - world.hero();
		if (far){
			Rat rat = world.mob( new Rat(), world.hero() + 2 * step );
			rat.HP = rat.HT = FAR_RAT_HP;
			Buff.affect( rat, Paralysis.class, 100f );
		} else {
			Rat rat = world.hunting( new Rat(), world.hero() + step );
			rat.HP = rat.HT = 100;
		}
	}

	private static Scenario mace(){
		return ability( Mace.class, false, AIM, () -> {
			Mob rat = rat();
			int hp = rat.HP;
			float rate = hitRate( rat );
			return () -> rat.HP < hp && rat.buff( Daze.class ) != null && hitRate( rat ) > rate;
		}, "the heavy blow did not daze the rat into being hit more often" ).before( d -> {
			d.act( Action.WAIT );
			d.require( rat().fieldOfView != null && rat().fieldOfView[ Dungeon.hero.pos ], "the rat did not see the hero before the blow" );
		}).then( d -> {
			Mob rat = rat();
			d.expect( d.until( () -> rat.buff( Daze.class ) == null, 8, () -> d.act( Action.WAIT )), "Daze never wore off the rat" );
		}).covers( Daze.class );
	}

	private static Scenario sword(){
		float[] base = new float[ 1 ];
		return ability( Sword.class, false, AIM, () -> {
			Mob rat = rat();
			return () -> !rat.isAlive() && Dungeon.hero.buff( GreaterHaste.class ) != null;
		}, "the killing cleave gave no greater haste" ).setup( ( world, kit ) -> {
			kit.hero().upgradeTalent( Talent.LETHAL_HASTE );
			Rat rat = new World().find( Rat.class );
			Buff.affect( rat, Paralysis.class, 100f );
			rat.HP = 1;
		}).before( d -> {
			d.act( Action.WAIT );
			int home = Dungeon.hero.pos;
			int away = d.world().adjacentFreeStraight();
			base[ 0 ] = PotionScenarios.cost( () -> d.move( away ));
			d.require( Dungeon.hero.pos == away, "the hero did not step from " + home + " to " + away );
			d.move( home );
		}).then( d -> {
			int away = d.world().adjacentFreeStraight();
			float fast = PotionScenarios.cost( () -> d.move( away ));
			d.expect( fast < base[ 0 ] - 0.5f, "a step under greater haste cost " + fast + " turns against " + base[ 0 ] + " before it" );
			PotionScenarios.wearsOff( d, GreaterHaste.class, 20 );
		}).covers( GreaterHaste.class );
	}

	private static Supplier< BooleanSupplier > hurt(){
		return () -> {
			Mob rat = rat();
			int hp = rat.HP;
			return () -> !rat.isAlive() || rat.HP < hp;
		};
	}

	private static Supplier< BooleanSupplier > lunged(){
		return () -> {
			int pos = Dungeon.hero.pos;
			BooleanSupplier hurt = hurt().get();
			return () -> Dungeon.hero.pos != pos && hurt.getAsBoolean();
		};
	}

	private static Supplier< BooleanSupplier > pushed(){
		return () -> {
			Mob rat = rat();
			int distance = Dungeon.level.distance( Dungeon.hero.pos, rat.pos );
			BooleanSupplier hurt = hurt().get();
			return () -> hurt.getAsBoolean() && Dungeon.level.distance( Dungeon.hero.pos, rat.pos ) > distance;
		};
	}

	private static float charge(){
		MeleeWeapon.Charger charger = Dungeon.hero.buff( MeleeWeapon.Charger.class );
		return charger == null ? 0 : charger.charges + charger.partialCharge;
	}

	private static float hitRate( Mob target ){
		Hero hero = Dungeon.hero;
		Random.pushGenerator( HIT_SAMPLE_SEED );
		try {
			int hits = 0;
			for (int i = 0; i < HIT_SAMPLES; i++) if (Char.hit( hero, target, false )) hits++;
			return hits / (float) HIT_SAMPLES;
		} finally {
			Random.popGenerator();
		}
	}

	private static Mob rat(){
		return new World().find( Rat.class );
	}
}
