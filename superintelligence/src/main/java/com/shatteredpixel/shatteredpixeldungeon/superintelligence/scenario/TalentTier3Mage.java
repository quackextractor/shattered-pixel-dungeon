package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ArtifactRecharge;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.SoulMark;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Wraith;
import com.shatteredpixel.shatteredpixeldungeon.items.BrokenSeal;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class TalentTier3Mage {

	private TalentTier3Mage(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( TalentTier3.of( Talent.DESPERATE_POWER, HeroClass.MAGE, ( world, kit ) -> {
			kit.sturdy();
			Wand wand = staff().wand();
			wand.curCharges = 1;
		}, d -> d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT ), () -> {
			int base = staff().wand().buffedLvl();
			return () -> staff().wand().buffedLvl() > base;
		}, "the last wand charge added no power" ));
		all.add( TalentTier3.of( Talent.ALLY_WARP, HeroClass.MAGE, ( world, kit ) -> {
			Wraith ally = world.mob( new Wraith(), world.cellAtRange( 2, true ));
			ally.alignment = Char.Alignment.ALLY;
			ally.HP = ally.HT = 1000;
		}, d -> d.act( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.WAIT ), () -> {
			Wraith ally = new World().find( Wraith.class );
			boolean base = ally.canInteract( TalentTier3.hero() );
			return () -> !base && ally.canInteract( TalentTier3.hero() );
		}, "an ally two tiles away stayed out of reach" ));
		all.add( TalentTier3.of( Talent.EMPOWERED_STRIKE, HeroClass.MAGE, ( world, kit ) -> {
			kit.sturdy();
			TalentTier3.rat( world, world.cellAtRange( 3, true ));
		}, d -> TalentTier3.aim( d, MagesStaff.class ), () -> () -> TalentTier3.has( Talent.EmpoweredStrikeTracker.class ),
				"the staff zap readied no empowered strike" ));
		all.add( TalentTier3.of( Talent.MYSTICAL_CHARGE, HeroClass.MAGE, ( world, kit ) -> {
			kit.sturdy();
			com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome tome =
					new com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome();
			tome.identify();
			TalentTier3.hero().belongings.misc = tome;
			tome.activate( TalentTier3.hero() );
			TalentFamily.prime( tome, "charge", 0 );
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 1000;
		}, d -> d.move( TalentTier3.rat().pos ), () -> {
			com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome tome =
					(com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome)
							TalentTier3.hero().belongings.misc;
			float base = TalentFamily.saved( tome, "partialcharge" );
			return () -> TalentFamily.saved( tome, "partialcharge" ) > base;
		}, "the staff hit charged no artifact" ));
		all.add( TalentTier3.of( Talent.EXCESS_CHARGE, HeroClass.MAGE, ( world, kit ) -> {
			kit.sturdy();
			staff().wand().upgrade( 3 );
			TalentTier3.rat( world, world.cellAtRange( 3, true ));
		}, d -> TalentTier3.aim( d, MagesStaff.class ), () -> {
			int base = TalentTier3.barrier();
			return () -> TalentTier3.barrier() > base;
		}, "a zap at full charges gave no shield" ));
		all.add( TalentTier3.of( Talent.SOUL_EATER, HeroClass.MAGE, ( world, kit ) -> {
			kit.sturdy().strength( 16 );
			TalentTier3.hero().belongings.weapon =
					(com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword)
							new com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword().upgrade( 8 );
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 1000;
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff.affect( rat, SoulMark.class );
			Hunger hunger = com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff.affect(
					TalentTier3.hero(), Hunger.class );
			hunger.affectHunger( -60f );
		}, d -> d.move( TalentTier3.rat().pos ), () -> {
			float base = hunger();
			return () -> hunger() < base - 2f;
		}, "hitting the marked rat restored no satiety" ));
		all.add( TalentTier3.of( Talent.SOUL_SIPHON, HeroClass.MAGE, ( world, kit ) -> {
			kit.health( 100, 1000 );
			Rat rat = world.mob( new Rat(), world.cellAtRange( 2, true ) );
			rat.HP = rat.HT = 1000;
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff.affect( rat, SoulMark.class );
			Wraith ally = world.mob( new Wraith(), beside( world, rat.pos ));
			ally.alignment = Char.Alignment.ALLY;
			ally.HP = ally.HT = 1000;
		}, d -> d.waitFor( () -> TalentTier3.hero().HP > 100, 12 ), () -> {
			int base = TalentTier3.hero().HP;
			return () -> TalentTier3.hero().HP > base;
		}, "the ally's hit on the marked rat healed nothing" ));
		all.add( TalentTier3.of( Talent.NECROMANCERS_MINIONS, HeroClass.MAGE, ( world, kit ) -> {
			kit.sturdy();
			for (int cell = 0; cell < world.level().length() && marked() < 20; cell++){
				if (!world.free( cell )) continue;
				int distance = world.level().distance( world.hero(), cell );
				if (distance < 1 || distance > 4) continue;
				Rat rat = world.mob( new Rat(), cell );
				rat.HP = 1;
				com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff.affect( rat, SoulMark.class, 200f );
				com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff.affect( rat,
						com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis.class, 200f );
			}
			if (marked() == 0) throw new NoFixture( "no cell to put a marked rat on" );
		}, d -> {
			for (int i = 0; i < 24 && d.env().running() && wraiths() == 0; i++){
				List< Rat > alive = TalentTier3.rats();
				if (alive.isEmpty()) break;
				Rat next = alive.get( 0 );
				d.walkTo( next.pos, 8 );
				if (next.isAlive() && com.shatteredpixel.shatteredpixeldungeon.Dungeon.level.adjacent(
						TalentTier3.hero().pos, next.pos )) d.move( next.pos );
			}
		}, () -> () -> wraiths() > 0, "no marked corpse raised a wraith" )
				.seeds( "SCN-B", "SCN-C", "SCN-D", "SCN-E", "SCN-F", "SCN-A" ));
		return all;
	}

	private static MagesStaff staff(){
		return (MagesStaff) TalentTier3.hero().belongings.weapon;
	}

	private static int beside( World world, int cell ){
		for (int offset : PathFinder.NEIGHBOURS8) if (world.free( cell + offset )) return cell + offset;
		throw new NoFixture( "no free cell beside " + cell );
	}

	private static float hunger(){
		Hunger hunger = TalentTier3.hero().buff( Hunger.class );
		return hunger == null ? 0 : hunger.hunger();
	}

	private static int wraiths(){
		int count = 0;
		for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob mob
				: com.shatteredpixel.shatteredpixeldungeon.Dungeon.level.mobs){
			if (mob instanceof Wraith) count++;
		}
		return count;
	}

	private static int marked(){
		int count = 0;
		for (Rat rat : TalentTier3.rats()) if (rat.buff( SoulMark.class ) != null) count++;
		return count;
	}
}
