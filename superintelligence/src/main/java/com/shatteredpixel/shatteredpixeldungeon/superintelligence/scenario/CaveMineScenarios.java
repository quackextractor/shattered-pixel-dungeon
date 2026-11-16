package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GnollGeomancer;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Blacksmith;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.DarkGold;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.Pickaxe;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.levels.MiningLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.BlacksmithRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.MineEntrance;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.MineGiantRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.MineLargeRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.MineSecretRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.MineSmallRoom;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;
import com.watabou.utils.Bundle;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;

final class CaveMineScenarios {

	private CaveMineScenarios(){}

	static final int MINE_DEPTH = 12;

	static final int SMITH_DEPTH = 14;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( entrance().covers( MineEntrance.class ));
		all.add( ore().covers( Pickaxe.class, DarkGold.class ));
		all.add( giant().covers( MineGiantRoom.class ));
		all.add( large().covers( MineLargeRoom.class ));
		all.add( small().covers( MineSmallRoom.class ));
		all.add( secret().covers( MineSecretRoom.class ));
		all.add( blacksmith().covers( BlacksmithRoom.class ));
		all.add( mineLevel().covers( MiningLevel.class ));
		return all;
	}

	static Runnable quest( int type ){
		return quest( type, false );
	}

	static Runnable quest( int type, boolean completed ){
		return () -> {
			Bundle bundle = new Bundle();
			Bundle node = new Bundle();
			node.put( "spawned", true );
			node.put( "type", type );
			node.put( "given", true );
			node.put( "started", true );
			node.put( "boss_beaten", false );
			node.put( "completed", completed );
			node.put( "favor", 0 );
			node.put( "reforges", 0 );
			node.put( "hardens", 0 );
			node.put( "upgrades", 0 );
			node.put( "smiths", 0 );
			bundle.put( "blacksmith", node );
			Blacksmith.Quest.restoreFromBundle( bundle );
		};
	}

	private static Scenario entrance(){
		return new Scenario( "caves.mine-entrance", "the hero enters the mines at the mine entrance", HeroClass.WARRIOR,
				( world, kit ) -> kit.sturdy(),
				d -> {
					d.fixture( Dungeon.level instanceof MiningLevel, "depth " + MINE_DEPTH + " branch 1 is a "
							+ Dungeon.level.getClass().getSimpleName() );
					MineEntrance room = d.world().room( MineEntrance.class );
					LevelTransition in = Dungeon.level.getTransition( LevelTransition.Type.BRANCH_ENTRANCE );
					d.fixture( in != null, "the mine held no branch entrance" );
					d.fixture( Dungeon.hero.pos == in.cell(), "the hero landed on " + Dungeon.hero.pos + ", not the mine entrance at "
							+ in.cell() );
					d.fixture( room.inside( Dungeon.level.cellToPoint( Dungeon.hero.pos )), "the mine entrance lies outside the MineEntrance room" );
					int away = CaveScenarios.freeNeighbour( Dungeon.hero.pos );
					d.move( away );
					d.expect( Dungeon.hero.pos == away, "stepping off the mine entrance left the hero at " + Dungeon.hero.pos );
				} ).depth( MINE_DEPTH ).branch( 1 ).prepare( quest( Blacksmith.Quest.GNOLL ));
	}

	private static Scenario ore(){
		return new Scenario( "caves.mine-ore", "the pickaxe equips, hits a rat, then mines golden ore", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength( 20 );
					kit.hero().belongings.weapon = null;
					kit.give( new Pickaxe() );
					int ore = ore( null );
					int spot = beside( ore, null );
					ScrollOfTeleportation.appear( Dungeon.hero, spot );
					Rat rat = world.hunting( new Rat(), elsewhere( spot, ore ));
					rat.HP = rat.HT = 100;
				},
				d -> {
					d.use( Pickaxe.class );
					d.require( Dungeon.hero.belongings.weapon instanceof Pickaxe, "using the pickaxe left the hero wielding "
							+ Dungeon.hero.belongings.weapon );
					Rat rat = d.world().find( Rat.class );
					int hp = rat.HP;
					d.expect( d.until( () -> !rat.isAlive() || rat.HP < hp, 6, () -> d.move( rat.pos )),
							"the pickaxe left the rat at " + hp + " HP after 6 attacks" );
					int ore = terrainBeside( Terrain.WALL_DECO );
					int mined = darkGold();
					d.move( ore );
					d.expect( Dungeon.level.map[ ore ] == Terrain.EMPTY_DECO, "mining the ore at " + ore + " left terrain "
							+ Dungeon.level.map[ ore ] );
					d.expect( darkGold() > mined, "mining the ore added no dark gold to " + mined );
				} ).depth( MINE_DEPTH ).branch( 1 ).prepare( quest( Blacksmith.Quest.GNOLL ));
	}

	private static Scenario giant(){
		return new Scenario( "caves.MineGiant", "mine a boulder out of the giant mine room", HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy();
					kit.strength( 20 );
					kit.give( new Pickaxe() );
					MineGiantRoom room = world.room( MineGiantRoom.class );
					GnollGeomancer boss = world.find( GnollGeomancer.class );
					world.clearMobs( boss );
					if (!room.inside( Dungeon.level.cellToPoint( boss.pos ))) throw new NoFixture( "the geomancer stands outside the giant room" );
					int boulder = terrain( Terrain.MINE_BOULDER, room );
					ScrollOfTeleportation.appear( Dungeon.hero, beside( boulder, room ));
				},
				d -> {
					MineGiantRoom room = d.world().room( MineGiantRoom.class );
					GnollGeomancer boss = d.world().find( GnollGeomancer.class );
					d.fixture( room.inside( Dungeon.level.cellToPoint( Dungeon.hero.pos )), "the hero stood outside the giant room" );
					d.fixture( room.inside( Dungeon.level.cellToPoint( boss.pos )), "the giant room's geomancer stood outside it" );
					int boulder = terrainBeside( Terrain.MINE_BOULDER );
					d.move( boulder );
					d.expect( Dungeon.level.map[ boulder ] == Terrain.EMPTY_DECO, "mining the boulder at " + boulder
							+ " left terrain " + Dungeon.level.map[ boulder ] );
				} ).depth( MINE_DEPTH ).branch( 1 ).prepare( quest( Blacksmith.Quest.GNOLL ));
	}

	private static Scenario large(){
		return new Scenario( "caves.MineLarge", "mine the golden ore in a large mine room", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength( 20 );
					kit.give( new Pickaxe() );
					MineLargeRoom room = world.room( MineLargeRoom.class );
					int ore = ore( room );
					ScrollOfTeleportation.appear( Dungeon.hero, beside( ore, room ));
				},
				d -> {
					MineLargeRoom room = d.world().room( MineLargeRoom.class );
					d.fixture( room.inside( Dungeon.level.cellToPoint( Dungeon.hero.pos )), "the hero stood outside the large room" );
					int ore = terrainBeside( Terrain.WALL_DECO );
					d.move( ore );
					d.act( Action.WAIT );
					d.expect( Dungeon.level.map[ ore ] == Terrain.EMPTY_DECO, "mining the ore at " + ore + " left terrain "
							+ Dungeon.level.map[ ore ] );
					d.expect( Dungeon.hero.belongings.getItem( Pickaxe.class ) != null, "the pickaxe left the pack" );
				} ).depth( MINE_DEPTH ).branch( 1 ).prepare( quest( Blacksmith.Quest.GNOLL ));
	}

	private static Scenario small(){
		return new Scenario( "caves.MineSmall", "mine a boulder out of a small mine room", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength( 20 );
					kit.give( new Pickaxe() );
					MineSmallRoom room = world.room( MineSmallRoom.class );
					int boulder = terrain( Terrain.MINE_BOULDER, room );
					ScrollOfTeleportation.appear( Dungeon.hero, beside( boulder, room ));
				},
				d -> {
					MineSmallRoom room = d.world().room( MineSmallRoom.class );
					d.fixture( room.inside( Dungeon.level.cellToPoint( Dungeon.hero.pos )), "the hero stood outside the small room" );
					int boulder = terrainBeside( Terrain.MINE_BOULDER );
					d.move( boulder );
					d.act( Action.WAIT );
					d.expect( Dungeon.level.map[ boulder ] == Terrain.EMPTY_DECO, "mining the boulder at " + boulder
							+ " left terrain " + Dungeon.level.map[ boulder ] );
				} ).depth( MINE_DEPTH ).branch( 1 ).prepare( quest( Blacksmith.Quest.GNOLL ));
	}

	private static Scenario secret(){
		return new Scenario( "caves.MineSecret", "take the dark gold chest inside a secret mine room", HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy();
					kit.strength( 20 );
					MineSecretRoom room = world.room( MineSecretRoom.class );
					int chest = chest( room );
					ScrollOfTeleportation.appear( Dungeon.hero, beside( chest, room ));
				},
				d -> {
					MineSecretRoom room = d.world().room( MineSecretRoom.class );
					d.fixture( room.inside( Dungeon.level.cellToPoint( Dungeon.hero.pos )), "the hero stood outside the secret room" );
					int chest = chestBeside();
					Heap before = Dungeon.level.heaps.get( chest );
					d.fixture( before != null && before.type == Heap.Type.CHEST, "the secret room's heap is not a chest: "
							+ (before == null ? "no heap" : before.type) );
					d.move( chest );
					d.waitFor( () -> Dungeon.level.heaps.get( chest ).type != Heap.Type.CHEST, 4 );
					d.expect( Dungeon.level.heaps.get( chest ).type != Heap.Type.CHEST, "stepping onto the secret room's chest left it "
							+ Dungeon.level.heaps.get( chest ).type );
				} ).depth( MINE_DEPTH ).branch( 1 ).prepare( quest( Blacksmith.Quest.GNOLL ));
	}

	private static Scenario blacksmith(){
		return new Scenario( "caves.blacksmith", "answer the blacksmith's reward window with a smith", HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy();
					BlacksmithRoom room = world.room( BlacksmithRoom.class );
					Mob smith = null;
					for (Mob mob : Dungeon.level.mobs) if (mob instanceof Blacksmith) smith = mob;
					if (smith == null) throw new NoFixture( "the Blacksmith room holds no blacksmith" );
					if (!room.inside( Dungeon.level.cellToPoint( smith.pos ))) throw new NoFixture( "the blacksmith stands outside its room" );
					world.clearMobs( smith );
					ScrollOfTeleportation.appear( Dungeon.hero, CaveScenarios.freeNeighbour( smith.pos ));
					quest( Blacksmith.Quest.GNOLL, true ).run();
					Blacksmith.Quest.favor = 2500;
				},
				d -> {
					Mob smith = null;
					for (Mob mob : Dungeon.level.mobs) if (mob instanceof Blacksmith) smith = mob;
					d.require( smith != null, "the blacksmith left the room" );
					d.require( Blacksmith.Quest.completed(), "the forced blacksmith quest is not complete: given="
							+ Blacksmith.Quest.given() + " rewards=" + Blacksmith.Quest.rewardsAvailable()
							+ " favor=" + Blacksmith.Quest.favor );
					int carried = Dungeon.hero.belongings.backpack.items.size();
					d.move( smith.pos );
					d.require( d.env().mode() == EnvMode.MENU, "talking to the blacksmith opened " + d.env().mode() );
					d.require( WindowBridge.optionCount() == 5, "the blacksmith offered " + WindowBridge.optionCount() + " buttons" );
					d.menu( 3 );
					d.require( d.env().mode() == EnvMode.MENU, "the smith choice opened " + d.env().mode() );
					d.require( WindowBridge.optionCount() == 2, "the smith confirm offered " + WindowBridge.optionCount() + " options" );
					d.menu( 0 );
					d.require( d.env().mode() == EnvMode.MENU, "paying the smith opened " + d.env().mode() );
					d.expect( WindowBridge.optionCount() == 4, "the smith offered " + WindowBridge.optionCount() + " rewards" );
					d.menu( 0 );
					d.require( d.env().mode() == EnvMode.MENU, "picking a reward opened " + d.env().mode() );
					d.menu( 0 );
					d.expect( Dungeon.hero.belongings.backpack.items.size() > carried, "confirming the reward added nothing to the pack" );
					d.expect( Blacksmith.Quest.smiths == 1, "the smith recorded " + Blacksmith.Quest.smiths + " smiths" );
					d.expect( d.env().mode() == EnvMode.WORLD, "the reward left the env in " + d.env().mode() );
					d.act( Action.WAIT );
				} ).depth( SMITH_DEPTH ).seeds( "SCN-A", "SCN-B", "SCN-C", "SCN-D", "SCN-E", "SCN-F", "SCN-G", "SCN-H" );
	}

	private static Scenario mineLevel(){
		return new Scenario( "caves.mine-level", "leave the mining level through its branch entrance", HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy();
					kit.give( new Pickaxe() );
				},
				d -> {
					d.fixture( Dungeon.level instanceof MiningLevel, "the jump landed on a " + Dungeon.level.getClass().getSimpleName() );
					LevelTransition in = Dungeon.level.getTransition( LevelTransition.Type.BRANCH_ENTRANCE );
					d.fixture( in != null, "the mining level held no branch entrance" );
					d.fixture( Dungeon.hero.pos == in.cell(), "the hero landed on " + Dungeon.hero.pos + ", not the mine entrance" );
					int entrance = in.cell();
					d.move( CaveScenarios.freeNeighbour( entrance ));
					d.move( entrance );
					d.require( d.env().mode() == EnvMode.MENU, "stepping onto the mine exit asked nothing: " + d.env().mode() );
					d.fixture( WindowBridge.optionCount() == 2, "the mine exit offered " + WindowBridge.optionCount() + " options" );
					d.menu( 0 );
					d.until( () -> Dungeon.branch == 0 || !(Dungeon.level instanceof MiningLevel), 6, () -> d.act( Action.WAIT ));
					d.expect( Dungeon.branch == 0 && Dungeon.depth == MINE_DEPTH, "answering the mine exit left the hero on depth "
							+ Dungeon.depth + " branch " + Dungeon.branch );
					d.expect( !(Dungeon.level instanceof MiningLevel), "answering the mine exit left the hero on a "
							+ Dungeon.level.getClass().getSimpleName() );
				} ).depth( MINE_DEPTH ).branch( 1 ).prepare( quest( Blacksmith.Quest.GNOLL ));
	}

	private static int terrainBeside( int terrain ){
		for (int offset : PathFinder.NEIGHBOURS8){
			int cell = Dungeon.hero.pos + offset;
			if (cell >= 0 && cell < Dungeon.level.length() && Dungeon.level.map[ cell ] == terrain) return cell;
		}
		throw new Driver.Stop( "no terrain " + terrain + " beside the hero at " + Dungeon.hero.pos );
	}

	private static int chestBeside(){
		for (int offset : PathFinder.NEIGHBOURS8){
			int cell = Dungeon.hero.pos + offset;
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			Heap heap = Dungeon.level.heaps.get( cell );
			if (heap != null && heap.type == Heap.Type.CHEST) return cell;
		}
		throw new Driver.Stop( "no chest beside the hero at " + Dungeon.hero.pos );
	}

	private static int elsewhere( int cell, int keep ){
		for (int offset : PathFinder.NEIGHBOURS8){
			int beside = cell + offset;
			if (beside == keep || beside < 0 || beside >= Dungeon.level.length()) continue;
			if (!Dungeon.level.passable[ beside ] || Dungeon.level.solid[ beside ]) continue;
			if (Actor.findChar( beside ) != null || Dungeon.level.heaps.get( beside ) != null) continue;
			return beside;
		}
		throw new NoFixture( "no second free cell beside " + cell );
	}

	private static int darkGold(){
		DarkGold gold = Dungeon.hero.belongings.getItem( DarkGold.class );
		return gold == null ? 0 : gold.quantity();
	}

	private static int ore( Room room ){
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (Dungeon.level.map[ cell ] != Terrain.WALL_DECO) continue;
			if (room != null && !room.inside( Dungeon.level.cellToPoint( cell ))) continue;
			if (beside( cell, room ) >= 0) return cell;
		}
		throw new NoFixture( "no mineable ore" + (room == null ? "" : " in the " + room.getClass().getSimpleName()) );
	}

	private static int terrain( int terrain, Room room ){
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (Dungeon.level.map[ cell ] != terrain) continue;
			if (room != null && !room.inside( Dungeon.level.cellToPoint( cell ))) continue;
			if (beside( cell, room ) >= 0) return cell;
		}
		throw new NoFixture( "no terrain " + terrain + " in the " + room.getClass().getSimpleName() );
	}

	private static int chest( Room room ){
		for (Heap heap : Dungeon.level.heaps.valueList()){
			if (heap.type != Heap.Type.CHEST) continue;
			if (!room.inside( Dungeon.level.cellToPoint( heap.pos ))) continue;
			if (beside( heap.pos, room ) >= 0) return heap.pos;
		}
		throw new NoFixture( "no chest in the " + room.getClass().getSimpleName() );
	}

	private static int beside( int cell, Room room ){
		for (int offset : PathFinder.NEIGHBOURS8){
			int beside = cell + offset;
			if (beside < 0 || beside >= Dungeon.level.length()) continue;
			if (!Dungeon.level.passable[ beside ] || Dungeon.level.solid[ beside ]) continue;
			if (Actor.findChar( beside ) != null || Dungeon.level.heaps.get( beside ) != null) continue;
			if (room != null && !room.inside( Dungeon.level.cellToPoint( beside ))) continue;
			return beside;
		}
		return -1;
	}
}
