package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Healing;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ParalyticGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Amok;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Drowsy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invisibility;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Levitation;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MindVision;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Recharging;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Terror;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.LeatherArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfExperience;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHaste;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfInvisibility;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfLevitation;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfMindVision;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfParalyticGas;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfLullaby;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfMagicMapping;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRage;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRecharging;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTerror;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

final class StateScenarios {

	private StateScenarios(){}

	private static int before;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( consume( "drink.Healing", PotionOfHealing.class, true, () -> before = Dungeon.hero.HP,
				() -> Dungeon.hero.HP > before ).covers( PotionOfHealing.class, Healing.class ));
		all.add( consume( "drink.Strength", PotionOfStrength.class, true, () -> before = Dungeon.hero.STR,
				() -> Dungeon.hero.STR == before + 1 ).covers( PotionOfStrength.class ));
		all.add( consume( "drink.Haste", PotionOfHaste.class, true, null, () -> hero( Haste.class )).covers( PotionOfHaste.class, Haste.class ));
		all.add( consume( "drink.Invisibility", PotionOfInvisibility.class, true, null, () -> hero( Invisibility.class ))
				.covers( PotionOfInvisibility.class, Invisibility.class ));
		all.add( consume( "drink.Levitation", PotionOfLevitation.class, true, null, () -> hero( Levitation.class ))
				.covers( PotionOfLevitation.class, Levitation.class ));
		all.add( consume( "drink.MindVision", PotionOfMindVision.class, true, null, () -> hero( MindVision.class ))
				.covers( PotionOfMindVision.class, MindVision.class ));
		all.add( consume( "drink.Experience", PotionOfExperience.class, true, () -> before = Dungeon.hero.lvl,
				() -> Dungeon.hero.lvl > before ).covers( PotionOfExperience.class ));
		all.add( consume( "drink.unknown-ParalyticGas", PotionOfParalyticGas.class, false, null,
				() -> Dungeon.level.blobs.get( ParalyticGas.class ) != null )
				.covers( PotionOfParalyticGas.class ));
		all.add( consume( "scroll.MagicMapping", ScrollOfMagicMapping.class, true, () -> before = mapped(),
				() -> mapped() > before ).covers( ScrollOfMagicMapping.class ));
		all.add( consume( "scroll.Teleportation", ScrollOfTeleportation.class, true, () -> before = Dungeon.hero.pos,
				() -> Dungeon.hero.pos != before ).covers( ScrollOfTeleportation.class ));
		all.add( consume( "scroll.Terror", ScrollOfTerror.class, true, null, () -> rat( Terror.class ))
				.covers( ScrollOfTerror.class, Terror.class ));
		all.add( consume( "scroll.Lullaby", ScrollOfLullaby.class, true, null, () -> rat( Drowsy.class ))
				.covers( ScrollOfLullaby.class, Drowsy.class ));
		all.add( consume( "scroll.Recharging", ScrollOfRecharging.class, true, null, () -> hero( Recharging.class ))
				.covers( ScrollOfRecharging.class, Recharging.class ));
		all.add( consume( "scroll.Rage", ScrollOfRage.class, true, null, () -> rat( Amok.class )).covers( ScrollOfRage.class, Amok.class ));
		all.add( eat().covers( Food.class, Hunger.class ));
		all.add( starve() );
		all.add( equip().covers( LeatherArmor.class ));
		all.add( equipCursed().covers( Sword.class ));
		all.add( talent() );
		all.add( levelUp().covers( Rat.class ));
		all.add( death() );
		return all;
	}

	private static boolean hero( Class< ? extends Buff > type ){
		return Dungeon.hero.buff( type ) != null;
	}

	private static boolean rat( Class< ? extends Buff > type ){
		for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob mob : Dungeon.level.mobs){
			if (mob instanceof Rat && mob.buff( type ) != null) return true;
		}
		return false;
	}

	private static int mapped(){
		int n = 0;
		for (boolean m : Dungeon.level.mapped) if (m) n++;
		return n;
	}

	private static Scenario consume( String id, Class< ? extends Item > type, boolean known, Runnable measure,
			BooleanSupplier effect ){
		return new Scenario( id, "use " + type.getSimpleName(), HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.health( 20, 1000 );
					Item item = Reflection.newInstance( type );
					if (known) kit.give( item );
					else kit.giveUnknown( item );
					world.mob( new Rat(), world.cellAtRange( 3, true ));
				},
				d -> {
					if (measure != null) measure.run();
					d.use( type );
					if (d.env().mode() == EnvMode.MENU) d.menu( 0 );
					d.expect( effect.getAsBoolean(), type.getSimpleName() + " had no effect" );
				} );
	}

	private static Scenario eat(){
		return new Scenario( "eat", "eat when hungry", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.hero().buff( Hunger.class ).affectHunger( -Hunger.HUNGRY - 10 );
				},
				d -> {
					Hunger hunger = Dungeon.hero.buff( Hunger.class );
					d.require( (hunger.hunger() >= Hunger.HUNGRY), "the hero is not hungry" );
					d.use( Food.class );
					d.waitFor( () -> !(hunger.hunger() >= Hunger.HUNGRY), 3 );
					d.expect( !(hunger.hunger() >= Hunger.HUNGRY), "eating left the hero hungry at " + hunger.hunger() );
				} );
	}

	private static Scenario starve(){
		return new Scenario( "starve", "starve while waiting", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.health( 20, 20 );
					kit.hero().buff( Hunger.class ).affectHunger( -Hunger.STARVING + 5 );
				},
				d -> {
					Hunger hunger = Dungeon.hero.buff( Hunger.class );
					int hp = Dungeon.hero.HP;
					d.waitFor( () -> hunger.isStarving() && Dungeon.hero.HP < hp, 12 );
					d.expect( hunger.isStarving() && Dungeon.hero.HP < hp, "twelve waits near starving left hunger "
							+ hunger.hunger() + " and HP " + Dungeon.hero.HP );
				} );
	}

	private static Scenario equip(){
		return new Scenario( "equip", "wear new armour", HeroClass.MAGE,
				( world, kit ) -> {
					world.clearMobs();
					kit.give( new LeatherArmor() );
				},
				d -> {
					d.use( LeatherArmor.class );
					d.expect( Dungeon.hero.belongings.armor instanceof LeatherArmor, "the hero wears "
							+ Dungeon.hero.belongings.armor );
				} );
	}

	private static Scenario equipCursed(){
		return new Scenario( "equip-cursed", "a cursed weapon sticks", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					Sword sword = new Sword();
					sword.cursed = true;
					kit.giveUnknown( sword );
				},
				d -> {
					d.use( Sword.class );
					d.expect( Dungeon.hero.belongings.weapon instanceof Sword && Dungeon.hero.belongings.weapon.cursed,
							"the cursed sword is not wielded" );
				} );
	}

	private static Scenario talent(){
		return new Scenario( "talent", "spend a talent point", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.level( 2 );
				},
				d -> {
					d.require( Dungeon.hero.talentPointsAvailable( 1 ) > 0, "no talent point to spend" );
					d.act( Action.TALENT );
					d.menu( 0 );
					d.expect( Dungeon.hero.talentPointsAvailable( 1 ) == 0, "the talent point was not spent" );
				} );
	}

	private static Scenario levelUp(){
		return new Scenario( "levelup", "gain a level from a kill", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.hero().exp = kit.hero().maxExp() - 1;
					world.hunting( new Rat(), world.adjacentFree() );
				},
				d -> {
					Rat rat = d.world().find( Rat.class );
					int lvl = Dungeon.hero.lvl;
					d.until( () -> !rat.isAlive(), 20, () -> d.move( rat.pos ));
					d.expect( Dungeon.hero.lvl == lvl + 1, "killing the rat left the hero at level " + Dungeon.hero.lvl );
				} );
	}

	private static Scenario death(){
		return new Scenario( "death", "die to a rat", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.health( 1, 20 );
					world.hunting( new Rat(), world.adjacentFree() );
				},
				d -> {
					d.waitFor( () -> !Dungeon.hero.isAlive(), 30 );
					d.expect( !d.env().running() && d.env().endReason().name().equals( "DEATH" ), "the episode ended as "
							+ d.env().endReason() + ", running " + d.env().running() );
				} );
	}
}
