package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility;
import com.shatteredpixel.shatteredpixeldungeon.items.KingsCrown;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClericArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.DuelistArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.HuntressArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.LeatherArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.MageArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.RogueArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.WarriorArmor;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class AbilityScenarios {

	private AbilityScenarios(){}

	static List<Scenario> all(){
		List<Scenario> all = new ArrayList<>();
		all.add(crown(HeroClass.WARRIOR, WarriorArmor.class));
		all.add(crown(HeroClass.ROGUE, RogueArmor.class));
		all.add(crown(HeroClass.MAGE, MageArmor.class));
		all.add(crown(HeroClass.HUNTRESS, HuntressArmor.class));
		all.add(crown(HeroClass.DUELIST, DuelistArmor.class));
		all.add(crown(HeroClass.CLERIC, ClericArmor.class));
		all.addAll(AbilitySummonScenarios.all());
		all.addAll(AbilityUseScenarios.all());
		return all;
	}

	private static Scenario crown(HeroClass heroClass, Class<? extends ClassArmor> armorClass){
		return new Scenario("armor-ability.crown." + heroClass.name(), "choose an ability with the KingsCrown", heroClass,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.hero().belongings.armor = new LeatherArmor();
					kit.give(new KingsCrown());
				},
				d -> {
					ArmorAbility before = Dungeon.hero.armorAbility;
					d.use(KingsCrown.class);
					d.require(d.env().mode() == EnvMode.MENU, "the crown opened " + d.env().mode());
					d.menu(0);
					d.require(d.env().mode() == EnvMode.MENU, "the ability choice opened " + d.env().mode());
					d.menu(0);
					ArmorAbility after = Dungeon.hero.armorAbility;
					d.expect(after != null && after != before, "the crown left the ability at " + after);
					boolean offered = false;
					for (ArmorAbility ability : heroClass.armorAbilities()){
						if (after != null && ability.getClass() == after.getClass()) offered = true;
					}
					d.expect(offered, "the crown gave " + after + " to " + heroClass);
					d.expect(armorClass.isInstance(Dungeon.hero.belongings.armor()), "the crown left " + Dungeon.hero.belongings.armor());
				}).covers(armorClass, KingsCrown.class);
	}
}
