package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Imp;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.VaultLaser;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.VaultMirror;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.VaultSentry;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.VaultTokenDoor;
import com.shatteredpixel.shatteredpixeldungeon.items.BrokenSeal;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.DwarfToken;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultAlternatingFireRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultCircleRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultCrossRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultEnemyCenterRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultHallwayRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultLasersRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultLongRingsRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultQuadrantsRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultRingRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultRingsRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultSimpleEnemyTreasureRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultTokensRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.treasure.VaultBookcaseTreasureRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.treasure.VaultCircleScanTreasureRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.treasure.VaultFlamePathRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.treasure.VaultFlamesTreasureRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.treasure.VaultHardLaserTreasureRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.treasure.VaultLaserTreasureRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.treasure.VaultManyScansRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.treasure.VaultMultipleEnemyTreasureRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.treasure.VaultSingleEnemyTreasureRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.treasure.VaultTreasureRoom;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;

import java.util.ArrayList;
import java.util.List;

final class VaultRoomScenarios {

	private VaultRoomScenarios(){}

	private static final int DEPTH = VaultScenarios.ROOM_DEPTH;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( sentries( VaultCircleRoom.class, "the circling sentry of a circle room zaps the hero" ));
		all.add( sentries( VaultCrossRoom.class, "the cross sentry of a cross room zaps the hero" ));
		all.add( laserRoom() );
		all.add( fireRoom() );
		all.add( tokens() );
		all.add( foes( VaultEnemyCenterRoom.class ));
		all.add( foes( VaultHallwayRoom.class ));
		all.add( foes( VaultLongRingsRoom.class ));
		all.add( foes( VaultQuadrantsRoom.class ));
		all.add( foes( VaultRingRoom.class ));
		all.add( foes( VaultRingsRoom.class ));
		all.add( foes( VaultSimpleEnemyTreasureRoom.class ));
		all.add( treasure( VaultBookcaseTreasureRoom.class, "its bookcases",
				( world, kit ) -> VaultScenarios.appear( besideChest( world.room( VaultBookcaseTreasureRoom.class ))),
				d -> {} ));
		all.add( treasure( VaultCircleScanTreasureRoom.class, "its scanning sentry",
				( world, kit ) -> VaultScenarios.appear( besideChest( world.room( VaultCircleScanTreasureRoom.class ))),
				d -> {} ).covers( VaultSentry.class ));
		all.add( treasure( VaultFlamePathRoom.class, "its flame path",
				( world, kit ) -> VaultScenarios.appear( VaultScenarios.hottestTrap( world.room( VaultFlamePathRoom.class ))),
				VaultScenarios::burned ).covers( com.shatteredpixel.shatteredpixeldungeon.actors.blobs.VaultFlameTraps.class ));
		all.add( treasure( VaultFlamesTreasureRoom.class, "its flames",
				( world, kit ) -> VaultScenarios.appear( VaultScenarios.hottestTrap( world.room( VaultFlamesTreasureRoom.class ))),
				VaultScenarios::burned ).covers( com.shatteredpixel.shatteredpixeldungeon.actors.blobs.VaultFlameTraps.class ));
		all.add( treasure( VaultHardLaserTreasureRoom.class, "its laser sentries",
				( world, kit ) -> VaultScenarios.appear( armed( world.room( VaultHardLaserTreasureRoom.class ))),
				VaultRoomScenarios::shot ).covers( VaultLaser.class ));
		all.add( treasure( VaultLaserTreasureRoom.class, "its laser sentries",
				( world, kit ) -> VaultScenarios.appear( armed( world.room( VaultLaserTreasureRoom.class ))),
				VaultRoomScenarios::shot ).covers( VaultLaser.class ));
		all.add( treasure( VaultManyScansRoom.class, "its scanning sentries",
				( world, kit ) -> scan( world.room( VaultManyScansRoom.class )),
				VaultRoomScenarios::scanned ).covers( VaultSentry.class ));
		all.add( treasure( VaultMultipleEnemyTreasureRoom.class, "its ghoul guards",
				( world, kit ) -> VaultScenarios.appear( VaultScenarios.inside( world.room( VaultMultipleEnemyTreasureRoom.class ))),
				d -> guard( d, d.world().room( VaultMultipleEnemyTreasureRoom.class ))));
		all.add( treasure( VaultSingleEnemyTreasureRoom.class, "its guard",
				( world, kit ) -> VaultScenarios.appear( VaultScenarios.inside( world.room( VaultSingleEnemyTreasureRoom.class ))),
				d -> guard( d, d.world().room( VaultSingleEnemyTreasureRoom.class ))));
		return all;
	}

	private static Scenario sentries( Class< ? extends Room > type, String mechanic ){
		return VaultScenarios.room( type, DEPTH, mechanic,
				( world, kit ) -> {
					Room room = world.room( type );
					VaultSentry sentry = VaultScenarios.in( room, VaultSentry.class );
					sentry.curCooldown = 1;
					sentry.afterScanCooldown = 1;
					VaultScenarios.appear( scanTarget( sentry, room ));
				},
				VaultRoomScenarios::scanned ).covers( VaultSentry.class );
	}

	private static Scenario laserRoom(){
		Class< VaultLasersRoom > type = VaultLasersRoom.class;
		return VaultScenarios.room( type, DEPTH, "a laser sentry fires across the room and hits the hero",
				( world, kit ) -> VaultScenarios.appear( armed( world.room( type ))),
				VaultRoomScenarios::shot ).covers( VaultLaser.class );
	}

	private static Scenario fireRoom(){
		Class< VaultAlternatingFireRoom > type = VaultAlternatingFireRoom.class;
		return VaultScenarios.room( type, DEPTH, "the alternating flame traps burn the hero",
				( world, kit ) -> VaultScenarios.appear( VaultScenarios.hottestTrap( world.room( type ))),
				d -> {
					VaultScenarios.burned( d );
					Room room = d.world().room( type );
					int loot = VaultScenarios.heap( room, heap -> !heap.items.isEmpty() );
					d.expect( loot >= 0, "the alternating fire room held no equipment" );
					d.expect( RoomVisit.take( d, loot ), "the equipment in the alternating fire room was not taken" );
				}).covers( com.shatteredpixel.shatteredpixeldungeon.actors.blobs.VaultFlameTraps.class );
	}

	private static Scenario tokens(){
		Class< VaultTokensRoom > type = VaultTokensRoom.class;
		return VaultScenarios.room( type, DEPTH, "ten dwarf tokens open the token door and the mirror gives its gift",
				( world, kit ) -> {
					kit.give( new DwarfToken() ).quantity( 10 );
					Room room = world.room( type );
					VaultTokenDoor door = VaultScenarios.in( room, VaultTokenDoor.class );
					VaultScenarios.appear( VaultScenarios.beside( door.pos, room ));
				},
				d -> {
					Room room = d.world().room( type );
					VaultTokenDoor door = VaultScenarios.in( room, VaultTokenDoor.class );
					int cell = door.pos;
					int tokens = RoomVisit.held( new DwarfToken() );
					d.require( tokens >= 10, "the hero carried " + tokens + " dwarf tokens, not 10" );
					d.move( cell );
					d.require( d.env().mode() == EnvMode.MENU, "the token door asked nothing: " + d.env().mode() );
					d.require( WindowBridge.optionCount() == 2, "the token door offered "
							+ WindowBridge.optionCount() + " options" );
					d.menu( 0 );
					d.expect( Dungeon.level.map[ cell ] == Terrain.DOOR, "confirming the token door left terrain "
							+ Dungeon.level.map[ cell ] );
					d.expect( VaultScenarios.count( VaultTokenDoor.class ) == 0, "the opened token door is still on the level" );
					d.expect( RoomVisit.held( new DwarfToken() ) < tokens, "the token door opened without spending the tokens" );
					VaultMirror mirror = VaultScenarios.in( room, VaultMirror.class );
					RoomVisit.walk( d, mirror.pos, 20 );
					d.move( mirror.pos );
					d.require( d.env().mode() == EnvMode.MENU, "the mirror asked nothing: " + d.env().mode() );
					d.menu( 0 );
					d.expect( Imp.Quest.mirrorUsed, "taking the mirror's gift did not use the mirror" );
					Heap at = Dungeon.level.heaps.get( Dungeon.hero.pos );
					d.expect( Dungeon.hero.belongings.getItem( BrokenSeal.class ) != null
							|| (at != null && VaultScenarios.holds( at, BrokenSeal.class )),
							"the mirror gave the warrior nothing" );
				}).covers( VaultTokenDoor.class, VaultMirror.class );
	}

	private static Scenario foes( Class< ? extends Room > type ){
		return VaultScenarios.room( type, DEPTH, "kill the guard of a " + type.getSimpleName(),
				( world, kit ) -> VaultScenarios.appear( VaultScenarios.inside( world.room( type ))),
				d -> {
					Room room = d.world().room( type );
					VaultScenarios.slay( d, VaultScenarios.foe( room ), 24 );
					int chest = VaultScenarios.heap( room, heap -> heap.type == Heap.Type.CHEST );
					if (chest >= 0) d.expect( RoomVisit.take( d, chest ), "the chest in the "
							+ type.getSimpleName() + " was not opened" );
				});
	}

	private static Scenario treasure( Class< ? extends VaultTreasureRoom > type, String hazard, Scenario.Setup place,
			Scenario.Script pass ){
		return VaultScenarios.room( type, DEPTH, "take the chest of a " + type.getSimpleName() + " past " + hazard,
				place,
				d -> {
					Room room = d.world().room( type );
					pass.run( d );
					int chest = VaultScenarios.heap( room, heap -> heap.type == Heap.Type.CHEST );
					d.fixture( chest >= 0, "the " + type.getSimpleName() + " held no chest" );
					d.expect( RoomVisit.take( d, chest ), "the chest in the " + type.getSimpleName()
							+ " was not opened" );
				});
	}

	private static int besideChest( Room room ){
		int chest = VaultScenarios.heap( room, heap -> heap.type == Heap.Type.CHEST );
		if (chest < 0) throw new NoFixture( "the " + room.getClass().getSimpleName() + " held no chest" );
		return VaultScenarios.beside( chest, room );
	}

	private static int armed( Room room ){
		VaultLaser sentry = VaultScenarios.in( room, VaultLaser.class );
		sentry.curCooldown = 1;
		sentry.afterShotCooldown = 1;
		sentry.shotsAfterCooldown = 1;
		sentry.giveWarning = false;
		for (int i = 0; i < sentry.laserDirs.length; i++){
			if (!VaultScenarios.free( sentry.laserDirs[ i ] )) continue;
			sentry.laserDirIdx = i;
			return sentry.laserDirs[ i ];
		}
		throw new NoFixture( "no free cell in a laser beam of the " + room.getClass().getSimpleName() );
	}

	private static void scan( Room room ){
		VaultSentry sentry = VaultScenarios.in( room, VaultSentry.class );
		sentry.curCooldown = 1;
		sentry.afterScanCooldown = 1;
		VaultScenarios.appear( scanTarget( sentry, room ));
	}

	private static int scanTarget( VaultSentry sentry, Room room ){
		for (int[] turn : sentry.scanDirs){
			for (int cell : turn){
				if (VaultScenarios.free( cell )) return cell;
			}
		}
		return VaultScenarios.beside( Dungeon.level.pointToCell( room.center() ), room );
	}

	private static void scanned( Driver d ){
		int hp = Dungeon.hero.HP;
		d.waitFor( () -> Dungeon.hero.HP < hp, 20 );
		d.expect( Dungeon.hero.HP < hp, "20 turns in the sentry's scan left the hero at "
				+ Dungeon.hero.HP + " of " + hp );
	}

	private static void shot( Driver d ){
		int hp = Dungeon.hero.HP;
		d.waitFor( () -> Dungeon.hero.HP < hp, 10 );
		d.expect( Dungeon.hero.HP < hp, "10 turns in the laser's beam left the hero at "
				+ Dungeon.hero.HP + " of " + hp );
	}

	private static void guard( Driver d, Room room ){
		VaultScenarios.slay( d, VaultScenarios.foe( room ), 24 );
	}
}
