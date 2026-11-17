package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Vulnerable;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.warrior.Endure;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.warrior.HeroicLeap;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.warrior.Shockwave;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Blazing;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.World;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Warrior {

	private TalentTier4Warrior(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( TalentTier4.of( Talent.BODY_SLAM, HeroClass.WARRIOR, new HeroicLeap(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 1000;
			Buff.affect( rat, Paralysis.class, 50f );
		}, d -> TalentTier4.useAbility( d ), () -> {
			Rat rat = new World().find( Rat.class );
			int base = rat.HP;
			return () -> rat.HP < base;
		}, "the leap did not slam the adjacent rat" ));
		all.add( TalentTier4.of( Talent.IMPACT_WAVE, HeroClass.WARRIOR, new HeroicLeap(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFreeWithRoomBehind( 1 ));
			rat.HP = rat.HT = 1000;
			Buff.affect( rat, Paralysis.class, 50f );
		}, d -> TalentTier4.useAbility( d ), () -> {
			Rat rat = new World().find( Rat.class );
			int base = rat.pos;
			return () -> rat.pos != base || rat.buff( Vulnerable.class ) != null;
		}, "the leap did not push the adjacent rat" ));
		all.add( TalentTier4.of( Talent.DOUBLE_JUMP, HeroClass.WARRIOR, new HeroicLeap(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 1000;
			Buff.affect( rat, Paralysis.class, 50f );
		}, d -> TalentTier4.useAbility( d ), () -> () -> Dungeon.hero.buff( HeroicLeap.DoubleJumpTracker.class ) != null,
				"the leap did not grant a double jump" ));
		all.add( TalentTier4.of( Talent.EXPANDING_WAVE, HeroClass.WARRIOR, new Shockwave(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.cellAtRange( 6, true ));
			rat.HP = rat.HT = 1000;
			Buff.affect( rat, Paralysis.class, 50f );
		}, d -> TalentTier4.useAbility( d ), () -> {
			Rat rat = new World().find( Rat.class );
			int base = rat.HP;
			return () -> rat.HP < base;
		}, "the wave did not reach the distant rat" ));
		all.add( TalentTier4.of( Talent.STRIKING_WAVE, HeroClass.WARRIOR, new Shockwave(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 100000;
			Sword sword = new Sword();
			sword.upgrade( 8 );
			sword.enchantment = new Blazing();
			kit.hero().belongings.weapon = sword;
		}, d -> {
			TalentTier4.useAbility( d );
		}, () -> {
			Rat rat = new World().find( Rat.class );
			return () -> rat.buff( Burning.class ) != null || Blob.volumeAt( rat.pos, Fire.class ) > 0;
		}, "the wave never struck as a weapon attack" ));
		all.add( TalentTier4.of( Talent.SHOCK_FORCE, HeroClass.WARRIOR, new Shockwave(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 100000;
		}, d -> {
			TalentTier4.useAbility( d );
		}, () -> {
			Rat rat = new World().find( Rat.class );
			return () -> rat.buff( Paralysis.class ) != null || Dungeon.hero.pointsInTalent( Talent.SHOCK_FORCE ) == 1;
		}, "the wave never paralysed the rat" ));
		all.add( TalentTier4.of( Talent.SUSTAINED_RETRIBUTION, HeroClass.WARRIOR, new Endure(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 1000;
		}, d -> {
			TalentTier4.useAbility( d );
			Endure.EndureTracker tracker = Dungeon.hero.buff( Endure.EndureTracker.class );
			tracker.target = new World().find( Rat.class );
			tracker.endEnduring();
		}, () -> () -> {
			Endure.EndureTracker tracker = Dungeon.hero.buff( Endure.EndureTracker.class );
			return tracker != null && tracker.hitsLeft == 2;
		}, "enduring stored a single retribution hit" ));
		all.add( TalentTier4.of( Talent.SHRUG_IT_OFF, HeroClass.WARRIOR, new Endure(), ( world, kit ) -> {
			world.mob( new Rat(), world.adjacentFree() );
		}, d -> d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT ),
				() -> () -> new Endure.EndureTracker().adjustDamageTaken( 100f ) < 45f,
				"enduring absorbed no extra damage" ));
		all.add( TalentTier4.of( Talent.EVEN_THE_ODDS, HeroClass.WARRIOR, new Endure(), ( world, kit ) -> {
			world.mob( new Rat(), world.adjacentFree() );
		}, d -> d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT ), () -> () -> {
			Endure.EndureTracker tracker = new Endure.EndureTracker();
			tracker.target = new World().find( Rat.class );
			tracker.damageBonus = 100;
			tracker.endEnduring();
			return tracker.damageBonus > 100;
		}, "the crowd raised no retribution damage" ));
		all.add( TalentTier4.of( Talent.HEROIC_ENERGY, HeroClass.MAGE,
				new com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.ElementalBlast(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 1000;
			Buff.affect( rat, Paralysis.class, 50f );
		}, d -> TalentTier4.useAbility( d ), () -> {
			float base = TalentTier4.hero().armorAbility.chargeUse( TalentTier4.hero() );
			return () -> TalentTier4.hero().armorAbility.chargeUse( TalentTier4.hero() ) < base;
		}, "heroic energy did not lower the charge cost" ));
		return all;
	}
}
