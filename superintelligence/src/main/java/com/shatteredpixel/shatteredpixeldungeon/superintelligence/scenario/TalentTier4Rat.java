package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.Ratmogrify;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Bat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.World;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Rat {

	private TalentTier4Rat(){}

	static List<Scenario> all(){
		List<Scenario> all = new ArrayList<>();
		for (Talent t : new Talent[]{ Talent.RATSISTANCE, Talent.RATLOMACY, Talent.RATFORCEMENTS }){
			all.add(TalentTier4.of(t, HeroClass.ROGUE, new Ratmogrify(), (world, kit) -> {
				Bat bat = world.mob(new Bat(), world.adjacentFree());
				bat.HP = bat.HT = 1000;
				Buff.affect(bat, Paralysis.class, 50f);
				TargetHealthIndicator.instance = new TargetHealthIndicator();
			}, d -> {
				TalentFamily.unmask(d, Action.USE);
				d.use(ClassArmor.class);
				if (d.env().mode() == EnvMode.TARGETING) {
					Bat bat = new World().find(Bat.class);
					d.step(Action.USE, d.direction(bat.pos));
				}
			}, () -> {
				ClassArmor ca = TalentTier4.armor();
				float before = ca.charge;
				return () -> TalentTier4.armor().charge < before;
			}, "ratmogrify did not spend armour charge"));
		}
		return all;
	}
}