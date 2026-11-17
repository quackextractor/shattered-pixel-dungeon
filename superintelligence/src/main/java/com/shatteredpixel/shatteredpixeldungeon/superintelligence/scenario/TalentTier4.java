package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClericArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.DuelistArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.HuntressArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.MageArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.RogueArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.WarriorArmor;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4 {

	private TalentTier4(){}

	static List<Scenario> all(){
		List<Scenario> all = new ArrayList<>();
		all.addAll(TalentTier4Warrior.all());
		all.addAll(TalentTier4Mage.all());
		all.addAll(TalentTier4Rogue.all());
		all.addAll(TalentTier4Huntress.all());
		all.addAll(TalentTier4Duelist.all());
		all.addAll(TalentTier4Cleric.all());
		all.addAll(TalentTier4Rat.all());
		return all;
	}

	static Hero hero(){ return Dungeon.hero; }

	static ClassArmor armor(){
		if (hero().belongings.armor instanceof ClassArmor) return (ClassArmor) hero().belongings.armor;
		for (Item i : hero().belongings.backpack.items) if (i instanceof ClassArmor) return (ClassArmor) i;
		return null;
	}

	static ClassArmor classArmorFor(HeroClass hero){
		switch (hero){
			case WARRIOR: default: return new WarriorArmor();
			case MAGE: return new MageArmor();
			case ROGUE: return new RogueArmor();
			case HUNTRESS: return new HuntressArmor();
			case DUELIST: return new DuelistArmor();
			case CLERIC: return new ClericArmor();
		}
	}

	static void useAbility(Driver d){
		TalentFamily.unmask(d, Action.USE);
		d.use(ClassArmor.class);
		if (d.env().mode() == EnvMode.TARGETING) d.aimNearest();
	}

	static Scenario of(Talent talent, HeroClass hero, ArmorAbility ability, Scenario.Setup setup, Scenario.Script trigger,
			java.util.function.Supplier<java.util.function.BooleanSupplier> effect, String failure){
		return TalentFamily.of(new TalentFamily.Probe(talent, hero, (world, kit) -> {
			kit.sturdy();
			kit.level(21);
			kit.subclass(hero.subClasses()[0]);
			kit.ability(ability);
			ClassArmor ca = classArmorFor(hero);
			ca.charge = 100f;
			kit.hero().belongings.armor = ca;
			ca.activate(kit.hero());
			setup.build(world, kit);
		}, trigger, effect, failure)).depth(8);
	}
}