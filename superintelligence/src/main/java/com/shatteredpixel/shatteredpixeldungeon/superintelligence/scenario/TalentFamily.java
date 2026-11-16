package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.Ratmogrify;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
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
