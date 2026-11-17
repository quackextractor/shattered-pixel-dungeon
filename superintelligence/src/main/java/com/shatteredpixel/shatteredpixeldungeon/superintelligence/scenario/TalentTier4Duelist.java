package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.duelist.Challenge;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.duelist.ElementalStrike;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.duelist.Feint;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Duelist {

	private TalentTier4Duelist(){}

	static List<Scenario> all(){
		List<Scenario> all = new ArrayList<>();
		for (Talent t : new Talent[]{ Talent.CLOSE_THE_GAP, Talent.INVIGORATING_VICTORY, Talent.ELIMINATION_MATCH }){
			all.add(TalentTier4.of(t, HeroClass.DUELIST, new Challenge(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
			}, d -> TalentTier4.useAbility(d), () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "challenge did not spend armour charge"));
		}
		for (Talent t : new Talent[]{ Talent.ELEMENTAL_REACH, Talent.STRIKING_FORCE, Talent.DIRECTED_POWER }){
			all.add(TalentTier4.of(t, HeroClass.DUELIST, new ElementalStrike(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
			}, d -> TalentTier4.useAbility(d), () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "elemental strike did not spend armour charge"));
		}
		for (Talent t : new Talent[]{ Talent.FEIGNED_RETREAT, Talent.EXPOSE_WEAKNESS, Talent.COUNTER_ABILITY }){
			all.add(TalentTier4.of(t, HeroClass.DUELIST, new Feint(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
				TargetHealthIndicator.instance = new TargetHealthIndicator();
			}, d -> {
				TalentFamily.unmask(d, Action.USE);
				d.use(ClassArmor.class);
				if (d.env().mode() == EnvMode.TARGETING) d.step(Action.USE, 1);
			}, () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "feint did not spend armour charge"));
		}
		return all;
	}
}