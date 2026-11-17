package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.Ratmogrify;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Skeleton;
import com.shatteredpixel.shatteredpixeldungeon.items.KingsCrown;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClericArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.DuelistArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.HuntressArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.LeatherArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.MageArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.RogueArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.WarriorArmor;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
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
		all.add(ratmogrify());
		all.addAll(AbilityUseScenarios.all());
		return all;
	}

	private static Scenario ratmogrify(){
		return new Scenario("armor-ability.Ratmogrify", "use Ratmogrify on a rat", HeroClass.WARRIOR,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength(18);
					WarriorArmor armor = new WarriorArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new Ratmogrify());
					new com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator();
					Skeleton rat = world.hunting(new Skeleton(), world.adjacentFree());
					rat.HP = rat.HT = 100;
				},
				d -> {
					float before = AbilityUseScenarios.charge();
					Skeleton rat = d.world().find(Skeleton.class);
					d.use(WarriorArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "Ratmogrify opened " + d.env().mode());
					d.step(Action.USE, d.direction(rat.pos));
					d.expect(AbilityUseScenarios.charge() < before, "Ratmogrify left charge at " + AbilityUseScenarios.charge() + " from " + before);
					boolean hasTransmog = false;
					for (Mob m : Dungeon.level.mobs) if (m instanceof Ratmogrify.TransmogRat) hasTransmog = true;
					d.expect(hasTransmog, "Ratmogrify spawned no TransmogRat");
				}).covers(Ratmogrify.class, Ratmogrify.TransmogRat.class);
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
					d.use(KingsCrown.class);
					d.require(d.env().mode() == EnvMode.MENU, "the crown opened " + d.env().mode());
					d.menu(0);
					d.require(d.env().mode() == EnvMode.MENU, "the ability choice opened " + d.env().mode());
					d.menu(0);
					d.expect(Dungeon.hero.armorAbility != null, "the crown gave no ability");
					d.expect(armorClass.isInstance(Dungeon.hero.belongings.armor()), "the crown left " + Dungeon.hero.belongings.armor());
				}).covers(armorClass, KingsCrown.class);
	}
}
