package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.Ratmogrify;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.PowerOfMany;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.huntress.SpiritHawk;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.rogue.ShadowClone;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.rogue.SmokeBomb;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Skeleton;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClericArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.HuntressArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.RogueArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.WarriorArmor;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class AbilitySummonScenarios {

	private AbilitySummonScenarios(){}

	static List<Scenario> all(){
		List<Scenario> all = new ArrayList<>();
		all.add(powerOfMany());
		all.add(spiritHawk());
		all.add(shadowClone());
		all.add(smokeBomb());
		all.add(ratmogrify());
		return all;
	}

	private static void untilDamaged( Driver d, Mob rat, int before, String why ){
		boolean hit = d.until( () -> !rat.isAlive() || rat.HP < before, 8, () -> d.act( Action.WAIT ));
		d.expect( hit, why + " left the rat at " + rat.HP );
	}

	private static Scenario powerOfMany(){
		return new Scenario("armor-ability.PowerOfMany", "use PowerOfMany", HeroClass.CLERIC,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength(18);
					ClericArmor armor = new ClericArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new PowerOfMany());
					Rat rat = world.hunting(new Rat(), world.adjacentFree());
					rat.HP = rat.HT = 100;
				},
				d -> {
					Rat rat = d.world().find(Rat.class);
					int ratHp = rat.HP;
					int cell = d.world().adjacentFree();
					d.use(ClericArmor.class);
					if (d.env().mode() == EnvMode.TARGETING) d.step(Action.USE, d.direction(cell));
					d.expect(AbilityUseScenarios.count(PowerOfMany.LightAlly.class) > 0, "PowerOfMany spawned no LightAlly");
					untilDamaged(d, rat, ratHp, "the LightAlly");
				}).covers(PowerOfMany.class, PowerOfMany.LightAlly.class);
	}

	private static Scenario spiritHawk(){
		return new Scenario("armor-ability.SpiritHawk", "use SpiritHawk", HeroClass.HUNTRESS,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength(18);
					HuntressArmor armor = new HuntressArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new SpiritHawk());
					Rat rat = world.hunting(new Rat(), world.adjacentFree());
					rat.HP = rat.HT = 100;
				},
				d -> {
					Rat rat = d.world().find(Rat.class);
					int ratHp = rat.HP;
					d.use(HuntressArmor.class);
					d.expect(AbilityUseScenarios.count(SpiritHawk.HawkAlly.class) > 0, "SpiritHawk spawned no HawkAlly");
					d.use(HuntressArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "SpiritHawk opened " + d.env().mode() + " when directed");
					d.step(Action.USE, d.direction(rat.pos));
					untilDamaged(d, rat, ratHp, "the HawkAlly");
				}).covers(SpiritHawk.class, SpiritHawk.HawkAlly.class);
	}

	private static Scenario shadowClone(){
		return new Scenario("armor-ability.ShadowClone", "use ShadowClone", HeroClass.ROGUE,
				(world, kit) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength(18);
					RogueArmor armor = new RogueArmor();
					armor.charge = 100;
					kit.hero().belongings.armor = armor;
					kit.ability(new ShadowClone());
					Rat rat = world.hunting(new Rat(), world.adjacentFree());
					rat.HP = rat.HT = 100;
				},
				d -> {
					Rat rat = d.world().find(Rat.class);
					int ratHp = rat.HP;
					d.use(RogueArmor.class);
					d.expect(AbilityUseScenarios.count(ShadowClone.ShadowAlly.class) > 0, "ShadowClone spawned no ShadowAlly");
					untilDamaged(d, rat, ratHp, "the ShadowAlly");
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
					Rat rat = world.hunting(new Rat(), world.adjacentFree());
					rat.HP = rat.HT = 100;
				},
				d -> {
					Rat rat = d.world().find(Rat.class);
					int cell = d.world().adjacentFree();
					d.use(RogueArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "SmokeBomb opened " + d.env().mode());
					d.step(Action.USE, d.direction(cell));
					d.expect(Dungeon.hero.pos == cell, "SmokeBomb left the hero at " + Dungeon.hero.pos);
					d.expect(AbilityUseScenarios.count(SmokeBomb.NinjaLog.class) > 0, "SmokeBomb spawned no NinjaLog");
					d.expect(rat.buff(Blindness.class) != null, "SmokeBomb blinded no adjacent rat");
				}).covers(SmokeBomb.class, SmokeBomb.NinjaLog.class);
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
					kit.talent(Talent.RATLOMACY, 1);
					new com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator();
					Skeleton skeleton = world.hunting(new Skeleton(), world.adjacentFree());
					skeleton.HP = skeleton.HT = 100;
				},
				d -> {
					Skeleton skeleton = d.world().find(Skeleton.class);
					d.use(WarriorArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "Ratmogrify opened " + d.env().mode());
					d.step(Action.USE, d.direction(skeleton.pos));
					d.expect(AbilityUseScenarios.count(Ratmogrify.TransmogRat.class) > 0, "Ratmogrify spawned no TransmogRat");
					Ratmogrify.TransmogRat ally = null;
					for (Mob mob : Dungeon.level.mobs) if (mob instanceof Ratmogrify.TransmogRat) ally = (Ratmogrify.TransmogRat) mob;
					d.use(WarriorArmor.class);
					d.require(d.env().mode() == EnvMode.TARGETING, "Ratmogrify opened " + d.env().mode() + " when directed");
					d.step(Action.USE, d.direction(ally.pos));
					d.expect(ally.alignment == com.shatteredpixel.shatteredpixeldungeon.actors.Char.Alignment.ALLY,
							"the TransmogRat is " + ally.alignment + ", not an ally");
					d.expect(ally.HP == skeleton.HT, "the TransmogRat inherited " + ally.HP + " of the skeleton's " + skeleton.HT);
				}).covers(Ratmogrify.class, Ratmogrify.TransmogRat.class);
	}
}
