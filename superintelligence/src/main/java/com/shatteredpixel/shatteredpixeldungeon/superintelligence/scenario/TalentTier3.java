package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barrier;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Berserk;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Combo;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.HoldFast;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.BrokenSeal;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.Random;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

final class TalentTier3 {

	private TalentTier3(){}

	static final int SAMPLES = 200;

	static final long SAMPLE_SEED = 0x3203F1L;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.addAll( warrior() );
		all.addAll( TalentTier3Mage.all() );
		all.addAll( TalentTier3Rogue.all() );
		all.addAll( TalentTier3Huntress.all() );
		all.addAll( TalentTier3Duelist.all() );
		all.addAll( TalentTier3Cleric.all() );
		return all;
	}

	private static List< Scenario > warrior(){
		List< Scenario > all = new ArrayList<>();
		all.add( of( Talent.HOLD_FAST, HeroClass.WARRIOR, ( world, kit ) -> kit.sturdy(), d -> d.act( Action.WAIT ), () -> {
			float base = mean( () -> hero().drRoll() );
			return () -> has( HoldFast.class ) && mean( () -> hero().drRoll() ) > base;
		}, "waiting gave no hold fast armour" ).covers( HoldFast.class ));
		all.add( of( Talent.STRONGMAN, HeroClass.WARRIOR, ( world, kit ) -> kit.strength( 13 ), d -> d.act( Action.WAIT ), () -> {
			int base = hero().STR();
			return () -> hero().STR() > base;
		}, "the hero gained no strength" ));
		all.add( of( Talent.ENDLESS_RAGE, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.health( 1000, 1000 );
			Berserk berserk = Buff.affect( hero(), Berserk.class );
			TalentFamily.prime( berserk, "power", 1f );
			TalentFamily.prime( berserk, "power_buffer", 50 );
			world.hunting( new Rat(), world.adjacentFree() );
		}, d -> d.waitFor( () -> hero().HP < 1000, 20 ), () -> {
			Berserk berserk = hero().buff( Berserk.class );
			return () -> TalentFamily.saved( berserk, "power" ) > 1f;
		}, "the hit pushed rage no higher than full" ));
		all.add( of( Talent.DEATHLESS_FURY, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.health( 1, 1000 );
			Berserk berserk = Buff.affect( hero(), Berserk.class );
			TalentFamily.prime( berserk, "power", 1f );
			TalentFamily.prime( berserk, "power_buffer", 50 );
			world.hunting( new Rat(), world.adjacentFree() );
		}, d -> d.waitFor( () -> hero().HP == 0, 10 ), () -> {
			Berserk berserk = hero().buff( Berserk.class );
			return () -> TalentFamily.saved( berserk, "levelrecovery" ) > 0f
					&& TalentFamily.saved( berserk, "levelrecovery" ) < 4f;
		}, "dying to the rat left the berserker without a shortened recovery" ));
		all.add( of( Talent.ENRAGED_CATALYST, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.sturdy();
			Berserk berserk = Buff.affect( hero(), Berserk.class );
			TalentFamily.prime( berserk, "power", 1f );
			TalentFamily.prime( berserk, "power_buffer", 50 );
		}, d -> d.act( Action.WAIT ), () -> {
			Berserk berserk = hero().buff( Berserk.class );
			float base = berserk.enchantFactor( 0.1f );
			return () -> berserk.enchantFactor( 0.1f ) > base;
		}, "full rage added nothing to the enchant chance" ));
		all.add( of( Talent.CLEAVE, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.sturdy();
			weakenedRat( world, world.adjacentFree() );
		}, d -> d.move( rat().pos ), () -> {
			int base = comboTime();
			return () -> comboTime() > base;
		}, "the kill left no extended combo" ));
		all.add( of( Talent.LETHAL_DEFENSE, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.sturdy();
			for (int i = 0; i < 5; i++) weakenedRat( world, world.adjacentFree() );
		}, d -> {
			for (int i = 0; i < 4 && d.env().running(); i++){
				List< Rat > alive = TalentTier3.weakened();
				d.require( alive.size() > 1, "ran out of rats before the combo was built" );
				d.move( alive.get( 0 ).pos );
			}
			TalentTier3.comboMove( d, 1 );
			d.waitFor( () -> TalentTier3.rats().isEmpty(), 8 );
		}, () -> {
			BrokenSeal.WarriorShield shield = hero().buff( BrokenSeal.WarriorShield.class );
			float base = TalentFamily.saved( shield, BrokenSeal.WarriorShield.COOLDOWN );
			return () -> TalentFamily.saved( hero().buff( BrokenSeal.WarriorShield.class ),
					BrokenSeal.WarriorShield.COOLDOWN ) < base - 10f;
		}, "the combo kill left the seal cooldown unchanged" ));
		all.add( of( Talent.ENHANCED_COMBO, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.sturdy();
			for (int i = 0; i < 7; i++) weakenedRat( world, world.adjacentFree() );
			Rat target = world.mob( new Rat(), world.adjacentFree() );
			target.HP = target.HT = 1000;
			Buff.affect( target, Paralysis.class, 50f );
		}, d -> {
			for (int i = 0; i < 7 && d.env().running(); i++){
				List< Rat > alive = TalentTier3.weakened();
				d.require( !alive.isEmpty(), "ran out of rats before the combo was built" );
				d.move( alive.get( 0 ).pos );
			}
			TalentTier3.clobber( d );
		}, () -> {
			Rat target = sturdyRat();
			return () -> target.buff( com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Vertigo.class ) != null;
		}, "the seventh-hit clobber dealt no vertigo" ));
		return all;
	}

	static void clobber( Driver d ){
		comboMove( d, 0 );
	}

	static void comboMove( Driver d, int option ){
		d.indicator();
		d.require( d.env().mode() == EnvMode.MENU, "the combo move list did not open: " + d.env().mode() );
		d.menu( option );
		d.require( d.env().mode() == EnvMode.TARGETING, "the combo move asked for no target: " + d.env().mode() );
		d.aimNearest();
		d.waitFor( () -> hero().ready, 6 );
	}

	static List< Rat > rats(){
		List< Rat > alive = new ArrayList<>();
		for (Rat rat : new World().level().mobs.toArray( new Rat[ 0 ] )) if (rat.isAlive()) alive.add( rat );
		return alive;
	}

	static List< Rat > weakened(){
		List< Rat > hurt = new ArrayList<>();
		for (Rat rat : new World().level().mobs.toArray( new Rat[ 0 ] )) if (rat.isAlive() && rat.HP <= 1) hurt.add( rat );
		return hurt;
	}

	static Rat sturdyRat(){
		for (Rat rat : new World().level().mobs.toArray( new Rat[ 0 ] )) if (rat.isAlive() && rat.HP > 1) return rat;
		throw new Driver.Stop( "no sturdy rat on the level" );
	}

	static void weakenedRat( World world, int cell ){
		Rat rat = world.mob( new Rat(), cell );
		rat.HP = 1;
		Buff.affect( rat, Paralysis.class, 50f );
	}

	static int comboTime(){
		Combo combo = hero().buff( Combo.class );
		return combo == null ? 0 : Integer.parseInt( combo.iconTextDisplay() );
	}

	static Scenario of( Talent talent, HeroClass hero, Scenario.Setup setup, Scenario.Script trigger,
			Supplier< BooleanSupplier > effect, String failure ){
		return TalentFamily.of( new TalentFamily.Probe( talent, hero, setup, trigger, effect, failure ));
	}

	static Hero hero(){
		return Dungeon.hero;
	}

	static Rat rat(){
		return new World().find( Rat.class );
	}

	static void rat( World world, int cell ){
		Rat rat = world.mob( new Rat(), cell );
		rat.HP = rat.HT = 1000;
		Buff.affect( rat, Paralysis.class, 50f );
	}

	static void use( Driver d, Class< ? extends Item > type ){
		TalentFamily.unmask( d, Action.USE );
		d.use( type );
	}

	static void aim( Driver d, Class< ? extends Item > type ){
		use( d, type );
		d.require( d.env().mode() == EnvMode.TARGETING, type.getSimpleName() + " did not ask for a target: " + d.env().mode() );
		d.aimNearest();
	}

	static void ability( Driver d, Class< ? extends Item > type ){
		use( d, type );
		if (d.env().mode() == EnvMode.TARGETING) d.aimNearest();
	}

	static boolean has( Class< ? extends Buff > type ){
		return hero().buff( type ) != null;
	}

	static int barrier(){
		Barrier barrier = hero().buff( Barrier.class );
		return barrier == null ? 0 : barrier.shielding();
	}

	static float mean( IntSupplier roll ){
		Random.pushGenerator( SAMPLE_SEED );
		try {
			int total = 0;
			for (int i = 0; i < SAMPLES; i++) total += roll.getAsInt();
			return total / (float) SAMPLES;
		} finally {
			Random.popGenerator();
		}
	}

	static boolean subclass( Hero hero, HeroSubClass sub ){
		return hero.subClass == sub;
	}
}
