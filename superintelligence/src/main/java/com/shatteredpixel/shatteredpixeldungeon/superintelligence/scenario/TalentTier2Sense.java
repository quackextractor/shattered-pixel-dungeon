package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bless;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.BlessSpell;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.DivineSense;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Sunray;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.TalismanOfForesight;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretLibraryRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.AlarmTrap;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class TalentTier2Sense {

	private TalentTier2Sense(){}

	private static boolean foresightHint = false;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( of( Talent.ARCANE_VISION, HeroClass.MAGE, ( world, kit ) -> {
			kit.sturdy();
			Rat rat = world.mob( new Rat(), world.cellAtRange( 3, true ));
			rat.HP = rat.HT = 1000;
			Buff.affect( rat, Paralysis.class, 50f );
			WandOfMagicMissile wand = kit.give( new WandOfMagicMissile() );
			wand.curCharges = wand.maxCharges;
		}, d -> TalentFamily.aim( d, WandOfMagicMissile.class ), () -> () -> {
			Rat rat = TalentFamily.rat();
			for (TalismanOfForesight.CharAwareness awareness : TalentFamily.hero().buffs( TalismanOfForesight.CharAwareness.class )){
				if (awareness.charID == rat.id()) return true;
			}
			return false;
		}, "the zap gave no awareness of the rat" ));
		all.add( of( Talent.WIDE_SEARCH, HeroClass.ROGUE, ( world, kit ) -> {
			kit.sturdy();
			Dungeon.level.traps.clear();
			int placed = 0;
			for (int cell = 0; cell < world.level().length(); cell++){
				if (cell == world.hero() || world.level().distance( world.hero(), cell ) > 3) continue;
				if (!world.level().insideMap( cell ) || world.level().getTransition( cell ) != null) continue;
				world.terrain( cell, Terrain.EMPTY );
			}
			for (int cell = 0; cell < world.level().length(); cell++){
				if (world.level().distance( world.hero(), cell ) != 3) continue;
				if (!world.level().insideMap( cell ) || !world.level().passable[ cell ]) continue;
				if (cell == world.hero() || com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar( cell ) != null) continue;
				world.trap( new AlarmTrap(), cell, Terrain.SECRET_TRAP );
				placed++;
			}
			if (placed == 0) throw new NoFixture( "no hidden cell three tiles out from " + world.hero() );
		}, d -> d.act( Action.SEARCH ), () -> {
			int before = secretTrapsAround( 3 );
			return () -> secretTrapsAround( 3 ) < before;
		}, "the search found nothing three tiles out" ));
		all.add( of( Talent.SILENT_STEPS, HeroClass.ROGUE, ( world, kit ) -> {
			kit.sturdy();
			Rat near = world.mob( new Rat(), world.cellAtRange( 2, true ));
			near.state = near.SLEEPING;
			Rat far = world.mob( new Rat(), world.cellAtRange( 4, true ));
			far.state = far.SLEEPING;
		}, d -> d.waitFor( () -> nearestRat().state != nearestRat().SLEEPING, 12 ), () -> {
			Rat near = nearestRat();
			Rat far = farthestRat();
			return () -> far.state == far.SLEEPING && near.state != near.SLEEPING;
		}, "the far rat woke, or the near one never did" ));
		all.add( foresight());
		all.add( of( Talent.REJUVENATING_STEPS, HeroClass.HUNTRESS, ( world, kit ) -> {
			kit.sturdy();
			world.terrain( world.adjacentFreeStraight(), Terrain.GRASS );
		}, d -> d.move( d.world().adjacentFreeStraight() ), () -> () -> {
			int terrain = Dungeon.level.map[ TalentFamily.hero().pos ];
			return (terrain == Terrain.HIGH_GRASS || terrain == Terrain.FURROWED_GRASS)
					&& TalentFamily.has( Talent.RejuvenatingStepsCooldown.class );
		}, "the grass did not spring up under the hero" ));
		all.add( of( Talent.HEIGHTENED_SENSES, HeroClass.HUNTRESS, ( world, kit ) -> {
			kit.sturdy();
			int near = orthogonal( world, 1 );
			int beyond = near + (near - world.hero());
			if (!world.free( beyond )) throw new NoFixture( "no free cell behind the wall" );
			world.terrain( near, Terrain.WALL );
			Rat rat = world.mob( new Rat(), beyond );
			rat.HP = rat.HT = 1000;
			Buff.affect( rat, Paralysis.class, 50f );
		}, d -> d.act( Action.WAIT ), () -> () -> TalentFamily.hero().mindVisionEnemies.contains( TalentFamily.rat() ),
				"the rat behind the wall stayed unseen" ));
		all.add( of( Talent.SUNRAY, HeroClass.CLERIC, ( world, kit ) -> {
			kit.sturdy();
			Rat rat = world.mob( new Rat(), world.cellAtRange( 2, true ));
			rat.HP = rat.HT = 1000;
		}, d -> {
			TalentFamily.cast( d, Sunray.INSTANCE );
			d.aimNearest();
		}, () -> () -> TalentFamily.rat().buff( Blindness.class ) != null, "the sunray blinded nothing" ));
		all.add( of( Talent.DIVINE_SENSE, HeroClass.CLERIC, ( world, kit ) -> kit.sturdy(),
				d -> TalentFamily.cast( d, DivineSense.INSTANCE ), () -> () -> TalentFamily.has( DivineSense.DivineSenseTracker.class ),
				"the spell granted no divine sense" ));
		all.add( of( Talent.BLESS, HeroClass.CLERIC, ( world, kit ) -> kit.sturdy().health( 20, 100 ), d -> {
			TalentFamily.cast( d, BlessSpell.INSTANCE );
			d.require( d.env().mode() == EnvMode.TARGETING, "bless did not ask for a target: " + d.env().mode() );
			d.step( Action.USE, ActionMapper.SELF_TARGET_ALIAS );
		}, () -> {
			int before = TalentFamily.barrier();
			return () -> TalentFamily.has( Bless.class ) && TalentFamily.barrier() > before;
		}, "the blessing gave no bless and no shielding" ));
		return all;
	}

	private static Scenario foresight(){
		Scenario scenario = of( Talent.ROGUES_FORESIGHT, HeroClass.ROGUE, ( world, kit ) -> {
			world.clearMobs();
			kit.sturdy();
			foresightHint = false;
			RoomForcing.secret( SecretLibraryRoom.class ).run();
			int exit = world.level().exit();
			int beside = -1;
			for (int offset : PathFinder.NEIGHBOURS8){
				if (world.free( exit + offset )){ beside = exit + offset; break; }
			}
			if (beside < 0) throw new NoFixture( "no cell beside the exit" );
			ScrollOfTeleportation.appear( kit.hero(), beside );
		}, d -> {
			java.util.Arrays.fill( Dungeon.level.mapped, true );
			int depth = Dungeon.depth;
			int exit = Dungeon.level.exit();
			com.watabou.utils.Signal.Listener< String > listener = text -> {
				if (text != null && text.contains( hintText() )) foresightHint = true;
				return true;
			};
			com.shatteredpixel.shatteredpixeldungeon.utils.GLog.update.add( listener );
			try {
				d.walkTo( exit, 6 );
				d.move( exit );
			} finally {
				com.shatteredpixel.shatteredpixeldungeon.utils.GLog.update.remove( listener );
			}
			d.expect( Dungeon.depth != depth, "the hero stayed on depth " + depth );
		}, () -> () -> foresightHint, "the descent gave no secret hint" );
		scenario.depth( 2 );
		scenario.maxSteps = 400;
		return scenario;
	}

	private static String hintText(){
		return com.shatteredpixel.shatteredpixeldungeon.messages.Messages.get(
				com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene.class, "secret_hint" );
	}

	private static int secretTrapsAround( int distance ){
		int count = 0;
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (Dungeon.level.distance( TalentFamily.hero().pos, cell ) != distance) continue;
			if (Dungeon.level.map[ cell ] == Terrain.SECRET_TRAP) count++;
		}
		return count;
	}

	private static int orthogonal( World world, int distance ){
		int width = world.level().width();
		for (int offset : new int[]{ -width, width, -1, 1 }){
			int cell = world.hero() + offset * distance;
			if (cell < 0 || cell >= world.level().length()) continue;
			if (world.level().distance( world.hero(), cell ) != distance) continue;
			if (world.free( cell )) return cell;
		}
		throw new NoFixture( "no free cell " + distance + " tiles away in a straight line" );
	}

	private static Rat nearestRat(){
		return ratAt( 0 );
	}

	private static Rat farthestRat(){
		return ratAt( 1 );
	}

	private static Rat ratAt( int rank ){
		ArrayList< Rat > rats = new ArrayList<>();
		for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob mob : Dungeon.level.mobs) if (mob instanceof Rat) rats.add( (Rat) mob );
		rats.sort( ( a, b ) -> Integer.compare( Dungeon.level.distance( TalentFamily.hero().pos, a.pos ),
				Dungeon.level.distance( TalentFamily.hero().pos, b.pos )));
		if (rats.size() <= rank) throw new Driver.Stop( "only " + rats.size() + " rats on the level" );
		return rats.get( rank );
	}

	private static Scenario of( Talent talent, HeroClass hero, Scenario.Setup setup, Scenario.Script trigger,
			Supplier< BooleanSupplier > effect, String failure ){
		return TalentFamily.of( new TalentFamily.Probe( talent, hero, setup, trigger, effect, failure ));
	}
}
