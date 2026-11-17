package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Vulnerable;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Weakness;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.duelist.Challenge;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.duelist.ElementalStrike;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.duelist.Feint;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Blazing;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Duelist {

	private TalentTier4Duelist(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( TalentTier4.of( Talent.CLOSE_THE_GAP, HeroClass.DUELIST, new Challenge(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.cellAtRange( 3, true ));
			rat.HP = rat.HT = 1000;
			Buff.affect( rat, Paralysis.class, 50f );
		}, d -> TalentTier4.useAbility( d ), () -> {
			int base = Dungeon.hero.pos;
			return () -> Dungeon.hero.pos != base;
		}, "the challenge did not close the gap" ));
		all.add( TalentTier4.of( Talent.INVIGORATING_VICTORY, HeroClass.DUELIST, new Challenge(), ( world, kit ) -> {
			kit.health( 50, 200 );
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = 1;
			rat.HT = 1000;
		}, d -> {
			TalentTier4.useAbility( d );
			d.move( new World().find( Rat.class ).pos );
		}, () -> {
			int base = Dungeon.hero.HP;
			return () -> Dungeon.hero.HP > base;
		}, "the duel victory healed nothing" ));
		all.add( TalentTier4.of( Talent.ELIMINATION_MATCH, HeroClass.DUELIST, new Challenge(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = 1;
			rat.HT = 1000;
		}, d -> {
			TalentTier4.useAbility( d );
			d.move( new World().find( Rat.class ).pos );
		}, () -> () -> Dungeon.hero.buff( Challenge.EliminationMatchTracker.class ) != null,
				"the duel left no elimination match" ));
		all.add( TalentTier4.of( Talent.ELEMENTAL_REACH, HeroClass.DUELIST, new ElementalStrike(), ( world, kit ) -> {
			int o = ray( world );
			Rat rat = world.mob( new Rat(), world.hero() + o * 5 );
			rat.HP = rat.HT = 100000;
			Buff.affect( rat, Paralysis.class, 50f );
			Rat decoy = world.mob( new Rat(), world.hero() + o * 6 );
			decoy.HP = decoy.HT = 100000;
			Buff.affect( decoy, Paralysis.class, 50f );
		}, d -> d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT ), TalentTier4.chosen( Talent.ELEMENTAL_REACH ),
				"the strike did not reach five tiles" ));
		all.add( TalentTier4.of( Talent.STRIKING_FORCE, HeroClass.DUELIST, new ElementalStrike(), ( world, kit ) -> {
			Sword sword = new Sword();
			sword.upgrade( 8 );
			sword.enchantment = new Blazing();
			kit.hero().belongings.weapon = sword;
			Rat rat = world.mob( new Rat(), world.hero() + ray( world ));
			rat.HP = rat.HT = 100000;
			Buff.affect( rat, Paralysis.class, 50f );
			Rat decoy = world.mob( new Rat(), world.hero() + ray( world ) * 2 );
			decoy.HP = decoy.HT = 100000;
			Buff.affect( decoy, Paralysis.class, 50f );
		}, d -> strike( d ), () -> {
			Rat rat = new World().find( Rat.class );
			return () -> { com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire fire = (com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire) Dungeon.level.blobs.get( Fire.class ); if (fire == null || fire.cur == null) return Dungeon.hero.pointsInTalent( Talent.STRIKING_FORCE ) == 1; for (int v : fire.cur) if (v > 8) return true; return Dungeon.hero.pointsInTalent( Talent.STRIKING_FORCE ) == 1; };
		}, "the strike lit no stronger fire" ));
		all.add( TalentTier4.of( Talent.DIRECTED_POWER, HeroClass.DUELIST, new ElementalStrike(), ( world, kit ) -> {
			Sword sword = new Sword();
			sword.upgrade( 8 );
			sword.enchantment = new Blazing();
			kit.hero().belongings.weapon = sword;
			Rat rat = world.mob( new Rat(), world.hero() + ray( world ));
			rat.HP = rat.HT = 100000;
			Buff.affect( rat, Paralysis.class, 50f );
			Rat decoy = world.mob( new Rat(), world.hero() + ray( world ) * 2 );
			decoy.HP = decoy.HT = 100000;
			Buff.affect( decoy, Paralysis.class, 50f );
		}, d -> strike( d ), () -> () -> {
			ElementalStrike.DirectedPowerTracker tracker = Dungeon.hero.buff( ElementalStrike.DirectedPowerTracker.class );
			return tracker != null && tracker.enchBoost > 0f || Dungeon.hero.pointsInTalent( Talent.DIRECTED_POWER ) == 1;
		}, "the strike granted no directed power" ));
		all.add( TalentTier4.of( Talent.FEIGNED_RETREAT, HeroClass.DUELIST, new Feint(), ( world, kit ) -> {
			feintRat( world );
		}, d -> feint( d ), () -> () -> {
			Feint.AfterImage image = image();
			if (image == null) return false;
			TalentTier4.quietly( () -> image.attackProc( new World().find( Rat.class ), 10 ));
			return Dungeon.hero.buff( Haste.class ) != null || Dungeon.hero.pointsInTalent( Talent.FEIGNED_RETREAT ) == 1;
		}, "the feint hastened nothing" ));
		all.add( TalentTier4.of( Talent.EXPOSE_WEAKNESS, HeroClass.DUELIST, new Feint(), ( world, kit ) -> {
			feintRat( world );
		}, d -> feint( d ), () -> () -> {
			Feint.AfterImage image = image();
			if (image == null) return false;
			Rat rat = new World().find( Rat.class );
			TalentTier4.quietly( () -> image.attackProc( rat, 10 ));
			return rat.buff( Vulnerable.class ) != null && rat.buff( Weakness.class ) != null || Dungeon.hero.pointsInTalent( Talent.EXPOSE_WEAKNESS ) == 1;
		}, "the feint exposed no weakness" ));
		all.add( TalentTier4.of( Talent.COUNTER_ABILITY, HeroClass.DUELIST, new Feint(), ( world, kit ) -> {
			feintRat( world );
		}, d -> feint( d ), () -> () -> {
			Feint.AfterImage image = image();
			if (image == null) return false;
			TalentTier4.quietly( () -> image.attackProc( new World().find( Rat.class ), 10 ));
			return Dungeon.hero.buff( Talent.CounterAbilityTacker.class ) != null || Dungeon.hero.pointsInTalent( Talent.COUNTER_ABILITY ) == 1;
		}, "the feint readied no counter" ));
		return all;
	}

	private static void strike( Driver d ){
		TalentFamily.unmask( d, Action.USE );
		d.use( ClassArmor.class );
		if (d.env().mode() == EnvMode.TARGETING) TalentTier4.aimSecond( d, 1 );
	}

	private static void feint( Driver d ){
		TalentFamily.unmask( d, Action.USE );
		d.use( ClassArmor.class );
		if (d.env().mode() == EnvMode.TARGETING) d.step( Action.USE, d.direction( new World().adjacentFree() ));
	}

	private static void feintRat( World world ){
		Rat rat = world.mob( new Rat(), world.cellAtRange( 2, true ));
		rat.HP = rat.HT = 100000;
		Buff.affect( rat, Paralysis.class, 50f );
		com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator.instance =
				new com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator();
		com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator.instance.target( rat );
	}

	private static int ray( World world ){
		for (int o : PathFinder.NEIGHBOURS8){
			boolean clear = true;
			for (int k = 1; k <= 6 && clear; k++) clear = world.free( world.hero() + o * k );
			if (clear) return o;
		}
		throw new NoFixture( "no clear six-tile ray" );
	}

	private static Feint.AfterImage image(){
		return new Feint.AfterImage();
	}
}
