package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.rogue.DeathMark;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.rogue.ShadowClone;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.rogue.SmokeBomb;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Rogue {

	private TalentTier4Rogue(){}

	static List<Scenario> all(){
		List<Scenario> all = new ArrayList<>();
		for (Talent t : new Talent[]{ Talent.FEAR_THE_REAPER, Talent.DEATHLY_DURABILITY, Talent.DOUBLE_MARK }){
			all.add(TalentTier4.of(t, HeroClass.ROGUE, new DeathMark(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
			}, d -> TalentTier4.useAbility(d), () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "death mark did not spend armour charge"));
		}
		for (Talent t : new Talent[]{ Talent.SHADOW_BLADE, Talent.CLONED_ARMOR, Talent.PERFECT_COPY }){
			all.add(TalentTier4.of(t, HeroClass.ROGUE, new ShadowClone(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
			}, d -> TalentTier4.useAbility(d), () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "shadow clone did not spend armour charge"));
		}
		for (Talent t : new Talent[]{ Talent.HASTY_RETREAT, Talent.BODY_REPLACEMENT, Talent.SHADOW_STEP }){
			all.add(TalentTier4.of(t, HeroClass.ROGUE, new SmokeBomb(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
			}, d -> {
				TalentFamily.unmask(d, Action.USE);
				d.use(ClassArmor.class);
				if (d.env().mode() == EnvMode.TARGETING) d.step(Action.USE, ActionMapper.SELF_TARGET_ALIAS);
			}, () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "smoke bomb did not spend armour charge"));
		}
		return all;
	}
}