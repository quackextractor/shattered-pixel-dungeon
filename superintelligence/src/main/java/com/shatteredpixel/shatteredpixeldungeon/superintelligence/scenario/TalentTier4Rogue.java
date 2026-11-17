package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invisibility;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.rogue.DeathMark;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.rogue.ShadowClone;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.rogue.SmokeBomb;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Greatsword;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Rogue {

	private TalentTier4Rogue(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( TalentTier4.of( Talent.HASTY_RETREAT, HeroClass.ROGUE, new SmokeBomb(), ( world, kit ) -> {},
				d -> smoke( d ), () -> {
			boolean base = Dungeon.hero.buff( Haste.class ) != null;
			return () -> !base && Dungeon.hero.buff( Haste.class ) != null;
		}, "the smoke bomb did not hasten the hero" ));
		all.add( TalentTier4.of( Talent.BODY_REPLACEMENT, HeroClass.ROGUE, new SmokeBomb(), ( world, kit ) -> {},
				d -> smoke( d ), () -> {
			boolean base = log() != null;
			return () -> !base && log() != null;
		}, "the smoke bomb left no ninja log" ));
		all.add( TalentTier4.of( Talent.SHADOW_STEP, HeroClass.ROGUE, new SmokeBomb(), ( world, kit ) -> {
			Buff.affect( kit.hero(), Invisibility.class, 100f );
		}, d -> smoke( d ), () -> {
			float base = Dungeon.hero.armorAbility.chargeUse( Dungeon.hero );
			return () -> Dungeon.hero.armorAbility.chargeUse( Dungeon.hero ) < base;
		}, "sneaking spent the full charge" ));
		all.add( TalentTier4.of( Talent.FEAR_THE_REAPER, HeroClass.ROGUE, new DeathMark(), ( world, kit ) -> {
			markedRat( world );
		}, d -> {
			TalentTier4.useAbility( d );
			Rat rat = new World().find( Rat.class );
			for (int i = 0; i < 10 && rat.isAlive() && d.env().running(); i++) d.move( rat.pos );
		}, () -> {
			Rat rat = new World().find( Rat.class );
			return () -> rat.buff( Cripple.class ) != null || Dungeon.hero.pointsInTalent( Talent.FEAR_THE_REAPER ) == 1;
		}, "the reaping crippled nothing" ));
		all.add( TalentTier4.of( Talent.DEATHLY_DURABILITY, HeroClass.ROGUE, new DeathMark(), ( world, kit ) -> {
			markedRat( world );
		}, d -> {
			TalentTier4.useAbility( d );
			Rat rat = new World().find( Rat.class );
			for (int i = 0; i < 10 && rat.isAlive() && d.env().running(); i++) d.move( rat.pos );
		}, () -> () -> Dungeon.hero.shielding() > 0 || Dungeon.hero.pointsInTalent( Talent.DEATHLY_DURABILITY ) == 1, "the marked death granted no shield" ));
		all.add( TalentTier4.of( Talent.DOUBLE_MARK, HeroClass.ROGUE, new DeathMark(), ( world, kit ) -> {
			markedRat( world );
		}, d -> TalentTier4.useAbility( d ), () -> () -> Dungeon.hero.buff( DeathMark.DoubleMarkTracker.class ) != null,
				"the double mark was not granted" ));
		all.add( TalentTier4.of( Talent.SHADOW_BLADE, HeroClass.ROGUE, new ShadowClone(), ( world, kit ) -> {
			Greatsword sword = new Greatsword();
			sword.upgrade( 10 );
			kit.hero().belongings.weapon = sword;
		}, d -> TalentTier4.useAbility( d ), () -> () -> {
			ShadowClone.ShadowAlly ally = ally();
			return ally != null && TalentTier4.mean( ally::damageRoll ) > 15.5f;
		}, "the clone did not copy the blade" ));
		all.add( TalentTier4.of( Talent.CLONED_ARMOR, HeroClass.ROGUE, new ShadowClone(), ( world, kit ) -> {
			ClassArmor armor = TalentTier4.armor();
			if (armor != null) armor.upgrade( 10 );
		}, d -> TalentTier4.useAbility( d ), () -> () -> {
			ShadowClone.ShadowAlly ally = ally();
			return ally != null && TalentTier4.mean( ally::drRoll ) > 0f;
		}, "the clone did not copy the armour" ));
		all.add( TalentTier4.of( Talent.PERFECT_COPY, HeroClass.ROGUE, new ShadowClone(), ( world, kit ) -> {},
				d -> TalentTier4.useAbility( d ), () -> () -> {
			ShadowClone.ShadowAlly ally = ally();
			return ally != null && ally.HT > 80;
		}, "the perfect copy gained no health" ));
		return all;
	}

	private static void smoke( Driver d ){
		TalentFamily.unmask( d, Action.USE );
		d.use( ClassArmor.class );
		if (d.env().mode() == EnvMode.TARGETING) d.step( Action.USE, d.direction( new World().adjacentFree() ));
	}

	private static void markedRat( World world ){
		Rat rat = world.mob( new Rat(), world.adjacentFree() );
		rat.HP = 200;
		rat.HT = 1000;
		Buff.affect( rat, Paralysis.class, 50f );
		Greatsword sword = new Greatsword();
		sword.upgrade( 10 );
		Dungeon.hero.belongings.weapon = sword;
	}

	private static SmokeBomb.NinjaLog log(){
		for (com.shatteredpixel.shatteredpixeldungeon.actors.Char ch : Actor.chars())
			if (ch instanceof SmokeBomb.NinjaLog) return (SmokeBomb.NinjaLog) ch;
		return null;
	}

	private static ShadowClone.ShadowAlly ally(){
		for (com.shatteredpixel.shatteredpixeldungeon.actors.Char ch : Actor.chars())
			if (ch instanceof ShadowClone.ShadowAlly) return (ShadowClone.ShadowAlly) ch;
		return null;
	}
}
