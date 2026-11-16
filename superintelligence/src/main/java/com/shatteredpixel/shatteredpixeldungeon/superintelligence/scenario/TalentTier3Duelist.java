package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MonkEnergy;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Rapier;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WornShortsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingKnife;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;

import java.util.ArrayList;
import java.util.List;

final class TalentTier3Duelist {

	private TalentTier3Duelist(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( TalentTier3.of( Talent.PRECISE_ASSAULT, HeroClass.DUELIST, ( world, kit ) -> {
			kit.sturdy();
			rat( world );
		}, TalentTier3Duelist::lunge, () -> () -> TalentTier3.has( Talent.PreciseAssaultTracker.class ),
				"the weapon ability readied no precise assault" ));
		all.add( TalentTier3.of( Talent.DEADLY_FOLLOWUP, HeroClass.DUELIST, ( world, kit ) -> {
			kit.sturdy();
			ThrowingKnife knife = new ThrowingKnife();
			knife.quantity( 2 );
			kit.give( knife );
			rat( world );
		}, d -> {
			TalentFamily.unmask( d, Action.THROW );
			d.throwItem( ThrowingKnife.class );
			d.aimNearest();
		}, () -> {
			Rat rat = TalentTier3.rat();
			return () -> {
				Talent.DeadlyFollowupTracker mark = TalentTier3.hero().buff( Talent.DeadlyFollowupTracker.class );
				return mark != null && mark.object == rat.id();
			};
		}, "the throw marked no deadly followup" ));
		all.add( TalentTier3.of( Talent.VARIED_CHARGE, HeroClass.DUELIST, ( world, kit ) -> {
			kit.sturdy();
			rat( world );
		}, TalentTier3Duelist::lunge, () -> () -> TalentTier3.has( Talent.VariedChargeTracker.class ),
				"the weapon ability recorded no varied charge" ));
		all.add( TalentTier3.of( Talent.TWIN_UPGRADES, HeroClass.DUELIST, ( world, kit ) -> {
			kit.sturdy();
			WornShortsword worn = new WornShortsword();
			com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword sword =
					new com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword();
			sword.upgrade( 2 );
			TalentTier3.hero().belongings.weapon = worn;
			TalentTier3.hero().belongings.secondWep = sword;
		}, d -> d.act( Action.WAIT ), () -> {
			WornShortsword worn = (WornShortsword) TalentTier3.hero().belongings.weapon;
			int base = worn.buffedLvl();
			int other = TalentTier3.hero().belongings.secondWep().buffedLvl();
			return () -> worn.buffedLvl() > base && worn.buffedLvl() >= other;
		}, "the weaker weapon stayed below the offhand" ));
		all.add( TalentTier3.of( Talent.COMBINED_LETHALITY, HeroClass.DUELIST, ( world, kit ) -> {
			kit.sturdy();
			rat( world );
		}, TalentTier3Duelist::lunge,
				() -> () -> TalentTier3.has( Talent.CombinedLethalityAbilityTracker.class ),
				"the weapon ability readied no execution" ));
		all.add( TalentTier3.of( Talent.UNENCUMBERED_SPIRIT, HeroClass.DUELIST, ( world, kit ) -> {
			kit.sturdy();
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = 1;
			Buff.affect( rat, com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis.class, 50f );
		}, d -> d.move( TalentTier3.rat().pos ), () -> {
			MonkEnergy energy = monk();
			float base = energy == null ? 0 : energy.energy;
			return () -> monk() != null && monk().energy > base + 1.4f;
		}, "the kill filled less than one and a half energy" ));
		all.add( TalentTier3.of( Talent.MONASTIC_VIGOR, HeroClass.DUELIST, ( world, kit ) -> {
			kit.sturdy();
			MonkEnergy energy = Buff.affect( TalentTier3.hero(), MonkEnergy.class );
			energy.energy = energy.energyCap();
		}, d -> d.act( Action.WAIT ), () -> {
			MonkEnergy energy = monk();
			boolean base = energy.abilitiesEmpowered( TalentTier3.hero() );
			return () -> !base && energy.abilitiesEmpowered( TalentTier3.hero() );
		}, "full energy still empowered no abilities" ));
		all.add( TalentTier3.of( Talent.COMBINED_ENERGY, HeroClass.DUELIST, ( world, kit ) -> {
			kit.sturdy();
			rat( world );
		}, TalentTier3Duelist::lunge,
				() -> () -> TalentTier3.has( Talent.CombinedEnergyAbilityTracker.class ),
				"the weapon ability readied no combined energy" ));
		return all;
	}

	private static void rat( World world ){
		Rat rat = world.mob( new Rat(), world.cellAtRange( 2, true ) );
		rat.HP = rat.HT = 1000;
	}

	static void lunge( Driver d ){
		TalentTier3.ability( d, Rapier.class );
		d.waitFor( () -> TalentTier3.hero().belongings.abilityWeapon == null, 6 );
	}

	private static MonkEnergy monk(){
		return TalentTier3.hero().buff( MonkEnergy.class );
	}
}

