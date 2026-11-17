package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.AscendedForm;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.PowerOfMany;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.Trinity;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Cleric {

	private TalentTier4Cleric(){}

	static List<Scenario> all(){
		List<Scenario> all = new ArrayList<>();
		for (Talent t : new Talent[]{ Talent.DIVINE_INTERVENTION, Talent.JUDGEMENT, Talent.FLASH }){
			all.add(TalentTier4.of(t, HeroClass.CLERIC, new AscendedForm(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
			}, d -> TalentTier4.useAbility(d), () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "ascended form did not spend armour charge"));
		}
		for (Talent t : new Talent[]{ Talent.BEAMING_RAY, Talent.LIFE_LINK, Talent.STASIS }){
			all.add(TalentTier4.of(t, HeroClass.CLERIC, new PowerOfMany(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
			}, d -> {
				TalentFamily.unmask(d, Action.USE);
				d.use(ClassArmor.class);
				if (d.env().mode() == EnvMode.TARGETING) d.step(Action.USE, 1);
			}, () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "power of many did not spend armour charge"));
		}
		for (Talent t : new Talent[]{ Talent.BODY_FORM, Talent.MIND_FORM, Talent.SPIRIT_FORM }){
			all.add(TalentTier4.of(t, HeroClass.CLERIC, new Trinity(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
				try {
					java.lang.reflect.Field f = com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.Trinity.class.getDeclaredField("bodyForm");
					f.setAccessible(true);
					f.set(kit.hero().armorAbility, new com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Blazing());
				} catch (Exception e){
					throw new NoFixture(e.toString());
				}
			}, d -> {
				TalentTier4.useAbility(d);
				if (d.env().mode() == EnvMode.MENU) d.menu(0);
			}, () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "trinity did not spend armour charge"));
		}
		return all;
	}
}