package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Imp;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.NPC;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultBossElemental;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultDM100;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultDM200;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultElemental;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultGhoul;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultGolem;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultRat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultShaman;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultSkeleton;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.DwarfToken;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.EscapeCrystal;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.ImpStatue;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.VaultBeacon;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.VaultLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultFinalRoom;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;
import com.watabou.utils.Bundle;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

final class VaultScenarios {

	private VaultScenarios(){}

	static final int ROOM_DEPTH = 16;

	static final int DEEP_DEPTH = 18;

	static final int BOSS_DEPTH = 19;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( fight( "vault.VaultRat", VaultRat.class, "the Vault Rat never hurt the hero", ROOM_DEPTH ));
		all.add( fight( "vault.VaultSkeleton", VaultSkeleton.class, "the Vault Skeleton never hurt the hero", ROOM_DEPTH ));
		all.add( fight( "vault.VaultDM100", VaultDM100.class, "the Vault DM100 never zapped the hero", ROOM_DEPTH ));
		all.add( fight( "vault.VaultGolem", VaultGolem.class, "the Vault Golem never hurt the hero", 17 ));
		all.add( fight( "vault.VaultGhoul", VaultGhoul.class, "the Vault Ghoul never hurt the hero", 17 ));
		all.add( fight( "vault.VaultShaman", VaultShaman.class, "the Vault Shaman never zapped the hero", 17 ));
		all.add( fight( "vault.VaultDM200", VaultDM200.class, "the Vault DM200 never zapped the hero", DEEP_DEPTH ));
		all.add( fight( "vault.VaultElementalFire", VaultElemental.Fire.class, "the fire vault elemental never burned the hero",
				DEEP_DEPTH ));
		all.add( fight( "vault.VaultElementalFrost", VaultElemental.Frost.class,
				"the frost vault elemental never chilled the hero", DEEP_DEPTH ));
		all.add( fight( "vault.VaultElementalShock", VaultElemental.Shock.class,
				"the shock vault elemental never shocked the hero", DEEP_DEPTH ));
		all.add( boss() );
		all.add( escape() );
		all.add( statue() );
		all.add( beacon() );
		return all;
	}

	static Runnable quest(){
		return () -> {
			RoomForcing.vaultReset().run();
			Bundle bundle = new Bundle();
			Bundle node = new Bundle();
			node.put( "spawned", true );
			node.put( "old_quest", false );
			node.put( "given", true );
			node.put( "completed", false );
			node.put( "hazard_freebies", 2 );
			node.put( "score", 0 );
			node.put( "mirror_used", false );
			node.put( "reward_options", new ArrayList< com.shatteredpixel.shatteredpixeldungeon.items.Item >() );
			bundle.put( "demon", node );
			Imp.Quest.restoreFromBundle( bundle );
		};
	}

	static Runnable forcing( Class< ? extends Room > type ){
		return () -> {
			quest().run();
			RoomForcing.vault( type ).run();
		};
	}

	static Scenario room( Class< ? extends Room > type, int depth, String mechanic, Scenario.Setup extra,
			Scenario.Script script ){
		return new Scenario( "vault." + type.getSimpleName(), mechanic, HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy().level( 20 ).strength( 20 );
					kit.hero().belongings.weapon.level( 20 );
					extra.build( world, kit );
				}, script ).depth( depth ).branch( 1 ).prepare( forcing( type ) ).covers( type );
	}

	private static Scenario fight( String id, Class< ? extends Mob > type, String failure, int depth ){
		return FoeFamily.fight( id, type, 1, 16, null, failure ).depth( depth ).branch( 1 ).prepare( quest() );
	}

	private static Scenario boss(){
		Scenario scenario = new Scenario( "vault.VaultBossElemental", "the vault boss changes form when its HP falls a bracket",
				HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy().level( 20 ).strength( 20 );
					kit.hero().belongings.weapon.level( 20 );
					VaultFinalRoom room = world.room( VaultFinalRoom.class );
					int door = terrain( room, Terrain.LOCKED_DOOR );
					int entry = terrain( room, Terrain.DOOR );
					appear( approach( room, door, entry, 5 ));
				},
				d -> {
					VaultFinalRoom room = d.world().room( VaultFinalRoom.class );
					int door = terrain( room, Terrain.LOCKED_DOOR );
					d.require( count( VaultBossElemental.class ) == 0, "the vault boss already stood in the final room" );
					RoomVisit.walk( d, door, 20 );
					d.waitFor( () -> count( VaultBossElemental.class ) > 0, 6 );
					d.require( count( VaultBossElemental.class ) > 0, "walking at the final room's locked door woke no boss" );
					VaultBossElemental boss = d.world().find( VaultBossElemental.class );
					VaultBossElemental.ElementalForm form = boss.curForm();
					int hp = boss.HP;
					PrisonScenarios.chase( d, boss, () -> boss.curForm() != form || !boss.isAlive(), 100 );
					d.expect( boss.curForm() != form, "100 steps left the vault boss in " + boss.curForm() + " at "
							+ boss.HP + " of " + hp );
					d.expect( boss.HP < hp, "the vault boss changed form without losing HP" );
					d.expect( d.env().running(), "the fight ended the episode: " + d.env().endReason() );
				} );
		scenario.maxSteps = 260;
		return scenario.depth( BOSS_DEPTH ).branch( 1 ).prepare( forcing( VaultFinalRoom.class ))
				.covers( VaultBossElemental.class, VaultFinalRoom.class );
	}

	private static Scenario escape(){
		return new Scenario( "vault.EscapeCrystal", "using the escape crystal leaves the vault", HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy();
					EscapeCrystal crystal = new EscapeCrystal();
					crystal.storeHeroBelongings( Dungeon.hero );
					kit.give( crystal );
					kit.give( new DwarfToken() );
				},
				d -> {
					d.fixture( Dungeon.level instanceof VaultLevel, "the jump landed on a "
							+ Dungeon.level.getClass().getSimpleName() );
					d.use( EscapeCrystal.class );
					d.require( d.env().mode() == EnvMode.MENU, "using the escape crystal asked nothing: " + d.env().mode() );
					d.require( WindowBridge.optionCount() >= 2, "the escape crystal offered "
							+ WindowBridge.optionCount() + " options" );
					d.menu( 0 );
					d.until( () -> Dungeon.branch == 0 || !(Dungeon.level instanceof VaultLevel), 8,
							() -> d.act( Action.WAIT ));
					d.expect( !(Dungeon.level instanceof VaultLevel), "confirming the escape crystal left the hero on a "
							+ Dungeon.level.getClass().getSimpleName() );
					d.expect( Dungeon.branch == 0, "the escape crystal left the hero on branch " + Dungeon.branch );
				} ).depth( 17 ).branch( 1 ).prepare( quest() ).covers( EscapeCrystal.class, VaultLevel.class );
	}

	private static Scenario statue(){
		return new Scenario( "vault.ImpStatue", "leaving the vault with the imp statue scores 4000",
				HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy();
					EscapeCrystal crystal = new EscapeCrystal();
					crystal.storeHeroBelongings( Dungeon.hero );
					kit.give( crystal );
					kit.give( new ImpStatue() );
					kit.give( new DwarfToken() );
					kit.give( new PotionOfHealing() );
				},
				d -> {
					d.fixture( Dungeon.level instanceof VaultLevel, "the jump landed on a "
							+ Dungeon.level.getClass().getSimpleName() );
					int before = Statistics.questScores[3];
					d.use( EscapeCrystal.class );
					d.require( d.env().mode() == EnvMode.MENU, "using the escape crystal asked nothing: " + d.env().mode() );
					d.require( WindowBridge.optionCount() >= 2, "the escape crystal offered "
							+ WindowBridge.optionCount() + " options" );
					d.menu( 0 );
					d.require( d.env().mode() == EnvMode.INVENTORY, "confirming the escape crystal offered "
							+ d.env().mode() );
					d.pick( PotionOfHealing.class );
					d.require( d.env().mode() == EnvMode.MENU, "offering a kept item asked nothing: " + d.env().mode() );
					d.require( WindowBridge.optionCount() >= 2, "the kept item window offered "
							+ WindowBridge.optionCount() + " options" );
					d.menu( 0 );
					d.until( () -> Dungeon.branch == 0 || !(Dungeon.level instanceof VaultLevel), 8,
							() -> d.act( Action.WAIT ));
					d.expect( !(Dungeon.level instanceof VaultLevel), "confirming the escape crystal left the hero on a "
							+ Dungeon.level.getClass().getSimpleName() );
					d.expect( Dungeon.branch == 0, "the escape crystal left the hero on branch " + Dungeon.branch );
					d.expect( Statistics.questScores[3] == before + 4000, "leaving with the imp statue scored "
							+ Statistics.questScores[3] + ", not " + before + " + 4000" );
				} ).depth( 17 ).branch( 1 ).prepare( quest() ).covers( ImpStatue.class );
	}

	private static Scenario beacon(){
		return room( com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultEntranceRoom.class, ROOM_DEPTH,
				"using a vault beacon returns the hero to the vault entrance",
				( world, kit ) -> {
					Room entrance = world.room(
							com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultEntranceRoom.class );
					int beacon = heap( entrance, heap -> holds( heap, VaultBeacon.class ));
					if (beacon < 0) throw new NoFixture( "the vault entrance held no return beacon" );
					appear( beside( beacon, entrance ));
				},
				d -> {
					Room entrance = d.world().room(
							com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.vault.VaultEntranceRoom.class );
					int beacon = heap( entrance, heap -> holds( heap, VaultBeacon.class ));
					d.require( beacon >= 0, "the vault entrance held no return beacon" );
					d.move( beacon );
					d.require( Dungeon.level.heaps.get( beacon ) == null, "stepping onto the beacon heap left it on the floor" );
					d.require( Dungeon.hero.belongings.getItem( VaultBeacon.class ) != null,
							"the vault beacon was not picked up" );
					int gate = Dungeon.level.entrance();
					d.walkTo( farFrom( gate, 6 ), 8 );
					d.require( Dungeon.hero.pos != gate, "the hero never moved away from the vault entrance" );
					d.use( VaultBeacon.class );
					d.expect( Dungeon.hero.pos == gate, "casting the vault beacon left the hero at " + Dungeon.hero.pos
							+ ", not the entrance at " + gate );
				} ).covers( VaultBeacon.class );
	}

	private static int approach( Room room, int door, int entry, int distance ){
		int best = -1;
		int closest = Integer.MAX_VALUE;
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (!room.inside( Dungeon.level.cellToPoint( cell ))) continue;
			if (Dungeon.level.distance( cell, door ) != distance) continue;
			if (!free( cell )) continue;
			int found = Dungeon.level.distance( cell, entry );
			if (found < closest){
				best = cell;
				closest = found;
			}
		}
		if (best < 0) throw new NoFixture( "no free cell " + distance + " from the locked door of the final room" );
		return best;
	}

	static int farFrom( int cell, int distance ){
		for (int other = 0; other < Dungeon.level.length(); other++){
			if (Dungeon.level.distance( other, cell ) < distance) continue;
			if (free( other )) return other;
		}
		throw new NoFixture( "no free cell " + distance + " from " + cell );
	}

	static void appear( int cell ){
		ScrollOfTeleportation.appear( Dungeon.hero, cell );
		Dungeon.observe();
	}

	static boolean free( int cell ){
		return cell >= 0 && cell < Dungeon.level.length() && Dungeon.level.insideMap( cell )
				&& Dungeon.level.passable[ cell ] && !Dungeon.level.solid[ cell ]
				&& Actor.findChar( cell ) == null && Dungeon.level.heaps.get( cell ) == null;
	}

	static int inside( Room room ){
		int centre = Dungeon.level.pointToCell( room.center() );
		if (free( centre )) return centre;
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (room.inside( Dungeon.level.cellToPoint( cell )) && free( cell )) return cell;
		}
		throw new NoFixture( "no free cell inside the " + room.getClass().getSimpleName() );
	}

	static int beside( int cell, Room room ){
		for (int offset : PathFinder.NEIGHBOURS8){
			int beside = cell + offset;
			if (room != null && !room.inside( Dungeon.level.cellToPoint( beside ))) continue;
			if (free( beside )) return beside;
		}
		throw new NoFixture( "no free cell beside " + cell + " inside the "
				+ (room == null ? "level" : room.getClass().getSimpleName()) );
	}

	static int terrain( Room room, int tile ){
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (Dungeon.level.map[ cell ] == tile && room.inside( Dungeon.level.cellToPoint( cell ))) return cell;
		}
		throw new NoFixture( "no terrain " + tile + " inside the " + room.getClass().getSimpleName() );
	}

	static int heap( Room room, Predicate< Heap > wanted ){
		int best = -1;
		int distance = Integer.MAX_VALUE;
		for (Heap heap : Dungeon.level.heaps.valueList()){
			if (!room.inside( Dungeon.level.cellToPoint( heap.pos ))) continue;
			if (!wanted.test( heap )) continue;
			int found = Dungeon.level.distance( Dungeon.hero.pos, heap.pos );
			if (found < distance){
				best = heap.pos;
				distance = found;
			}
		}
		return best;
	}

	static boolean holds( Heap heap, Class< ? > type ){
		for (com.shatteredpixel.shatteredpixeldungeon.items.Item item : heap.items) if (type.isInstance( item )) return true;
		return false;
	}

	static int tokens(){
		int count = 0;
		for (Heap heap : Dungeon.level.heaps.valueList()) if (holds( heap, DwarfToken.class )) count++;
		return count;
	}

	static int count( Class< ? extends Mob > type ){
		int count = 0;
		for (Mob mob : Dungeon.level.mobs) if (type.isInstance( mob )) count++;
		return count;
	}

	static < T extends Mob > T in( Room room, Class< T > type ){
		for (Mob mob : Dungeon.level.mobs){
			if (type.isInstance( mob ) && room.inside( Dungeon.level.cellToPoint( mob.pos ))) return type.cast( mob );
		}
		throw new NoFixture( "no " + type.getSimpleName() + " inside the " + room.getClass().getSimpleName() );
	}

	static Mob foe( Room room ){
		for (Mob mob : Dungeon.level.mobs){
			if (mob instanceof NPC || !mob.isAlive()) continue;
			if (room.inside( Dungeon.level.cellToPoint( mob.pos ))) return mob;
		}
		throw new NoFixture( "no foe inside the " + room.getClass().getSimpleName() );
	}

	static void slay( Driver d, Mob mob, int steps ){
		d.until( () -> down( mob ), steps, () -> {
			if (down( mob )) return;
			if (Dungeon.level.adjacent( Dungeon.hero.pos, mob.pos )) d.move( mob.pos );
			else RoomVisit.walk( d, mob.pos, 1 );
		});
		d.expect( down( mob ), steps + " steps left the " + mob.getClass().getSimpleName() + " at " + mob.HP );
	}

	static boolean down( Mob mob ){
		return !mob.isAlive() || !Dungeon.level.mobs.contains( mob );
	}

	static int volume( Class< ? extends com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob > type ){
		com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob blob = Dungeon.level.blobs.get( type );
		return blob == null ? 0 : blob.volume;
	}

	static int hottestTrap( Room room ){
		com.shatteredpixel.shatteredpixeldungeon.actors.blobs.VaultFlameTraps flames =
				(com.shatteredpixel.shatteredpixeldungeon.actors.blobs.VaultFlameTraps)
						Dungeon.level.blobs.get( com.shatteredpixel.shatteredpixeldungeon.actors.blobs.VaultFlameTraps.class );
		int best = -1;
		int cooldown = Integer.MAX_VALUE;
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (Dungeon.level.map[ cell ] != Terrain.INACTIVE_TRAP) continue;
			if (!room.inside( Dungeon.level.cellToPoint( cell ))) continue;
			if (!free( cell )) continue;
			int found = flames == null || flames.curCooldowns == null ? 0 : flames.curCooldowns[ cell ];
			if (found < cooldown){
				best = cell;
				cooldown = found;
			}
		}
		if (best < 0) throw new NoFixture( "no vault flame trap inside the " + room.getClass().getSimpleName() );
		return best;
	}

	static void burned( Driver d ){
		int hp = Dungeon.hero.HP;
		d.waitFor( () -> volume( com.shatteredpixel.shatteredpixeldungeon.actors.blobs.VaultFlameTraps.class ) > 0, 16 );
		d.expect( volume( com.shatteredpixel.shatteredpixeldungeon.actors.blobs.VaultFlameTraps.class ) > 0,
				"no vault flame burned in 16 turns" );
		d.waitFor( () -> Dungeon.hero.HP < hp || Dungeon.hero.buff( Burning.class ) != null, 6 );
		d.expect( Dungeon.hero.HP < hp || Dungeon.hero.buff( Burning.class ) != null,
				"the flames burned the hero for nothing" );
	}

	static boolean newDebuff( Set< Buff > before ){
		for (Buff buff : Dungeon.hero.buffs()) if (buff.type == Buff.buffType.NEGATIVE && !before.contains( buff )) return true;
		return false;
	}

	static Set< Buff > buffs(){
		return new HashSet<>( Dungeon.hero.buffs() );
	}
}
