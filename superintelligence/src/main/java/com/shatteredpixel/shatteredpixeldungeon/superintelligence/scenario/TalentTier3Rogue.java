package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.EnhancedRings;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invisibility;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Momentum;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Preparation;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.CloakOfShadows;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfAccuracy;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingKnife;

import java.util.ArrayList;
import java.util.List;

final class TalentTier3Rogue {

	private TalentTier3Rogue(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( TalentTier3.of( Talent.ENHANCED_RINGS, HeroClass.ROGUE, ( world, kit ) -> {
			kit.sturdy();
			kit.give( new RingOfAccuracy() );
		}, d -> TalentTier3.use( d, CloakOfShadows.class ), () -> {
			RingOfAccuracy ring = TalentTier3.hero().belongings.getItem( RingOfAccuracy.class );
			int base = ring.buffedLvl();
			return () -> TalentTier3.has( EnhancedRings.class ) && ring.buffedLvl() > base;
		}, "the cloak use left the rings no stronger" ).covers( EnhancedRings.class ));
		all.add( TalentTier3.of( Talent.LIGHT_CLOAK, HeroClass.ROGUE, ( world, kit ) -> {
			kit.sturdy();
			TalentTier3.hero().belongings.artifact = null;
			kit.give( new CloakOfShadows() );
		}, d -> d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT ), () -> {
			CloakOfShadows cloak = TalentTier3.hero().belongings.getItem( CloakOfShadows.class );
			boolean base = cloak.actions( TalentTier3.hero() ).contains( CloakOfShadows.AC_STEALTH );
			return () -> !base && cloak.actions( TalentTier3.hero() ).contains( CloakOfShadows.AC_STEALTH );
		}, "the cloak offered no stealth while unworn" ));
		all.add( TalentTier3.of( Talent.ENHANCED_LETHALITY, HeroClass.ROGUE, ( world, kit ) -> {
			kit.sturdy();
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HT = 1000;
			rat.HP = 600;
		}, d -> d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT ), () -> {
			boolean base = Preparation.AttackLevel.LVL_4.canKO( TalentTier3.rat() );
			return () -> !base && Preparation.AttackLevel.LVL_4.canKO( TalentTier3.rat() );
		}, "the stronger lunge left the knockout threshold low" ));
		all.add( TalentTier3.of( Talent.ASSASSINS_REACH, HeroClass.ROGUE, ( world, kit ) -> kit.sturdy(),
				d -> d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT ), () -> {
			int base = Preparation.AttackLevel.LVL_2.blinkDistance();
			return () -> Preparation.AttackLevel.LVL_2.blinkDistance() > base;
		}, "the blink reach did not grow" ));
		all.add( TalentTier3.of( Talent.BOUNTY_HUNTER, HeroClass.ROGUE, ( world, kit ) -> {
			kit.sturdy();
			Buff.affect( TalentTier3.hero(), Preparation.class );
			Buff.affect( TalentTier3.hero(), Invisibility.class, 200f );
			Buff.affect( TalentTier3.hero(), Talent.BountyHunterTracker.class, 200f );
			com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Snake snake =
					world.mob( new com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Snake(), world.adjacentFree() );
			snake.HP = snake.HT = 1000;
		}, d -> d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT ), () -> {
			float base = new World().find( com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Snake.class ).lootChance();
			return () -> new World().find( com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Snake.class )
					.lootChance() > base;
		}, "the bounty raised no loot chance" ));
		all.add( TalentTier3.of( Talent.EVASIVE_ARMOR, HeroClass.ROGUE, ( world, kit ) -> {
			kit.sturdy();
			Momentum momentum = Buff.affect( TalentTier3.hero(), Momentum.class );
			TalentFamily.prime( momentum, "freerun_turns", 6 );
		}, d -> d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT ), () -> {
			Momentum momentum = TalentTier3.hero().buff( Momentum.class );
			int base = momentum.evasionBonus( 10, 5 );
			return () -> momentum.evasionBonus( 10, 5 ) > base;
		}, "freerunning added no armour evasion" ));
		all.add( TalentTier3.of( Talent.PROJECTILE_MOMENTUM, HeroClass.ROGUE, ( world, kit ) -> {
			kit.sturdy();
			Momentum momentum = Buff.affect( TalentTier3.hero(), Momentum.class );
			TalentFamily.prime( momentum, "freerun_turns", 8 );
		}, d -> d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT ), () -> {
			ThrowingKnife knife = new ThrowingKnife();
			float base = TalentTier3.mean( () -> knife.damageRoll( TalentTier3.hero() ));
			return () -> TalentTier3.mean( () -> knife.damageRoll( TalentTier3.hero() )) > base;
		}, "freerunning changed no missile damage" ));
		all.add( TalentTier3.of( Talent.SPEEDY_STEALTH, HeroClass.ROGUE, ( world, kit ) -> {
			kit.sturdy();
			Momentum momentum = Buff.affect( TalentTier3.hero(), Momentum.class );
			TalentFamily.prime( momentum, "stacks", 0 );
			Buff.affect( TalentTier3.hero(), Invisibility.class, 20f );
		}, d -> d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT ), () -> {
			Momentum momentum = TalentTier3.hero().buff( Momentum.class );
			int base = (int) TalentFamily.saved( momentum, "stacks" );
			return () -> TalentFamily.saved( momentum, "stacks" ) > base;
		}, "standing invisible built no momentum" ));
		return all;
	}
}
