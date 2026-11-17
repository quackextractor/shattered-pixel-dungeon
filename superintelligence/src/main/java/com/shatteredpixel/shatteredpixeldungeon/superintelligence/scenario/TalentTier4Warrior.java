package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.warrior.Endure;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.warrior.HeroicLeap;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.warrior.Shockwave;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.World;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Warrior {

	private TalentTier4Warrior(){}

	static List<Scenario> all(){
		List<Scenario> all = new ArrayList<>();
		all.add(TalentTier4.of(Talent.BODY_SLAM, HeroClass.WARRIOR, new HeroicLeap(), (world, kit) -> {
			Rat rat = world.mob(new Rat(), world.adjacentFree());
			rat.HP = rat.HT = 1000;
			Buff.affect(rat, Paralysis.class, 50f);
		}, d -> {
			TalentTier4.useAbility(d);
		}, () -> {
			ClassArmor ca = TalentTier4.armor();
			float before = ca.charge;
			return () -> TalentTier4.armor().charge < before;
		}, "the leap did not spend armour charge"));
		all.add(TalentTier4.of(Talent.IMPACT_WAVE, HeroClass.WARRIOR, new HeroicLeap(), (world, kit) -> {
			Rat rat = world.mob(new Rat(), world.adjacentFree());
			rat.HP = rat.HT = 1000;
			Buff.affect(rat, Paralysis.class, 50f);
		}, d -> {
			TalentTier4.useAbility(d);
		}, () -> {
			ClassArmor ca = TalentTier4.armor();
			float before = ca.charge;
			return () -> TalentTier4.armor().charge < before;
		}, "the leap did not spend armour charge"));
		all.add(TalentTier4.of(Talent.DOUBLE_JUMP, HeroClass.WARRIOR, new HeroicLeap(), (world, kit) -> {
			Rat rat = world.mob(new Rat(), world.adjacentFree());
			rat.HP = rat.HT = 1000;
			Buff.affect(rat, Paralysis.class, 50f);
		}, d -> {
			TalentTier4.useAbility(d);
		}, () -> {
			ClassArmor ca = TalentTier4.armor();
			float before = ca.charge;
			return () -> TalentTier4.armor().charge < before;
		}, "the leap did not spend armour charge"));
		all.add(TalentTier4.of(Talent.EXPANDING_WAVE, HeroClass.WARRIOR, new Shockwave(), (world, kit) -> {
			Rat rat = world.mob(new Rat(), world.cellAtRange(6, true));
			rat.HP = rat.HT = 1000;
			Buff.affect(rat, Paralysis.class, 50f);
		}, d -> {
			TalentTier4.useAbility(d);
		}, () -> {
			Rat rat = new World().find(Rat.class);
			int base = rat.HP;
			return () -> rat.HP < base;
		}, "the wave did not reach the distant rat"));
		all.add(TalentTier4.of(Talent.STRIKING_WAVE, HeroClass.WARRIOR, new Shockwave(), (world, kit) -> {
			Rat rat = world.mob(new Rat(), world.adjacentFree());
			rat.HP = rat.HT = 1000;
			Buff.affect(rat, Paralysis.class, 50f);
		}, d -> {
			TalentTier4.useAbility(d);
		}, () -> {
			ClassArmor ca = TalentTier4.armor();
			float before = ca.charge;
			return () -> TalentTier4.armor().charge < before;
		}, "the wave did not spend armour charge"));
		all.add(TalentTier4.of(Talent.SHOCK_FORCE, HeroClass.WARRIOR, new Shockwave(), (world, kit) -> {
			Rat rat = world.mob(new Rat(), world.adjacentFree());
			rat.HP = rat.HT = 1000;
			Buff.affect(rat, Paralysis.class, 50f);
		}, d -> {
			TalentTier4.useAbility(d);
		}, () -> {
			ClassArmor ca = TalentTier4.armor();
			float before = ca.charge;
			return () -> TalentTier4.armor().charge < before;
		}, "the wave did not spend armour charge"));
		all.add(TalentTier4.of(Talent.SUSTAINED_RETRIBUTION, HeroClass.WARRIOR, new Endure(), (world, kit) -> {}, d -> {
			TalentTier4.useAbility(d);
		}, () -> {
			ClassArmor ca = TalentTier4.armor();
			float before = ca.charge;
			return () -> TalentTier4.armor().charge < before;
		}, "endure did not spend armour charge"));
		all.add(TalentTier4.of(Talent.SHRUG_IT_OFF, HeroClass.WARRIOR, new Endure(), (world, kit) -> {}, d -> {
			TalentTier4.useAbility(d);
		}, () -> {
			ClassArmor ca = TalentTier4.armor();
			float before = ca.charge;
			return () -> TalentTier4.armor().charge < before;
		}, "endure did not spend armour charge"));
		all.add(TalentTier4.of(Talent.EVEN_THE_ODDS, HeroClass.WARRIOR, new Endure(), (world, kit) -> {}, d -> {
			TalentTier4.useAbility(d);
		}, () -> {
			ClassArmor ca = TalentTier4.armor();
			float before = ca.charge;
			return () -> TalentTier4.armor().charge < before;
		}, "endure did not spend armour charge"));
		all.add(TalentTier4.of(Talent.HEROIC_ENERGY, HeroClass.WARRIOR, new Shockwave(), (world, kit) -> {
			Rat rat = world.mob(new Rat(), world.adjacentFree());
			rat.HP = rat.HT = 1000;
			Buff.affect(rat, Paralysis.class, 50f);
		}, d -> {
			TalentTier4.useAbility(d);
		}, () -> {
			float base = TalentTier4.hero().armorAbility.chargeUse(TalentTier4.hero());
			return () -> TalentTier4.hero().armorAbility.chargeUse(TalentTier4.hero()) < base;
		}, "heroic energy did not lower the charge cost"));
		return all;
	}
}