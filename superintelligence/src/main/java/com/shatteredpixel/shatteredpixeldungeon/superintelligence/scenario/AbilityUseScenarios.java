package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.AscendedForm;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.PowerOfMany;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.Trinity;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.duelist.Challenge;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.duelist.ElementalStrike;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.duelist.Feint;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.huntress.NaturesPower;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.huntress.SpectralBlades;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.huntress.SpiritHawk;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.ElementalBlast;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.WarpBeacon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.WildMagic;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.rogue.DeathMark;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.rogue.ShadowClone;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.rogue.SmokeBomb;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.warrior.Endure;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.warrior.HeroicLeap;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.warrior.Shockwave;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClericArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.DuelistArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.HuntressArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.MageArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.RogueArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.WarriorArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Blazing;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Longsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.Shuriken;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.BodyForm;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class AbilityUseScenarios {

	private AbilityUseScenarios(){}

	static float charge(){
		ClassArmor armor = (ClassArmor) Dungeon.hero.belongings.armor();
		return armor == null ? 0f : armor.charge;
	}

	private static int count(Class<? extends Mob> type){
		int n = 0;
		for (Mob m : Dungeon.level.mobs) if (type.isInstance(m)) n++;
		return n;
	}

	static List<Scenario> all(){
		List<Scenario> all = new ArrayList<>();
		all.add(ascendedForm());
		all.add(powerOfMany());
		all.add(trinity());
		all.add(challenge());
		all.add(elementalStrike());
		all.add(feint());
		all.add(naturesPower());
		all.add(spectralBlades());
		all.add(spiritHawk());
		all.add(elementalBlast());
		all.add(warpBeacon());
		all.add(wildMagic());
		all.add(deathMark());
		all.add(shadowClone());
		all.add(smokeBomb());
		all.add(endure());
		all.add(heroicLeap());
		all.add(shockwave());
		return all;
	}

	private static Scenario ascendedForm(){
		return new Scenario("armor-ability.AscendedForm", "use AscendedForm", HeroClass.CLERIC,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					ClericArmor armor = new ClericArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new AscendedForm());
				},
				d -> {
					d.require(Dungeon.hero.buff(AscendedForm.AscendBuff.class) == null, "AscendBuff existed before AscendedForm");
					float before = charge();
					d.use(ClericArmor.class);
					d.expect(charge() < before, "AscendedForm left charge at " + charge() + " from " + before);
					d.expect(Dungeon.hero.buff(AscendedForm.AscendBuff.class) != null, "AscendedForm gave no AscendBuff");
				}).covers(AscendedForm.class);
	}

	private static Scenario powerOfMany(){
		return new Scenario("armor-ability.PowerOfMany", "use PowerOfMany", HeroClass.CLERIC,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					ClericArmor armor = new ClericArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new PowerOfMany());
				},
				d -> {
					float before = charge();
					int cell = d.world().adjacentFree();
					d.use(ClericArmor.class);
					if (d.env().mode() == EnvMode.TARGETING) d.step(Action.USE, d.direction(cell));
					d.expect(charge() < before, "PowerOfMany left charge at " + charge() + " from " + before);
					boolean hasLightAlly = false;
					for (Mob m : Dungeon.level.mobs) if (m instanceof PowerOfMany.LightAlly) hasLightAlly = true;
					d.expect(hasLightAlly, "PowerOfMany spawned no LightAlly");
				}).covers(PowerOfMany.class, PowerOfMany.LightAlly.class);
	}

	private static Scenario trinity(){
		return new Scenario("armor-ability.Trinity", "use Trinity through its window", HeroClass.CLERIC,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					ClericArmor armor = new ClericArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					Trinity trinity = new Trinity();
					try {
						java.lang.reflect.Field field = Trinity.class.getDeclaredField("bodyForm");
						field.setAccessible(true);
						field.set(trinity, new Blazing());
					} catch (ReflectiveOperationException e) {
						throw new NoFixture("could not imbue Trinity: " + e);
					}
					kit.ability(trinity);
				},
				d -> {
					d.require(Dungeon.hero.buff(BodyForm.BodyFormBuff.class) == null, "BodyForm existed before Trinity");
					float before = charge();
					d.use(ClericArmor.class);
					d.require(d.env().mode() == EnvMode.MENU, "Trinity opened " + d.env().mode());
					d.menu(0);
					d.expect(charge() < before, "Trinity left charge at " + charge() + " from " + before);
					d.expect(Dungeon.hero.buff(BodyForm.BodyFormBuff.class) != null, "Trinity applied no BodyForm");
				}).covers(Trinity.class);
	}

	private static Scenario challenge(){
		return new Scenario("armor-ability.Challenge", "use Challenge on a rat", HeroClass.DUELIST,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength(18);
					DuelistArmor armor = new DuelistArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new Challenge());
					Rat rat = world.hunting(new Rat(), world.adjacentFree());
					rat.HP = rat.HT = 100;
				},
				d -> {
					float before = charge();
					Rat rat = d.world().find(Rat.class);
					d.use(DuelistArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "Challenge opened " + d.env().mode());
					d.step(Action.USE, d.direction(rat.pos));
					d.expect(charge() < before, "Challenge left charge at " + charge() + " from " + before);
					d.expect(rat.buff(Challenge.DuelParticipant.class) != null, "Challenge marked no rat");
				}).covers(Challenge.class);
	}

	private static Scenario elementalStrike(){
		return new Scenario("armor-ability.ElementalStrike", "use ElementalStrike on a rat", HeroClass.DUELIST,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength(18);
					DuelistArmor armor = new DuelistArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new ElementalStrike());
					Longsword sword = new Longsword();
					sword.enchant(new Blazing());
					kit.hero().belongings.weapon = sword;
					Rat rat = world.hunting(new Rat(), world.adjacentFree());
					rat.HP = rat.HT = 100;
				},
				d -> {
					float before = charge();
					Rat rat = d.world().find(Rat.class);
					int ratHp = rat.HP;
					d.use(DuelistArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "ElementalStrike opened " + d.env().mode());
					d.step(Action.USE, d.direction(rat.pos));
					d.expect(charge() < before, "ElementalStrike left charge at " + charge() + " from " + before);
					d.expect(!rat.isAlive() || rat.HP < ratHp, "ElementalStrike left the rat at " + rat.HP);
				}).covers(ElementalStrike.class);
	}

	private static Scenario feint(){
		return new Scenario("armor-ability.Feint", "use Feint", HeroClass.DUELIST,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength(18);
					DuelistArmor armor = new DuelistArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new Feint());
					new com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator();
					world.mob(new Feint.AfterImage(), world.adjacentFree());
				},
				d -> {
					float before = charge();
					int cell = d.world().adjacentFree();
					d.use(DuelistArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "Feint opened " + d.env().mode());
					d.step(Action.USE, d.direction(cell));
					d.expect(charge() < before, "Feint left charge at " + charge() + " from " + before);
				}).covers(Feint.class, Feint.AfterImage.class);
	}

	private static Scenario naturesPower(){
		return new Scenario("armor-ability.NaturesPower", "use NaturesPower", HeroClass.HUNTRESS,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					HuntressArmor armor = new HuntressArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new NaturesPower());
				},
				d -> {
					d.require(Dungeon.hero.buff(NaturesPower.naturesPowerTracker.class) == null, "NaturesPower tracker existed before");
					float before = charge();
					d.use(HuntressArmor.class);
					d.expect(charge() < before, "NaturesPower left charge at " + charge() + " from " + before);
					d.expect(Dungeon.hero.buff(NaturesPower.naturesPowerTracker.class) != null, "NaturesPower gave no tracker");
				}).covers(NaturesPower.class);
	}

	private static Scenario spectralBlades(){
		return new Scenario("armor-ability.SpectralBlades", "use SpectralBlades on a rat", HeroClass.HUNTRESS,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength(18);
					HuntressArmor armor = new HuntressArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new SpectralBlades());
					Rat rat = world.hunting(new Rat(), world.adjacentFree());
					rat.HP = rat.HT = 100;
				},
				d -> {
					float before = charge();
					Rat rat = d.world().find(Rat.class);
					d.use(HuntressArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "SpectralBlades opened " + d.env().mode());
					d.step(Action.USE, d.direction(rat.pos));
					d.expect(charge() < before, "SpectralBlades left charge at " + charge() + " from " + before);
				}).covers(SpectralBlades.class);
	}

	private static Scenario spiritHawk(){
		return new Scenario("armor-ability.SpiritHawk", "use SpiritHawk", HeroClass.HUNTRESS,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					HuntressArmor armor = new HuntressArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new SpiritHawk());
				},
				d -> {
					float before = charge();
					d.use(HuntressArmor.class);
					d.expect(charge() < before, "SpiritHawk left charge at " + charge() + " from " + before);
					d.expect(count(SpiritHawk.HawkAlly.class) > 0, "SpiritHawk spawned no HawkAlly");
				}).covers(SpiritHawk.class, SpiritHawk.HawkAlly.class);
	}

	private static Scenario elementalBlast(){
		return new Scenario("armor-ability.ElementalBlast", "use ElementalBlast", HeroClass.MAGE,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength(18);
					MageArmor armor = new MageArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new ElementalBlast());
					kit.hero().belongings.weapon = new MagesStaff(new WandOfMagicMissile());
				},
				d -> {
					float before = charge();
					d.use(MageArmor.class);
					d.expect(charge() < before, "ElementalBlast left charge at " + charge() + " from " + before);
				}).covers(ElementalBlast.class);
	}

	private static Scenario warpBeacon(){
		return new Scenario("armor-ability.WarpBeacon", "use WarpBeacon", HeroClass.MAGE,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					MageArmor armor = new MageArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new WarpBeacon());
				},
				d -> {
					d.require(Dungeon.hero.buff(WarpBeacon.WarpBeaconTracker.class) == null, "WarpBeaconTracker existed before");
					d.use(MageArmor.class);
					d.expect(Dungeon.hero.buff(WarpBeacon.WarpBeaconTracker.class) != null, "WarpBeacon set no beacon");
				}).covers(WarpBeacon.class);
	}

	private static Scenario wildMagic(){
		return new Scenario("armor-ability.WildMagic", "use WildMagic on a rat", HeroClass.MAGE,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength(18);
					MageArmor armor = new MageArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new WildMagic());
					kit.give(new WandOfMagicMissile());
					Rat rat = world.hunting(new Rat(), world.adjacentFree());
					rat.HP = rat.HT = 100;
				},
				d -> {
					float before = charge();
					Rat rat = d.world().find(Rat.class);
					d.use(MageArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "WildMagic opened " + d.env().mode());
					d.step(Action.USE, d.direction(rat.pos));
					d.expect(charge() < before, "WildMagic left charge at " + charge() + " from " + before);
				}).covers(WildMagic.class);
	}

	private static Scenario deathMark(){
		return new Scenario("armor-ability.DeathMark", "use DeathMark on a rat", HeroClass.ROGUE,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength(18);
					RogueArmor armor = new RogueArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new DeathMark());
					Rat rat = world.hunting(new Rat(), world.adjacentFree());
					rat.HP = rat.HT = 100;
				},
				d -> {
					float before = charge();
					Rat rat = d.world().find(Rat.class);
					d.require(rat.buff(DeathMark.DeathMarkTracker.class) == null, "DeathMark existed before");
					d.use(RogueArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "DeathMark opened " + d.env().mode());
					d.step(Action.USE, d.direction(rat.pos));
					d.expect(charge() < before, "DeathMark left charge at " + charge() + " from " + before);
					d.expect(rat.buff(DeathMark.DeathMarkTracker.class) != null, "DeathMark marked no rat");
				}).covers(DeathMark.class);
	}

	private static Scenario shadowClone(){
		return new Scenario("armor-ability.ShadowClone", "use ShadowClone", HeroClass.ROGUE,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					RogueArmor armor = new RogueArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new ShadowClone());
				},
				d -> {
					float before = charge();
					d.use(RogueArmor.class);
					d.expect(charge() < before, "ShadowClone left charge at " + charge() + " from " + before);
					d.expect(count(ShadowClone.ShadowAlly.class) > 0, "ShadowClone spawned no ShadowAlly");
				}).covers(ShadowClone.class, ShadowClone.ShadowAlly.class);
	}

	private static Scenario smokeBomb(){
		return new Scenario("armor-ability.SmokeBomb", "use SmokeBomb", HeroClass.ROGUE,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					RogueArmor armor = new RogueArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new SmokeBomb());
					kit.talent(Talent.BODY_REPLACEMENT, 1);
				},
				d -> {
					float before = charge();
					int cell = d.world().adjacentFree();
					d.use(RogueArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "SmokeBomb opened " + d.env().mode());
					d.step(Action.USE, d.direction(cell));
					d.expect(charge() < before, "SmokeBomb left charge at " + charge() + " from " + before);
					d.expect(count(SmokeBomb.NinjaLog.class) > 0, "SmokeBomb spawned no NinjaLog");
				}).covers(SmokeBomb.class, SmokeBomb.NinjaLog.class);
	}

	private static Scenario endure(){
		return new Scenario("armor-ability.Endure", "use Endure", HeroClass.WARRIOR,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					WarriorArmor armor = new WarriorArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new Endure());
				},
				d -> {
					d.require(Dungeon.hero.buff(Endure.EndureTracker.class) == null, "EndureTracker existed before Endure");
					float before = charge();
					d.use(WarriorArmor.class);
					d.expect(charge() < before, "Endure left charge at " + charge() + " from " + before);
				}).covers(Endure.class);
	}

	private static Scenario heroicLeap(){
		return new Scenario("armor-ability.HeroicLeap", "use HeroicLeap", HeroClass.WARRIOR,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength(18);
					WarriorArmor armor = new WarriorArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new HeroicLeap());
				},
				d -> {
					float before = charge();
					int cell = d.world().adjacentFree();
					d.use(WarriorArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "HeroicLeap opened " + d.env().mode());
					d.step(Action.USE, d.direction(cell));
					d.expect(charge() < before, "HeroicLeap left charge at " + charge() + " from " + before);
				}).covers(HeroicLeap.class);
	}

	private static Scenario shockwave(){
		return new Scenario("armor-ability.Shockwave", "use Shockwave on a rat", HeroClass.WARRIOR,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength(18);
					WarriorArmor armor = new WarriorArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new Shockwave());
					Rat rat = world.hunting(new Rat(), world.adjacentFree());
					rat.HP = rat.HT = 100;
				},
				d -> {
					float before = charge();
					Rat rat = d.world().find(Rat.class);
					int ratHp = rat.HP;
					d.use(WarriorArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "Shockwave opened " + d.env().mode());
					d.step(Action.USE, d.direction(rat.pos));
					d.expect(charge() < before, "Shockwave left charge at " + charge() + " from " + before);
					d.expect(!rat.isAlive() || rat.HP < ratHp, "Shockwave left the rat at " + rat.HP);
				}).covers(Shockwave.class);
	}
}
