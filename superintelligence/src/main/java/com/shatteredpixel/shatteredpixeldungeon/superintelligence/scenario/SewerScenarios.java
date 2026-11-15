package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.StenchGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.LockedFloor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Ooze;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Regeneration;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Albino;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.CausticSlime;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Crab;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.CrystalMimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.EbonyMimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.FetidRat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Gnoll;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GoldenMimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Goo;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GreatCrab;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.HermitCrab;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.PhantomPiranha;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Piranha;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Slime;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Snake;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.WornKey;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfInvisibility;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.GooBlob;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.levels.SewerBossLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.SewerLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

final class SewerScenarios {

	private SewerScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( FoeFamily.dot( "sewer.Albino", Albino.class, Bleeding.class, "the Albino never made the hero bleed",
				"the bleeding never hurt the hero out of the Albino's reach" ));
		all.add( FoeFamily.fight( "sewer.Gnoll", Gnoll.class, 1, 12, null, "the Gnoll never hurt the hero" ));
		all.add( FoeFamily.fight( "sewer.Snake", Snake.class, 1, 12, null, "the Snake never hurt the hero" ));
		all.add( FoeFamily.fight( "sewer.Crab", Crab.class, 1, 12, null, "the Crab never hurt the hero" ));
		all.add( FoeFamily.fight( "sewer.Slime", Slime.class, 1, 12, null, "the Slime never hurt the hero" ));
		all.add( FoeFamily.fight( "sewer.HermitCrab", HermitCrab.class, 1, 12, null, "the Hermit Crab never hurt the hero" ));
		all.add( FoeFamily.dot( "sewer.CausticSlime", CausticSlime.class, Ooze.class, "the Caustic Slime never oozed the hero",
				"the ooze never hurt the hero out of the Caustic Slime's reach" ));
		all.add( FoeFamily.armedFight( "sewer.FetidRat", FetidRat.class, 1, 12, SewerScenarios::stenchParalyses,
				"the Fetid Rat's stench beside the hero never cost him a turn", ( world, kit ) -> {} ).covers( StenchGas.class ));
		all.add( greatCrab().covers( GreatCrab.class ));
		all.add( FoeFamily.fight( "sewer.Piranha", Piranha.class, 1, 12, null, "the Piranha never bit the hero in the water",
				( world, kit ) -> wade( world )));
		all.add( phantomPiranha().covers( PhantomPiranha.class ));
		all.add( mimic( "sewer.GoldenMimic", GoldenMimic.class ));
		all.add( mimic( "sewer.EbonyMimic", EbonyMimic.class ));
		all.add( FoeFamily.armedPassive( "sewer.CrystalMimic", CrystalMimic.class, d -> d.move( d.world().find( CrystalMimic.class ).pos ),
				SewerScenarios::crystalSteals, "stepping into the crystal chest neither revealed the mimic nor let it steal" ));
		all.add( gooKill().covers( GooBlob.class, WornKey.class, LockedFloor.class, SewerBossLevel.class ));
		all.add( wornKey().covers( WornKey.class ));
		all.add( descend().covers( SewerLevel.class ));
		all.add( regen().covers( Regeneration.class ));
		return all;
	}

	private static BooleanSupplier stenchParalyses(){
		float[] since = { Actor.now() };
		boolean[] inGas = { stenchBesideHero() };
		return () -> {
			boolean lostTurns = inGas[ 0 ] && Actor.now() - since[ 0 ] > 1.5f;
			since[ 0 ] = Actor.now();
			inGas[ 0 ] = stenchBesideHero();
			return lostTurns;
		};
	}

	private static boolean stenchBesideHero(){
		Blob gas = Dungeon.level.blobs.get( StenchGas.class );
		if (gas == null || gas.volume <= 0) return false;
		if (gas.cur[ Dungeon.hero.pos ] > 0) return true;
		for (int offset : PathFinder.NEIGHBOURS8) if (gas.cur[ Dungeon.hero.pos + offset ] > 0) return true;
		return false;
	}

	private static void wade( World world ){
		int cell = world.adjacentFree();
		ScrollOfTeleportation.appear( Dungeon.hero, cell );
		world.terrain( cell, Terrain.WATER );
		for (int offset : PathFinder.NEIGHBOURS8) if (world.free( cell + offset )) world.terrain( cell + offset, Terrain.WATER );
		Dungeon.observe();
	}

	private static Scenario greatCrab(){
		return new Scenario( "sewer.GreatCrab", "a great crab parries what it sees and not what it cannot", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new PotionOfInvisibility() );
					world.hunting( new GreatCrab(), world.adjacentFree() );
				},
				d -> {
					GreatCrab crab = d.world().find( GreatCrab.class );
					d.act( Action.WAIT );
					d.require( crab.enemySeen(), "the great crab never saw the hero beside it" );
					for (int i = 0; i < 3; i++) d.move( crab.pos );
					d.expect( crab.HP == crab.HT, "three attacks from the front hurt the great crab to " + crab.HP + " of " + crab.HT );
					d.use( PotionOfInvisibility.class );
					int hp = crab.HP;
					d.until( () -> crab.HP < hp || !crab.isAlive(), 4, () -> {
						if (Dungeon.level.adjacent( Dungeon.hero.pos, crab.pos )) d.move( crab.pos );
						else d.act( Action.WAIT );
					});
					d.expect( crab.HP < hp || !crab.isAlive(), "attacks the great crab could not see left it at " + crab.HP );
				} );
	}

	private static Scenario phantomPiranha(){
		return new Scenario( "sewer.PhantomPiranha", "a phantom piranha hit from afar teleports beside the hero and bites", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new WandOfMagicMissile() ).curCharges = 2;
					wade( world );
					int pool = world.cellAtRange( 3, true );
					world.terrain( pool, Terrain.WATER );
					world.hunting( new PhantomPiranha(), pool );
				},
				d -> {
					PhantomPiranha piranha = d.world().find( PhantomPiranha.class );
					int pool = piranha.pos;
					int mobHp = piranha.HP;
					int heroHp = Dungeon.hero.HP;
					d.use( WandOfMagicMissile.class );
					d.aimNearest();
					d.expect( piranha.HP < mobHp, "the zap left the phantom piranha at " + piranha.HP );
					d.expect( piranha.pos != pool, "the phantom piranha stayed in its pool when hit from afar" );
					d.waitFor( () -> Dungeon.hero.HP < heroHp, 6 );
					d.expect( Dungeon.hero.HP < heroHp, "the phantom piranha never bit the hero in the water" );
				} );
	}

	private static Scenario mimic( String id, Class< ? extends Mimic > type ){
		return FoeFamily.armedPassive( id, type, d -> d.move( d.world().find( type ).pos ), () -> {
			Mimic mimic = Dungeon.level.mobs.stream().filter( type::isInstance ).map( type::cast ).findFirst().orElseThrow();
			int hp = Dungeon.hero.HP;
			return () -> mimic.alignment == Char.Alignment.ENEMY && mimic.state == mimic.HUNTING && Dungeon.hero.HP < hp;
		}, "stepping into the disguised " + type.getSimpleName() + " did not reveal it and draw its attack" );
	}

	private static BooleanSupplier crystalSteals(){
		CrystalMimic mimic = Dungeon.level.mobs.stream().filter( CrystalMimic.class::isInstance ).map( CrystalMimic.class::cast )
				.findFirst().orElseThrow();
		int held = mimic.items.size();
		return () -> mimic.alignment == Char.Alignment.ENEMY && mimic.state == mimic.FLEEING && mimic.items.size() > held;
	}

	private static Scenario gooKill(){
		return new Scenario( "sewer.goo-kill", "kill Goo at 5 HP after its pump and take its drops", HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy();
					Goo goo = null;
					for (Mob mob : world.level().mobs) if (mob instanceof Goo) goo = (Goo) mob;
					if (goo == null) throw new NoFixture( "floor 5 has no Goo" );
					ScrollOfTeleportation.appear( goo, world.adjacentFree() );
					goo.HP = 5;
					goo.aggro( Dungeon.hero );
					goo.state = goo.HUNTING;
				},
				d -> {
					Goo goo = d.world().find( Goo.class );
					d.waitFor( () -> goo.pumpedUp() > 0, 12 );
					d.require( goo.pumpedUp() > 0, "Goo never pumped up in 12 turns beside the hero" );
					d.expect( Dungeon.hero.buff( LockedFloor.class ) != null, "the floor was not locked while Goo fought" );
					d.until( () -> !goo.isAlive(), 20, () -> {
						if (Dungeon.level.adjacent( Dungeon.hero.pos, goo.pos )) d.move( goo.pos );
						else d.act( Action.WAIT );
					});
					d.require( !goo.isAlive(), "20 steps beside Goo did not kill it; it has " + goo.HP + " HP" );
					d.expect( heaped( WornKey.class ), "Goo dropped no worn key" );
					d.expect( heaped( GooBlob.class ), "Goo dropped no goo blob" );
					d.expect( Dungeon.hero.buff( LockedFloor.class ) == null, "the floor stayed locked after Goo died" );
				} ).depth( 5 );
	}

	private static boolean heaped( Class< ? extends Item > type ){
		for (Heap heap : Dungeon.level.heaps.valueList()) for (Item item : heap.items) if (type.isInstance( item )) return true;
		return false;
	}

	private static Scenario wornKey(){
		return new Scenario( "sewer.worn-key", "the worn key opens the locked exit", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					int exit = lockedExit();
					if (exit < 0) throw new NoFixture( "floor 5 has no locked exit" );
					int stand = -1;
					for (int offset : PathFinder.NEIGHBOURS8) if (stand < 0 && world.free( exit + offset )) stand = exit + offset;
					if (stand < 0) throw new NoFixture( "no free cell beside the locked exit" );
					ScrollOfTeleportation.appear( Dungeon.hero, stand );
					Dungeon.observe();
					world.drop( new WornKey( Dungeon.depth ), world.adjacentFree() );
				},
				d -> {
					int exit = lockedExit();
					int key = -1;
					for (Heap heap : Dungeon.level.heaps.valueList()) if (heap.peek() instanceof WornKey) key = heap.pos;
					d.require( key >= 0, "no worn key lies beside the hero" );
					d.move( key );
					d.expect( Dungeon.level.heaps.get( key ) == null, "the hero did not pick up the worn key" );
					d.walkTo( exit, 6 );
					d.move( exit );
					d.waitFor( () -> Dungeon.level.map[ exit ] == Terrain.UNLOCKED_EXIT, 3 );
					d.expect( Dungeon.level.map[ exit ] == Terrain.UNLOCKED_EXIT, "the worn key did not open the locked exit" );
				} ).depth( 5 );
	}

	private static int lockedExit(){
		for (int cell = 0; cell < Dungeon.level.length(); cell++) if (Dungeon.level.map[ cell ] == Terrain.LOCKED_EXIT) return cell;
		return -1;
	}

	private static Scenario descend(){
		return new Scenario( "sewer.descend", "take the stairs down from a sewer floor", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Arrays.fill( world.level().mapped, true );
				},
				d -> {
					d.require( Dungeon.level instanceof SewerLevel, "depth 3 is a " + Dungeon.level.getClass().getSimpleName() );
					d.fixture( d.env().actionMask()[ Action.TRAVEL_STAIRS.index ] > 0.5f, "no known path to the exit" );
					d.until( () -> Dungeon.depth == 4, 100, () -> d.act( Action.TRAVEL_STAIRS ));
					d.expect( Dungeon.depth == 4, "100 TRAVEL_STAIRS steps from depth 3 left the hero on depth " + Dungeon.depth );
				} ).depth( 3 );
	}

	private static Scenario regen(){
		return new Scenario( "sewer.regen", "a resting hero regenerates", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.health( kit.hero().HT / 2, kit.hero().HT );
				},
				d -> {
					int hp = Dungeon.hero.HP;
					for (int i = 0; i < 30 && d.env().running(); i++) d.act( Action.WAIT );
					d.expect( Dungeon.hero.HP > hp, "30 turns of rest left the hero at " + Dungeon.hero.HP + " of " + hp );
				} );
	}
}
