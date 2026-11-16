package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barrier;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.Ratmogrify;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.ClericSpell;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.Bundlable;
import com.watabou.utils.Bundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class TalentFamily {

	private TalentFamily(){}

	static final class Probe {

		final Talent talent;
		final HeroClass hero;
		final Scenario.Setup setup;
		final Scenario.Script trigger;
		final Supplier< BooleanSupplier > effect;
		final String failure;

		Probe( Talent talent, HeroClass hero, Scenario.Setup setup, Scenario.Script trigger, Supplier< BooleanSupplier > effect, String failure ){
			this.talent = talent;
			this.hero = hero;
			this.setup = setup;
			this.trigger = trigger;
			this.effect = effect;
			this.failure = failure;
		}
	}

	static Scenario of( Probe probe ){
		Talent talent = probe.talent;
		int tier = classTier( probe.hero, talent );
		return new Scenario( "talent" + tier + "." + talent.name(), "choose " + talent.name() + " and see it act", probe.hero,
				( world, kit ) -> {
					world.clearMobs();
					preset( probe, kit );
					probe.setup.build( world, kit );
				},
				d -> {
					BooleanSupplier effect = probe.effect.get();
					d.require( !effect.getAsBoolean(), "the " + talent.name() + " effect held before the talent was chosen" );
					d.talent( talent );
					d.expect( Dungeon.hero.pointsInTalent( talent ) == 1, talent.name() + " has "
							+ Dungeon.hero.pointsInTalent( talent ) + " points after one choice" );
					probe.trigger.run( d );
					d.expect( effect.getAsBoolean(), probe.failure );
				} ).covers( Coverage.talent( talent ));
	}

	private static int classTier( HeroClass hero, Talent talent ){
		ArrayList< LinkedHashMap< Talent, Integer > > tiers = new ArrayList<>();
		Talent.initClassTalents( hero, tiers );
		for (HeroSubClass sub : hero.subClasses()) Talent.initSubclassTalents( sub, tiers );
		int tier = tierIn( tiers, talent );
		return tier > 0 ? tier : Talent.MAX_TALENT_TIERS;
	}

	private static int tierIn( ArrayList< LinkedHashMap< Talent, Integer > > tiers, Talent talent ){
		for (int i = 0; i < tiers.size(); i++) if (tiers.get( i ).containsKey( talent )) return i + 1;
		return 0;
	}

	private static void preset( Probe probe, HeroKit kit ){
		Hero hero = kit.hero();
		ArrayList< LinkedHashMap< Talent, Integer > > owned = new ArrayList<>();
		Talent.initClassTalents( probe.hero, owned );
		int tier = tierIn( owned, probe.talent );
		HeroSubClass subclass = probe.hero.subClasses()[ 0 ];
		ArmorAbility ability = null;
		for (HeroSubClass sub : probe.hero.subClasses()){
			if (tier > 0) break;
			ArrayList< LinkedHashMap< Talent, Integer > > scratch = new ArrayList<>();
			Talent.initSubclassTalents( sub, scratch );
			tier = tierIn( scratch, probe.talent );
			if (tier > 0) subclass = sub;
		}
		List< ArmorAbility > abilities = new ArrayList<>( Arrays.asList( probe.hero.armorAbilities() ));
		abilities.add( new Ratmogrify() );
		for (ArmorAbility candidate : abilities){
			if (tier > 0) break;
			ArrayList< LinkedHashMap< Talent, Integer > > scratch = new ArrayList<>();
			Talent.initArmorTalents( candidate, scratch );
			tier = tierIn( scratch, probe.talent );
			if (tier > 0) ability = candidate;
		}
		if (tier == 0) throw new Driver.Stop( probe.talent.name() + " is not a talent of " + probe.hero );
		kit.level( Talent.tierLevelThresholds[ tier ] );
		if (tier >= 3) kit.subclass( subclass );
		if (ability != null){
			hero.armorAbility = ability;
			Talent.initArmorTalents( hero );
		}
	}

	static void unmask( Driver d, Action action ){
		if (d.env().actionMask()[ action.index ] < 0.5f) d.act( Action.WAIT );
	}

	static Hero hero(){
		return Dungeon.hero;
	}

	static Rat rat(){
		return new World().find( Rat.class );
	}

	static boolean has( Class< ? extends Buff > type ){
		return hero().buff( type ) != null;
	}

	static int barrier(){
		Barrier barrier = hero().buff( Barrier.class );
		return barrier == null ? 0 : barrier.shielding();
	}

	static BooleanSupplier barrierGrew(){
		int before = barrier();
		return () -> barrier() > before;
	}

	static void use( Driver d, Class< ? extends Item > type ){
		unmask( d, Action.USE );
		d.use( type );
	}

	static void eat( Driver d ){
		use( d, Food.class );
	}

	static void aim( Driver d, Class< ? extends Item > type ){
		use( d, type );
		d.require( d.env().mode() == EnvMode.TARGETING, type.getSimpleName() + " did not ask for a target: " + d.env().mode() );
		d.aimNearest();
	}

	static void cast( Driver d, ClericSpell spell ){
		use( d, HolyTome.class );
		int option = 0;
		boolean found = false;
		for (int tier = 1; tier <= 3 && !found; tier++){
			List< ClericSpell > spells = ClericSpell.getSpellList( hero(), tier );
			int index = spells.indexOf( spell );
			if (index >= 0){
				option += index;
				found = true;
			} else {
				option += spells.size();
			}
		}
		d.require( found, spell.name() + " is not offered by the tome" );
		d.menu( 1 + option );
	}

	static int charges(){
		MeleeWeapon.Charger charger = hero().buff( MeleeWeapon.Charger.class );
		return charger == null ? 0 : charger.charges;
	}

	static float saved( Bundlable thing, String key ){
		Bundle bundle = new Bundle();
		thing.storeInBundle( bundle );
		return bundle.getFloat( key );
	}

	static void prime( Bundlable thing, String key, int value ){
		Bundle bundle = new Bundle();
		thing.storeInBundle( bundle );
		bundle.put( key, value );
		thing.restoreFromBundle( bundle );
	}
}
