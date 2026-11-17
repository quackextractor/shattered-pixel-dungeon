package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Chill;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Levitation;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.huntress.NaturesPower;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.huntress.SpectralBlades;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.huntress.SpiritHawk;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.SpiritBow;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Huntress {

	private TalentTier4Huntress(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( TalentTier4.of( Talent.SPIRIT_BLADES, HeroClass.HUNTRESS, new SpectralBlades(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 100000;
			Buff.affect( rat, Paralysis.class, 50f );
		}, d -> TalentTier4.useAbility( d ), () -> {
			Rat rat = new World().find( Rat.class );
			int base = rat.HP;
			return () -> rat.HP < base;
		}, "the blades struck nothing" ));
		all.add( TalentTier4.of( Talent.FAN_OF_BLADES, HeroClass.HUNTRESS, new SpectralBlades(), ( world, kit ) -> {
			Rat primary = world.mob( new Rat(), world.adjacentFree() );
			primary.HP = primary.HT = 100000;
			Buff.affect( primary, Paralysis.class, 50f );
		}, d -> TalentTier4.useAbility( d ), TalentTier4.chosen( Talent.FAN_OF_BLADES ),
				"the blades fanned out to no second target" ));
		all.add( TalentTier4.of( Talent.PROJECTING_BLADES, HeroClass.HUNTRESS, new SpectralBlades(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.cellAtRange( 3, true ));
			rat.HP = rat.HT = 100000;
			Buff.affect( rat, Paralysis.class, 50f );
		}, d -> TalentTier4.useAbility( d ), () -> {
			Rat rat = new World().find( Rat.class );
			int base = rat.HP;
			return () -> rat.HP < base;
		}, "the blades did not pierce the wall" ));
		all.add( TalentTier4.of( Talent.GROWING_POWER, HeroClass.HUNTRESS, new NaturesPower(), ( world, kit ) -> {},
				d -> TalentTier4.useAbility( d ), () -> {
			float base = Dungeon.hero.speed();
			return () -> Dungeon.hero.speed() > base * 2f;
		}, "nature's power left the hero at base speed" ));
		all.add( TalentTier4.of( Talent.NATURES_WRATH, HeroClass.HUNTRESS, new NaturesPower(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 100000;
		}, d -> TalentTier4.useAbility( d ), () -> () -> Dungeon.hero.buff( NaturesPower.naturesPowerTracker.class ) != null, "nature's power granted no tracker" ));
		all.add( TalentTier4.of( Talent.WILD_MOMENTUM, HeroClass.HUNTRESS, new NaturesPower(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = 1;
			rat.HT = 100000;
		}, d -> TalentTier4.useAbility( d ), () -> () -> Dungeon.hero.buff( NaturesPower.naturesPowerTracker.class ) != null, "nature's power granted no tracker" ));
		all.add( TalentTier4.of( Talent.EAGLE_EYE, HeroClass.HUNTRESS, new SpiritHawk(), ( world, kit ) -> {},
				d -> TalentTier4.useAbility( d ), () -> () -> {
			SpiritHawk.HawkAlly hawk = hawk();
			return hawk != null && hawk.viewDistance > 6;
		}, "the hawk saw no further" ));
		all.add( TalentTier4.of( Talent.GO_FOR_THE_EYES, HeroClass.HUNTRESS, new SpiritHawk(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 100000;
		}, d -> TalentTier4.useAbility( d ), () -> () -> {
			SpiritHawk.HawkAlly hawk = hawk();
			if (hawk == null) return false;
			Rat rat = new World().find( Rat.class );
			hawk.attackProc( rat, 10 );
			return rat.buff( Blindness.class ) != null;
		}, "the hawk strike blinded nothing" ));
		all.add( TalentTier4.of( Talent.SWIFT_SPIRIT, HeroClass.HUNTRESS, new SpiritHawk(), ( world, kit ) -> {},
				d -> TalentTier4.useAbility( d ), () -> () -> {
			SpiritHawk.HawkAlly hawk = hawk();
			return hawk != null && hawk.speed() > 2f;
		}, "the hawk flew no faster" ));
		return all;
	}

	private static SpiritHawk.HawkAlly hawk(){
		for (com.shatteredpixel.shatteredpixeldungeon.actors.Char ch : Actor.chars())
			if (ch instanceof SpiritHawk.HawkAlly) return (SpiritHawk.HawkAlly) ch;
		return null;
	}
}
