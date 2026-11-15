package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Adrenaline;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bless;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Chill;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Corrosion;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FlavourBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.PinCushion;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.MirrorImage;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.Bolas;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.FishingSpear;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ForceCube;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.HeavyBoomerang;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.Javelin;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.Kunai;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.MissileWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.Shuriken;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingClub;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingHammer;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingSpear;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingSpike;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.Tomahawk;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.Trident;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.AdrenalineDart;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.BlindingDart;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.ChillingDart;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.CleansingDart;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.DisplacingDart;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.HealingDart;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.HolyDart;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.IncendiaryDart;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.PoisonDart;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.RotDart;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.ShockingDart;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.TippedDart;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.RangedScenarios.Capture;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

final class MissileScenarios {

	private MissileScenarios(){}

	private static final long HIT_SAMPLE_SEED = 8L;

	private static final int HIT_SAMPLES = 400;

	interface Arm {
		BooleanSupplier arm( Mob ally );
	}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( stuck( Bolas.class, Cripple.class ));
		all.add( stuck( Tomahawk.class, Bleeding.class ));
		for (Class< ? extends MissileWeapon > type : List.of( FishingSpear.class, Javelin.class, Kunai.class, Shuriken.class,
				ThrowingSpear.class, ThrowingSpike.class, Trident.class )){
			all.add( stuck( type, null ));
		}
		all.add( missile( ThrowingClub.class, ( rat, before ) -> before.hurt( rat )));
		all.add( missile( ThrowingHammer.class, ( rat, before ) -> before.hurt( rat )));
		all.add( missile( HeavyBoomerang.class, ( rat, before ) -> before.hurt( rat ) && returned() ));
		all.add( RangedScenarios.thrown( "missile.ForceCube", ForceCube.class, ( world, kit ) -> {
					flank( world, kit );
					still( world, kit );
				},
				( rat, before ) -> before.hurt( rat ) && everyRatHurt() ));
		all.add( dart( BlindingDart.class, ( rat, before ) -> before.gained( rat, Blindness.class )));
		all.add( dart( ChillingDart.class, ( rat, before ) -> before.gained( rat, Chill.class )));
		all.add( RangedScenarios.thrown( "dart.CleansingDart", CleansingDart.class,
				( world, kit ) -> {
					Buff.affect( world.find( Rat.class ), Haste.class, 50f );
					still( world, kit );
				},
				( rat, before ) -> before.buffs.contains( Haste.class ) && rat.buff( Haste.class ) == null ));
		all.add( dart( DisplacingDart.class, ( rat, before ) -> Dungeon.level.distance( rat.pos, before.pos ) > 2 ));
		all.add( dart( IncendiaryDart.class, ( rat, before ) -> before.gained( rat, Burning.class )));
		all.add( dart( PoisonDart.class, ( rat, before ) -> before.gained( rat, Poison.class )));
		all.add( dart( RotDart.class, ( rat, before ) -> before.gained( rat, Corrosion.class )));
		all.add( dart( ShockingDart.class, ( rat, before ) -> !rat.isAlive() || before.hp - rat.HP > dartCeiling( ShockingDart.class )));
		all.add( ally( HealingDart.class, "the healing dart never healed the image", null, image -> {
			int hp = image.HP;
			return () -> image.HP > hp;
		}));
		all.add( ally( HolyDart.class, "the holy dart never blessed the image", Bless.class,
				image -> {
			Rat probe = new Rat();
			int hits = hitsOn( probe, image );
			return () -> image.buff( Bless.class ) != null && hitsOn( probe, image ) < hits;
		}).covers( Bless.class ));
		all.add( ally( AdrenalineDart.class, "the adrenaline dart never sped up the image", Adrenaline.class, image -> {
			float speed = image.speed();
			return () -> image.buff( Adrenaline.class ) != null && image.speed() > speed;
		}).covers( Adrenaline.class ));
		return all;
	}

	private static Scenario missile( Class< ? extends MissileWeapon > type, BiPredicate< Mob, Capture > hit ){
		return RangedScenarios.thrown( "missile." + type.getSimpleName(), type, MissileScenarios::still, hit );
	}

	private static Scenario dart( Class< ? extends TippedDart > type, BiPredicate< Mob, Capture > hit ){
		return RangedScenarios.thrown( "dart." + type.getSimpleName(), type, MissileScenarios::still, hit );
	}

	private static Scenario stuck( Class< ? extends MissileWeapon > type, Class< ? extends Buff > mark ){
		return missile( type, ( rat, before ) -> pinned( rat, before, type )
				&& (mark == null ? before.hurt( rat ) : before.gained( rat, mark ))).covers( PinCushion.class );
	}

	private static boolean pinned( Mob rat, Capture before, Class< ? extends MissileWeapon > type ){
		PinCushion pin = rat.buff( PinCushion.class );
		if (pin == null || before.buffs.contains( PinCushion.class )) return false;
		List< MissileWeapon > stuck = pin.getStuckItems();
		return stuck.size() == 1 && stuck.get( 0 ).getClass() == type && stuck.get( 0 ).quantity() == 1;
	}

	private static int hitsOn( Mob attacker, Mob defender ){
		Random.pushGenerator( HIT_SAMPLE_SEED );
		try {
			int hits = 0;
			for (int i = 0; i < HIT_SAMPLES; i++) if (Char.hit( attacker, defender, false )) hits++;
			return hits;
		} finally {
			Random.popGenerator();
		}
	}

	private static boolean returned(){
		Item pack = Dungeon.hero.belongings.getItem( HeavyBoomerang.class );
		return pack != null && pack.quantity() == RangedScenarios.GIVEN
				&& Dungeon.hero.buff( HeavyBoomerang.CircleBack.class ) == null;
	}

	private static void flank( World world, HeroKit kit ){
		Rat first = world.find( Rat.class );
		for (int offset : PathFinder.NEIGHBOURS8){
			int cell = first.pos + offset;
			if (world.free( cell ) && world.level().distance( cell, world.hero() ) >= 3){
				Rat second = world.mob( new Rat(), cell );
				second.HP = second.HT = 100;
				return;
			}
		}
		throw new NoFixture( "no free cell beside the target rat away from the hero" );
	}

	private static void still( World world, HeroKit kit ){
		for (Mob mob : world.level().mobs) if (mob instanceof Rat) Buff.affect( mob, Paralysis.class, 10f );
	}

	private static boolean everyRatHurt(){
		int rats = 0;
		for (Mob mob : Dungeon.level.mobs){
			if (!(mob instanceof Rat)) continue;
			rats++;
			if (mob.HP >= mob.HT) return false;
		}
		return rats == 2;
	}

	private static int dartCeiling( Class< ? extends TippedDart > type ){
		MissileWeapon pack = (MissileWeapon) Dungeon.hero.belongings.getItem( type );
		return pack.max() + Math.max( 0, Dungeon.hero.STR() - pack.STRReq() );
	}

	private static Scenario ally( Class< ? extends TippedDart > type, String failure, Class< ? extends FlavourBuff > wears, Arm arm ){
		String name = type.getSimpleName();
		return new Scenario( "dart." + name, "throw " + name + " at an ally", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( Reflection.newInstance( type ).quantity( RangedScenarios.GIVEN ));
					MirrorImage image = new MirrorImage();
					image.duplicate( kit.hero() );
					image.HT = 100;
					image.HP = 30;
					world.mob( image, world.adjacentFreeStraight() );
				},
				d -> {
					MirrorImage image = d.world().find( MirrorImage.class );
					Item pack = Dungeon.hero.belongings.getItem( type );
					int quantity = pack.quantity();
					BooleanSupplier holds = arm.arm( image );
					d.require( !holds.getAsBoolean(), "the effect held before the " + name + " was thrown" );
					d.throwItem( type );
					d.require( d.env().mode() == EnvMode.TARGETING, name + " did not ask for a target: " + d.env().mode() );
					d.step( Action.USE, d.direction( image.pos ));
					d.expect( pack.quantity() == quantity - 1, "a throw left " + pack.quantity() + " of " + quantity );
					d.expect( d.until( holds, RangedScenarios.HIT_WAITS, () -> d.act( Action.WAIT )), failure );
					if (wears != null){
						d.expect( d.until( () -> image.buff( wears ) == null, 40, () -> d.act( Action.WAIT )),
								"the " + wears.getSimpleName() + " on the image never wore off" );
					}
				} ).covers( type );
	}
}
