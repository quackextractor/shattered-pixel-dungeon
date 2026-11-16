package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ArtifactRecharge;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invisibility;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Recharging;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ScrollEmpower;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.RecallInscription;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Pasty;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfMagicMapping;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WornShortsword;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class TalentTier2 {

	private TalentTier2(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		float[] eaten = { -1f };
		all.add( of( Talent.IRON_STOMACH, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.sturdy();
			kit.give( new Pasty() );
			eaten[ 0 ] = -1f;
		}, d -> {
			float start = Actor.now();
			TalentFamily.eat( d );
			eaten[ 0 ] = Actor.now() - start;
		}, () -> {
			float normal = Food.TIME_TO_EAT;
			return () -> normal > 0f && eaten[ 0 ] > 0f && eaten[ 0 ] < normal;
		}, "eating did not take the short meal time" ));
		all.add( of( Talent.ENERGIZING_MEAL, HeroClass.MAGE, ( world, kit ) -> {}, TalentFamily::eat,
				() -> () -> TalentFamily.has( Recharging.class ), "the meal started no wand recharge" ));
		all.add( of( Talent.MYSTICAL_MEAL, HeroClass.ROGUE, ( world, kit ) -> {}, TalentFamily::eat,
				() -> () -> TalentFamily.has( ArtifactRecharge.class ), "the meal started no artifact recharge" ));
		all.add( of( Talent.INVIGORATING_MEAL, HeroClass.HUNTRESS, ( world, kit ) -> {}, TalentFamily::eat,
				() -> () -> TalentFamily.has( Haste.class ), "the meal granted no haste" ));
		all.add( of( Talent.FOCUSED_MEAL, HeroClass.DUELIST, ( world, kit ) -> {
			kit.hero().belongings.weapon = (WornShortsword) new WornShortsword().identify();
		}, TalentFamily::eat, () -> {
			float before = charge();
			return () -> charge() > before;
		}, "the meal added no weapon charge" ));
		all.add( of( Talent.ENLIGHTENING_MEAL, HeroClass.CLERIC, TalentTier2::emptyTome, TalentFamily::eat, () -> {
			float before = tomeCharge();
			return () -> tomeCharge() > before;
		}, "the meal added no tome charge" ));
		all.add( of( Talent.LIQUID_WILLPOWER, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.sturdy();
			kit.give( new PotionOfHealing() );
		}, d -> TalentFamily.use( d, PotionOfHealing.class ), TalentFamily::barrierGrew, "the potion granted no shielding" ));
		all.add( of( Talent.LIQUID_NATURE, HeroClass.HUNTRESS, ( world, kit ) -> {
			kit.sturdy();
			for (int offset : PathFinder.NEIGHBOURS9){
				int cell = world.hero() + offset;
				if (cell >= 0 && cell < world.level().length() && offset != 0) world.terrain( cell, Terrain.EMPTY );
			}
			kit.give( new PotionOfHealing() );
		}, d -> TalentFamily.use( d, PotionOfHealing.class ), () -> {
			int before = grassAround();
			return () -> grassAround() > before;
		}, "the potion sprouted no high grass" ));
		all.add( of( Talent.LIQUID_AGILITY, HeroClass.DUELIST, ( world, kit ) -> {
			kit.sturdy();
			kit.give( new PotionOfHealing() );
		}, d -> TalentFamily.use( d, PotionOfHealing.class ), () -> () -> {
			Talent.LiquidAgilACCTracker acc = TalentFamily.hero().buff( Talent.LiquidAgilACCTracker.class );
			return acc != null && acc.uses == 1;
		}, "the potion left no accuracy charge" ));
		all.add( of( Talent.INSCRIBED_POWER, HeroClass.MAGE, ( world, kit ) -> {
			kit.give( new ScrollOfMagicMapping() );
			kit.give( new WandOfMagicMissile() );
		}, TalentTier2::read, TalentTier2::empowered, "the scroll left no scroll empower" ).covers( ScrollEmpower.class ));
		all.add( of( Talent.INSCRIBED_STEALTH, HeroClass.ROGUE, ( world, kit ) -> kit.give( new ScrollOfMagicMapping() ),
				TalentTier2::read, () -> () -> TalentFamily.has( Invisibility.class ), "the scroll granted no stealth" ));
		all.add( of( Talent.RECALL_INSCRIPTION, HeroClass.CLERIC, ( world, kit ) -> kit.give( new ScrollOfMagicMapping() ),
				TalentTier2::read, () -> () -> {
					RecallInscription.UsedItemTracker tracker = TalentFamily.hero().buff( RecallInscription.UsedItemTracker.class );
					return tracker != null && tracker.item == ScrollOfMagicMapping.class;
				}, "the scroll was not tracked for recall" ));
		return all;
	}

	private static Scenario of( Talent talent, HeroClass hero, Scenario.Setup setup, Scenario.Script trigger,
			Supplier< BooleanSupplier > effect, String failure ){
		return TalentFamily.of( new TalentFamily.Probe( talent, hero, setup, trigger, effect, failure ));
	}

	private static void read( Driver d ){
		TalentFamily.use( d, ScrollOfMagicMapping.class );
	}

	private static float charge(){
		MeleeWeapon.Charger charger = TalentFamily.hero().buff( MeleeWeapon.Charger.class );
		return charger == null ? 0 : charger.partialCharge;
	}

	private static float tomeCharge(){
		HolyTome tome = TalentFamily.hero().belongings.getItem( HolyTome.class );
		return tome == null ? 0 : TalentFamily.saved( tome, "partialcharge" );
	}

	private static void emptyTome( World world, HeroKit kit ){
		HolyTome tome = TalentFamily.hero().belongings.getItem( HolyTome.class );
		if (tome == null) tome = kit.give( new HolyTome() );
		TalentFamily.prime( tome, "charge", 0 );
	}

	private static int grassAround(){
		int count = 0;
		for (int offset : PathFinder.NEIGHBOURS9){
			int cell = TalentFamily.hero().pos + offset;
			if (cell >= 0 && cell < Dungeon.level.length() && Dungeon.level.map[ cell ] == Terrain.HIGH_GRASS) count++;
		}
		return count;
	}

	private static BooleanSupplier empowered(){
		ScrollEmpower empower = TalentFamily.hero().buff( ScrollEmpower.class );
		float before = empower == null ? 0 : TalentFamily.saved( empower, "left" );
		return () -> TalentFamily.has( ScrollEmpower.class )
				&& TalentFamily.saved( TalentFamily.hero().buff( ScrollEmpower.class ), "left" ) > before;
	}
}
