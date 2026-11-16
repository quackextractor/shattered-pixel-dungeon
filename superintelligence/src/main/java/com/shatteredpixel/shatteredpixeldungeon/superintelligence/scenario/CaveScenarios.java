package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Regrowth;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.ArmoredBrute;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.CrystalGuardian;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.CrystalSpire;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.CrystalWisp;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DM201;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DM300;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.FungalCore;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.FungalSentry;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.FungalSpinner;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GnollExile;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GnollGeomancer;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GnollGuard;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GnollSapper;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Pylon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Blacksmith;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.MetalShard;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.Pickaxe;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.levels.CavesBossLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.CavesLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CaveScenarios {

	private CaveScenarios(){}

	static final int CAVES = 12;

	static final int BOSS = 15;

	static final int BOSS_STEPS = 60;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( raged().covers( ArmoredBrute.class ));
		all.add( metalShard().covers( DM201.class, MetalShard.class ));
		all.add( FoeFamily.fight( "caves.GnollGuard", GnollGuard.class, 1, 14, null,
				"the Gnoll Guard never hurt the hero", CaveScenarios::unarmed ).depth( CAVES ));
		all.add( FoeFamily.fight( "caves.GnollSapper", GnollSapper.class, 1, 14, null,
				"the Gnoll Sapper never hurt the hero", CaveScenarios::unarmed ).depth( CAVES ));
		all.add( FoeFamily.fight( "caves.GnollExile", GnollExile.class, 1, 14, null,
				"the Gnoll Exile never hurt the hero", CaveScenarios::unarmed ).depth( CAVES ));
		all.add( FoeFamily.fight( "caves.FungalSentry", FungalSentry.class, 1, 14,
				() -> Dungeon.hero.buff( Poison.class ) != null, "the Fungal Sentry never poisoned the hero",
				CaveScenarios::unarmed ).depth( CAVES ));
		all.add( spinner().covers( FungalSpinner.class ));
		all.add( FoeFamily.fight( "caves.CrystalGuardian", CrystalGuardian.class, 1, 16, null,
				"the Crystal Guardian never hurt the hero" ).depth( CAVES ));
		all.add( FoeFamily.fight( "caves.CrystalWisp", CrystalWisp.class, 1, 14, null,
				"the Crystal Wisp never hurt the hero", CaveScenarios::unarmed ).depth( CAVES ));
		all.add( geomancer().covers( GnollGeomancer.class ));
		all.add( pylon().covers( Pylon.class ));
		all.add( fungalCore().covers( FungalCore.class ));
		all.add( crystalSpire().covers( CrystalSpire.class ));
		all.add( dm300().covers( DM300.class, CavesBossLevel.class ));
		all.add( descend().covers( CavesLevel.class ));
		all.addAll( CaveMineScenarios.all() );
		return all;
	}

	private static Scenario raged(){
		return FoeFamily.armedFight( "caves.ArmoredBrute", ArmoredBrute.class, 1, 16,
				() -> () -> {
					for (Mob mob : Dungeon.level.mobs){
						if (mob instanceof ArmoredBrute && mob.buff( ArmoredBrute.ArmoredRage.class ) != null) return true;
					}
					return false;
				},
				"the Armored Brute never enraged", ( world, kit ) -> kit.hero().belongings.weapon.level( 10 )).depth( CAVES );
	}

	private static Scenario metalShard(){
		return FoeFamily.fight( "caves.DM201", DM201.class, 1, 30,
				() -> PrisonScenarios.heapOf( MetalShard.class ) != null, "DM-201 died without dropping a metal shard",
				( world, kit ) -> kit.hero().belongings.weapon.level( 20 )).depth( CAVES );
	}

	static void unarmed( World world, HeroKit kit ){
		kit.hero().belongings.weapon.level( 2 );
	}

	private static Scenario spinner(){
		return new Scenario( "caves.FungalSpinner", "a Fungal Spinner webs regrowth over the ground the hero leaves", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					unarmed( world, kit );
					world.hunting( new FungalSpinner(), world.cellAtRange( 3, true ));
				},
				d -> {
					FungalSpinner spinner = d.world().find( FungalSpinner.class );
					d.fixture( !anyRegrowth(), "regrowth lay on the floor before the spinner webbed" );
					int hp = spinner.HP;
					d.until( () -> !spinner.isAlive() || spinner.HP < hp, 8, () -> PrisonScenarios.chase( d, spinner, () -> false, 1 ));
					d.expect( !spinner.isAlive() || spinner.HP < hp, "eight steps left the Fungal Spinner at " + spinner.HP + " of " + hp );
					boolean[] webbed = { false };
					d.until( () -> webbed[ 0 ], 16, () -> {
						int from = Dungeon.hero.pos;
						retreat( d, spinner );
						webbed[ 0 ] = regrowthNear( from );
					});
					d.expect( webbed[ 0 ], "the Fungal Spinner laid no regrowth where the hero walked in 16 steps" );
				} ).depth( CAVES );
	}

	private static void retreat( Driver d, Mob mob ){
		for (int offset : PathFinder.NEIGHBOURS8){
			int cell = Dungeon.hero.pos + offset;
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			if (!Dungeon.level.passable[ cell ] || Dungeon.level.solid[ cell ] || Actor.findChar( cell ) != null) continue;
			if (Dungeon.level.distance( cell, mob.pos ) <= Dungeon.level.distance( Dungeon.hero.pos, mob.pos )) continue;
			d.move( cell );
			return;
		}
		d.act( Action.WAIT );
	}

	private static Scenario geomancer(){
		return new Scenario( "caves.GnollGeomancer", "a Gnoll Geomancer brings the ceiling down where it marks", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					world.hunting( new GnollGeomancer(), world.cellAtRange( 3, true ));
				},
				d -> {
					GnollGeomancer geomancer = d.world().find( GnollGeomancer.class );
					d.fixture( geomancer.buff( GnollGeomancer.GnollRockFall.class ) == null, "a rockfall was already in the air" );
					d.fixture( geomancer.HP == geomancer.HT, "the geomancer stood damaged before the fight" );
					int heroHp = Dungeon.hero.HP;
					Set< Buff > buffs = new HashSet<>( Dungeon.hero.buffs() );
					boolean[] marked = { false };
					d.until( () -> marked[ 0 ] && (Dungeon.hero.HP < heroHp || RoomScenarios.newDebuff( buffs )), 20, () -> {
						d.act( Action.WAIT );
						marked[ 0 ] |= geomancer.buff( GnollGeomancer.GnollRockFall.class ) != null;
					});
					d.expect( marked[ 0 ], "the geomancer marked no cells with a Gnoll Rock Fall in 20 turns" );
					d.expect( Dungeon.hero.HP < heroHp || RoomScenarios.newDebuff( buffs ),
							"20 turns of marked cells left the hero at " + Dungeon.hero.HP + " HP and unparalysed" );
					int hp = geomancer.HP;
					PrisonScenarios.chase( d, geomancer, () -> !geomancer.isAlive() || geomancer.HP < hp, 16 );
					d.expect( !geomancer.isAlive() || geomancer.HP < hp,
							"16 attacks left the geomancer at " + geomancer.HP + " of " + hp );
				} ).depth( CAVES );
	}

	private static Scenario pylon(){
		return new Scenario( "caves.Pylon", "a pylon woken by DM-300 supercharging zaps the char beside it", HeroClass.WARRIOR,
				( world, kit ) -> {
					arena( world, kit );
					for (int pylon : CavesBossLevel.pylonPositions) pen( world, pylon );
				},
				d -> {
					DM300 dm = boss();
					d.fixture( dm != null, "the sealed arena held no DM-300" );
					d.fixture( !dm.isSupercharged(), "DM-300 began the fight supercharged" );
					d.fixture( activePylon() == null, "a pylon stood activated before DM-300 charged" );
					d.until( () -> dm.isSupercharged(), BOSS_STEPS, () -> PrisonScenarios.chase( d, dm, () -> false, 1 ));
					d.require( dm.isSupercharged(), BOSS_STEPS + " steps left DM-300 uncharged at " + dm.HP );
					Pylon pylon = activePylon();
					d.require( pylon != null, "supercharging DM-300 activated no pylon" );
					Rat rat = ratBeside( pylon.pos );
					d.require( rat != null, "no rat waited beside the activated pylon" );
					int hp = rat.HP;
					d.waitFor( () -> rat.HP < hp || !rat.isAlive(), 12 );
					d.expect( rat.HP < hp || !rat.isAlive(), "12 turns beside the activated pylon left the rat at " + rat.HP + " HP" );
				} ).depth( BOSS );
	}

	private static Scenario fungalCore(){
		return new Scenario( "caves.FungalCore", "the fungal core of the mine dies and its quest boss is beaten", HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy();
					kit.strength( 20 );
					kit.hero().belongings.weapon.level( 20 );
					FungalCore core = world.find( FungalCore.class );
					world.clearMobs( core );
					ScrollOfTeleportation.appear( Dungeon.hero, freeNeighbour( core.pos ));
				},
				d -> {
					FungalCore core = d.world().find( FungalCore.class );
					d.require( !Blacksmith.Quest.bossBeaten(), "the fungal quest was already beaten" );
					PrisonScenarios.chase( d, core, () -> !core.isAlive(), BOSS_STEPS );
					d.expect( !core.isAlive(), BOSS_STEPS + " steps left the fungal core at " + core.HP + " HP" );
					d.expect( Blacksmith.Quest.bossBeaten(), "the fungal core died without beating the quest boss" );
				} ).depth( CAVES ).branch( 1 ).prepare( CaveMineScenarios.quest( Blacksmith.Quest.FUNGI ));
	}

	private static Scenario crystalSpire(){
		return new Scenario( "caves.CrystalSpire", "a crystal spire is mined down with the pickaxe", HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy();
					kit.give( new Pickaxe() );
					CrystalSpire spire = world.find( CrystalSpire.class );
					world.clearMobs( spire );
					ScrollOfTeleportation.appear( Dungeon.hero, freeNeighbour( spire.pos ));
				},
				d -> {
					CrystalSpire spire = d.world().find( CrystalSpire.class );
					d.require( spire.HP == spire.HT, "the spire stood damaged before the pickaxe" );
					int hp = spire.HP;
					d.until( () -> spire.HP < hp, 6, () -> {
						if (Dungeon.level.adjacent( Dungeon.hero.pos, spire.pos )) d.move( spire.pos );
						else d.act( Action.WAIT );
					});
					d.expect( spire.HP < hp, "six pickaxe swings left the spire at " + spire.HP + " HP" );
					d.expect( Dungeon.hero.belongings.getItem( Pickaxe.class ) != null, "the pickaxe left the pack" );
				} ).depth( CAVES ).branch( 1 ).prepare( CaveMineScenarios.quest( Blacksmith.Quest.CRYSTAL ));
	}

	private static Scenario dm300(){
		return new Scenario( "caves.dm300", "fight DM-300 in its sealed arena", HeroClass.WARRIOR,
				CaveScenarios::arena,
				d -> {
					DM300 dm = boss();
					d.require( dm != null, "the sealed arena held no DM-300" );
					int hp = dm.HP;
					PrisonScenarios.chase( d, dm, () -> dm.HP < hp, BOSS_STEPS );
					d.expect( dm.HP < hp, BOSS_STEPS + " steps beside DM-300 left it at " + dm.HP + " of " + hp );
					d.expect( d.env().running(), "the fight ended the episode: " + d.env().endReason() );
				} ).depth( BOSS );
	}

	private static Scenario descend(){
		return new Scenario( "caves.descend", "take the stairs down from a caves floor", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Arrays.fill( world.level().mapped, true );
				},
				d -> {
					d.require( Dungeon.level instanceof CavesLevel, "depth " + Dungeon.depth + " is a "
							+ Dungeon.level.getClass().getSimpleName() );
					d.fixture( d.env().actionMask()[ Action.TRAVEL_STAIRS.index ] > 0.5f, "no known path to the exit" );
					d.until( () -> Dungeon.depth == CAVES + 1, 100, () -> d.act( Action.TRAVEL_STAIRS ));
					d.expect( Dungeon.depth == CAVES + 1, "100 TRAVEL_STAIRS steps from depth " + CAVES
							+ " left the hero on depth " + Dungeon.depth );
				} ).depth( CAVES );
	}

	private static void arena( World world, HeroKit kit ){
		kit.sturdy();
		kit.strength( 20 );
		kit.hero().belongings.weapon.level( 12 );
		int cell = freeNeighbour( CavesBossLevel.pylonPositions[ 0 ] );
		ScrollOfTeleportation.appear( Dungeon.hero, cell );
		if (boss() == null) throw new NoFixture( "stepping beside a pylon did not seal the arena" );
	}

	private static Pylon activePylon(){
		for (Mob mob : Dungeon.level.mobs) if (mob instanceof Pylon && mob.alignment == Char.Alignment.ENEMY) return (Pylon) mob;
		return null;
	}

	private static void pen( World world, int pylon ){
		for (int offset : PathFinder.NEIGHBOURS8){
			int cell = pylon + offset;
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			int terrain = Dungeon.level.map[ cell ];
			if (terrain != Terrain.EMPTY && terrain != Terrain.EMPTY_SP) continue;
			if (Dungeon.level.solid[ cell ] || Dungeon.level.water[ cell ] || Actor.findChar( cell ) != null) continue;
			Rat rat = world.mob( new Rat(), cell );
			rat.HP = rat.HT = 100;
			Buff.affect( rat, Paralysis.class, 100f );
			return;
		}
	}

	private static Rat ratBeside( int cell ){
		for (int offset : PathFinder.NEIGHBOURS8){
			int beside = cell + offset;
			if (beside < 0 || beside >= Dungeon.level.length()) continue;
			Char ch = Actor.findChar( beside );
			if (ch instanceof Rat && ch.isAlive()) return (Rat) ch;
		}
		return null;
	}

	static boolean anyRegrowth(){
		for (Blob blob : Dungeon.level.blobs.values()) if (blob instanceof Regrowth && blob.volume > 0) return true;
		return false;
	}

	static boolean regrowthNear( int cell ){
		for (int offset : PathFinder.NEIGHBOURS9){
			int near = cell + offset;
			if (near < 0 || near >= Dungeon.level.length()) continue;
			if (Blob.volumeAt( near, Regrowth.class ) > 0) return true;
		}
		return false;
	}

	static DM300 boss(){
		for (Mob mob : Dungeon.level.mobs) if (mob instanceof DM300) return (DM300) mob;
		return null;
	}

	static int freeNeighbour( int cell ){
		for (int offset : PathFinder.NEIGHBOURS8){
			int beside = cell + offset;
			if (beside < 0 || beside >= Dungeon.level.length()) continue;
			if (Dungeon.level.passable[ beside ] && !Dungeon.level.solid[ beside ] && Actor.findChar( beside ) == null){
				return beside;
			}
		}
		throw new NoFixture( "no free cell beside " + cell );
	}
}
