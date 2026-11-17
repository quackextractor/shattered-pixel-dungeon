package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.ElementalBlast;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.WarpBeacon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.WildMagic;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Mage {

	private TalentTier4Mage(){}

	static List<Scenario> all(){
		List<Scenario> all = new ArrayList<>();
		for (Talent t : new Talent[]{ Talent.BLAST_RADIUS, Talent.ELEMENTAL_POWER, Talent.REACTIVE_BARRIER }){
			all.add(TalentTier4.of(t, HeroClass.MAGE, new ElementalBlast(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
			}, d -> TalentTier4.useAbility(d), () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "the blast did not spend armour charge"));
		}
		for (Talent t : new Talent[]{ Talent.TELEFRAG, Talent.REMOTE_BEACON, Talent.LONGRANGE_WARP }){
			all.add(TalentTier4.of(t, HeroClass.MAGE, new WarpBeacon(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
			}, d -> TalentTier4.useAbility(d), () -> {
				boolean before = Dungeon.hero.buff(com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.WarpBeacon.WarpBeaconTracker.class) != null;
				return () -> Dungeon.hero.buff(com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.WarpBeacon.WarpBeaconTracker.class) != null && !before;
			}, "the beacon was not placed"));
		}
		for (Talent t : new Talent[]{ Talent.WILD_POWER, Talent.FIRE_EVERYTHING, Talent.CONSERVED_MAGIC }){
			all.add(TalentTier4.of(t, HeroClass.MAGE, new WildMagic(), (world, kit) -> {
				Rat rat = world.mob(new Rat(), world.adjacentFree());
				rat.HP = rat.HT = 1000;
				Buff.affect(rat, Paralysis.class, 50f);
				kit.give(new WandOfMagicMissile());
			}, d -> TalentTier4.useAbility(d), () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "wild magic did not spend armour charge"));
		}
		return all;
	}
}