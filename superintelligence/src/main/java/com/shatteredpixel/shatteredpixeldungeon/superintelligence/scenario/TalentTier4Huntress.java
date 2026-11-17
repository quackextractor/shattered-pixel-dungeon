package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.huntress.NaturesPower;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.huntress.SpectralBlades;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.huntress.SpiritHawk;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Huntress {

	private TalentTier4Huntress(){}

	static List<Scenario> all(){
		List<Scenario> all = new ArrayList<>();
		for (Talent t : new Talent[]{ Talent.GROWING_POWER, Talent.NATURES_WRATH, Talent.WILD_MOMENTUM }){
			all.add(TalentTier4.of(t, HeroClass.HUNTRESS, new NaturesPower(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
			}, d -> TalentTier4.useAbility(d), () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "nature's power did not spend armour charge"));
		}
		for (Talent t : new Talent[]{ Talent.FAN_OF_BLADES, Talent.PROJECTING_BLADES, Talent.SPIRIT_BLADES }){
			all.add(TalentTier4.of(t, HeroClass.HUNTRESS, new SpectralBlades(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
			}, d -> TalentTier4.useAbility(d), () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "spectral blades did not spend armour charge"));
		}
		for (Talent t : new Talent[]{ Talent.EAGLE_EYE, Talent.GO_FOR_THE_EYES, Talent.SWIFT_SPIRIT }){
			all.add(TalentTier4.of(t, HeroClass.HUNTRESS, new SpiritHawk(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
			}, d -> TalentTier4.useAbility(d), () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "spirit hawk did not spend armour charge"));
		}
		return all;
	}
}