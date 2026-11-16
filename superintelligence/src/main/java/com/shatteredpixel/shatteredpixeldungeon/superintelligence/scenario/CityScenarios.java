package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DwarfKing;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Elemental;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Monk;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Senior;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Imp;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.ImpShopkeeper;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfMindVision;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.DwarfToken;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfMight;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.levels.CityBossLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.CityLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.AmbitiousImpRoom;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;
import com.watabou.utils.Bundle;
import com.watabou.utils.PathFinder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class CityScenarios {

	private CityScenarios(){}

	private static final int CITY_DEPTH = 17;

	private static final int BOSS_DEPTH = 20;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( monk().covers( Monk.class ));
		all.add( FoeFamily.fight( "city.Senior", Senior.class, 1, 16, null, "the Senior never hurt the hero" ).depth( CITY_DEPTH ));
		all.add( chaosElemental().covers( Elemental.ChaosElemental.class ));
		all.add( kingMinions().covers( DwarfKing.DKGhoul.class, DwarfKing.DKMonk.class, DwarfKing.DKWarlock.class,
				DwarfKing.DKGolem.class ));
		all.add( dwarfKing().covers( DwarfKing.class, CityBossLevel.class ));
		all.add( impQuest().covers( DwarfToken.class ));
		all.add( ambitiousImpRoom().covers( AmbitiousImpRoom.class ));
		all.add( impShopkeeper().covers( ImpShopkeeper.class ));
		all.add( descend().covers( CityLevel.class ));
		return all;
	}

	private static Scenario monk(){
		return new Scenario( "city.Monk", "a Monk parries the first attack with its focus, then loses HP to the next", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().level( 20 ).strength( 20 );
					kit.hero().belongings.weapon.level( 20 );
					Monk monk = world.hunting( new Monk(), world.adjacentFree() );
					monk.HP = monk.HT = FoeFamily.MIN_HP;
				},
				d -> {
					Monk monk = d.world().find( Monk.class );
					d.require( monk.buff( Monk.Focus.class ) == null, "the Monk already held focus before its first turn" );
					d.waitFor( () -> monk.buff( Monk.Focus.class ) != null, 6 );
					d.require( monk.buff( Monk.Focus.class ) != null, "the Monk never focused in 6 turns" );
					d.until( () -> Dungeon.level.adjacent( Dungeon.hero.pos, monk.pos ), 8, () -> d.act( Action.WAIT ));
					d.require( Dungeon.level.adjacent( Dungeon.hero.pos, monk.pos ), "the Monk never came within reach" );
					int hp = monk.HP;
					d.move( monk.pos );
					boolean parried = monk.buff( Monk.Focus.class ) == null && monk.HP == hp;
					d.expect( parried, "the focused attack cost the Monk HP or left it focused: " + hp + " to " + monk.HP );
					d.until( () -> monk.HP < hp, 16, () -> strike( d, monk ));
					d.expect( monk.HP < hp, "16 blows after the parry left the Monk at " + monk.HP + " of " + hp );
				} ).depth( CITY_DEPTH );
	}

	private static Scenario chaosElemental(){
		return FoeFamily.armedFight( "city.ChaosElemental", Elemental.ChaosElemental.class, 4, 16,
				() -> {
					int hp = Dungeon.hero.HP;
					int pos = Dungeon.hero.pos;
					int buffs = Dungeon.hero.buffs().size();
					int gold = Dungeon.gold;
					return () -> Dungeon.hero.HP < hp || Dungeon.hero.pos != pos || Dungeon.hero.buffs().size() != buffs
							|| Dungeon.gold != gold;
				}, "16 turns of cursed zaps changed nothing about the hero", ( world, kit ) -> {} ).depth( CITY_DEPTH );
	}

	private static Scenario kingMinions(){
		return new Scenario( "city.king-minions", "the Dwarf King's summons bring one of each minion and the hero fights one of each",
				HeroClass.WARRIOR,
				( world, kit ) -> {
					enterArena( world, kit );
					DwarfKing king = world.find( DwarfKing.class );
					force( king, "phase", 2 );
					force( king, "summonsMade", 20 );
					Buff.affect( king, DwarfKing.DKBarrior.class ).setShield( king.HT );
					int[] cells = around( world, 4 );
					summon( king, DwarfKing.DKGhoul.class, cells[ 0 ] );
					summon( king, DwarfKing.DKMonk.class, cells[ 1 ] );
					summon( king, DwarfKing.DKWarlock.class, cells[ 2 ] );
					summon( king, DwarfKing.DKGolem.class, cells[ 3 ] );
				},
				d -> {
					d.require( RoomScenarios.count( DwarfKing.DKGhoul.class ) == 0
							&& RoomScenarios.count( DwarfKing.DKMonk.class ) == 0
							&& RoomScenarios.count( DwarfKing.DKWarlock.class ) == 0
							&& RoomScenarios.count( DwarfKing.DKGolem.class ) == 0,
							"a minion stood on the floor before the king summoned one" );
					d.waitFor( CityScenarios::summoned, 12 );
					d.require( summoned(), "the summons arrived as ghoul " + RoomScenarios.count( DwarfKing.DKGhoul.class )
							+ ", monk " + RoomScenarios.count( DwarfKing.DKMonk.class )
							+ ", warlock " + RoomScenarios.count( DwarfKing.DKWarlock.class )
							+ ", golem " + RoomScenarios.count( DwarfKing.DKGolem.class ));
					fight( d, DwarfKing.DKWarlock.class, "the king's warlock never hurt the hero" );
					fight( d, DwarfKing.DKMonk.class, "the king's monk never hurt the hero" );
					fight( d, DwarfKing.DKGhoul.class, "the king's ghoul never hurt the hero" );
					fight( d, DwarfKing.DKGolem.class, "the king's golem never hurt the hero" );
				} ).depth( BOSS_DEPTH );
	}

	private static void summon( DwarfKing king, Class< ? extends Mob > type, int cell ){
		DwarfKing.Summoning summoning = new DwarfKing.Summoning();
		set( summoning, "pos", cell );
		set( summoning, "summon", type );
		set( summoning, "delay", 1 );
		summoning.attachTo( king );
	}

	private static int[] around( World world, int count ){
		int[] cells = new int[ count ];
		int found = 0;
		for (int offset : PathFinder.NEIGHBOURS8){
			if (found < count && world.free( Dungeon.hero.pos + offset )) cells[ found++ ] = Dungeon.hero.pos + offset;
		}
		if (found < count) throw new NoFixture( "only " + found + " free cells around the hero, not " + count );
		return cells;
	}

	private static boolean summoned(){
		return RoomScenarios.count( DwarfKing.DKGhoul.class ) > 0 && RoomScenarios.count( DwarfKing.DKMonk.class ) > 0
				&& RoomScenarios.count( DwarfKing.DKWarlock.class ) > 0 && RoomScenarios.count( DwarfKing.DKGolem.class ) > 0;
	}

	private static void fight( Driver d, Class< ? extends Mob > type, String failure ){
		Mob mob = among( type );
		int heroHp = Dungeon.hero.HP;
		int mobHp = mob.HP;
		Set< Buff > buffs = new HashSet<>( Dungeon.hero.buffs() );
		boolean hurt = false;
		boolean effect = false;
		for (int i = 0; i < 16 && d.env().running() && !(hurt && effect); i++){
			if (Dungeon.level.adjacent( Dungeon.hero.pos, mob.pos )) d.move( mob.pos );
			else {
				int next = PathFinder.getStep( Dungeon.hero.pos, mob.pos, open( mob.pos ));
				if (next < 0) d.act( Action.WAIT );
				else d.move( next );
			}
			hurt |= !mob.isAlive() || mob.HP < mobHp;
			effect |= Dungeon.hero.HP < heroHp || RoomScenarios.newDebuff( buffs );
		}
		d.expect( hurt, "the " + type.getSimpleName() + " took no damage in 16 steps at distance "
				+ Dungeon.level.distance( Dungeon.hero.pos, mob.pos ) + ", hp " + mob.HP + " of " + mobHp );
		d.expect( effect, failure );
	}

	private static boolean[] open( int target ){
		boolean[] open = RoomVisit.walkable( target );
		for (Mob mob : Dungeon.level.mobs) if (mob instanceof DwarfKing) open[ mob.pos ] = false;
		open[ target ] = true;
		return open;
	}

	private static Mob among( Class< ? extends Mob > type ){
		for (Mob mob : Dungeon.level.mobs) if (type.isInstance( mob )) return mob;
		throw new Driver.Stop( "no " + type.getSimpleName() + " among " + Dungeon.level.mobs );
	}

	private static void sturdyHero( World world, HeroKit kit ){
		arm( world, kit );
		kit.sturdy();
	}

	private static void arm( World world, HeroKit kit ){
		world.clearMobs();
		kit.level( 20 ).strength( 20 );
		kit.hero().belongings.weapon.level( 20 );
		set( Dungeon.hero, "attackSkill", 45 );
		set( Dungeon.hero, "defenseSkill", 45 );
	}

	private static void enterArena( World world, HeroKit kit ){
		arm( world, kit );
		ScrollOfTeleportation.appear( Dungeon.hero, arenaCell( world ));
		Dungeon.hero.HTBoost = 5000;
		Dungeon.hero.updateHT( true );
		Dungeon.hero.HP = Dungeon.hero.HT;
	}

	private static int arenaCell( World world ){
		int below = CityBossLevel.throne + 3 * world.level().width();
		if (world.free( below )) return below;
		below = CityBossLevel.throne + world.level().width();
		if (world.free( below )) return below;
		for (int offset : PathFinder.NEIGHBOURS8){
			if (world.free( CityBossLevel.throne + offset )) return CityBossLevel.throne + offset;
		}
		throw new NoFixture( "no free cell beside the Dwarf King's throne" );
	}

	private static void force( DwarfKing king, String field, int value ){
		set( king, field, value );
	}

	private static void set( Object target, String field, Object value ){
		try {
			Field found = target.getClass().getDeclaredField( field );
			found.setAccessible( true );
			found.set( target, value );
		} catch (ReflectiveOperationException e){
			throw new IllegalStateException( target.getClass().getSimpleName() + "." + field + " is out of reach", e );
		}
	}

	private static Scenario dwarfKing(){
		return new Scenario( "city.dwarf-king", "hit the Dwarf King beside its throne", HeroClass.WARRIOR,
				( world, kit ) -> {
					enterArena( world, kit );
				},
				d -> {
					d.require( Dungeon.level instanceof CityBossLevel, "depth 20 is a "
							+ Dungeon.level.getClass().getSimpleName() );
					DwarfKing king = d.world().find( DwarfKing.class );
					int hp = king.HP;
					d.until( () -> !king.isAlive() || king.HP < hp, 20, () -> d.move( king.pos ));
					d.expect( !king.isAlive() || king.HP < hp, "20 blows left the Dwarf King at " + king.HP + " of " + hp );
					d.expect( d.env().running(), "the fight ended the episode: " + d.env().endReason() );
				} ).depth( BOSS_DEPTH );
	}

	private static Scenario impQuest(){
		return new Scenario( "city.imp-quest", "take a dwarf token off a fallen Monk, then hand five to the Imp", HeroClass.WARRIOR,
				( world, kit ) -> {
					sturdyHero( world, kit );
					quest( true, true, true, false, new RingOfMight().level( 3 ));
					kit.give( new DwarfToken() ).quantity( 4 );
					world.mob( new Imp(), world.adjacentFree() );
					Monk monk = world.hunting( new Monk(), world.adjacentFree() );
					monk.HP = monk.HT = FoeFamily.MIN_HP;
				},
				d -> {
					d.require( tokens() == 4, "the pack holds " + tokens() + " dwarf tokens, not the four planted" );
					Monk monk = d.world().find( Monk.class );
					d.until( () -> !monk.isAlive(), 30, () -> strike( d, monk ));
					d.require( !monk.isAlive(), "30 blows left the Monk at " + monk.HP );
					Heap token = heapOf( DwarfToken.class );
					d.require( token != null, "the fallen Monk left no dwarf token" );
					d.expect( RoomVisit.take( d, token.pos ), "the dwarf token was not picked up" );
					d.require( tokens() == 5, "the pack holds " + tokens() + " dwarf tokens, not five" );
					Imp imp = d.world().find( Imp.class );
					RoomVisit.walk( d, imp.pos, 30 );
					d.move( imp.pos );
					d.require( d.env().mode() == EnvMode.MENU, "the Imp with five tokens asked nothing: " + d.env().mode() );
					d.require( WindowBridge.optionCount() == 1, "the Imp offered " + WindowBridge.optionCount() + " rewards, not one" );
					d.menu( 0 );
					d.expect( tokens() == 0, "the handed-in tokens stayed in the pack: " + tokens() );
					d.expect( Imp.Quest.isCompleted(), "handing in five tokens did not complete the Imp's quest" );
					d.expect( Dungeon.hero.belongings.getItem( RingOfMight.class ) != null, "the Imp handed over no reward" );
					d.expect( Statistics.questScores[ 3 ] == 4000, "the completed quest scored " + Statistics.questScores[ 3 ] );
					d.act( Action.WAIT );
				} ).depth( CITY_DEPTH );
	}

	private static Scenario ambitiousImpRoom(){
		return new Scenario( "city.AmbitiousImpRoom", "answer the Imp's offer of entry at the branch exit inside its room",
				HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					quest( false, false, true, false, null );
					RoomVisit.atDoor( world, world.room( AmbitiousImpRoom.class ));
				},
				d -> {
					AmbitiousImpRoom room = d.world().room( AmbitiousImpRoom.class );
					RoomVisit.enter( d, room );
					int exit = RoomVisit.terrain( room, Terrain.EXIT );
					RoomVisit.walk( d, exit, 30 );
					d.move( exit );
					d.require( d.env().mode() == EnvMode.MENU, "the branch exit asked nothing: " + d.env().mode() );
					d.menu( 0 );
					d.act( Action.WAIT );
					d.expect( Dungeon.branch == 1, "the answered offer left the hero on branch " + Dungeon.branch );
					d.expect( Dungeon.level.getClass().getSimpleName().equals( "VaultLevel" ),
							"the branch exit led to a " + Dungeon.level.getClass().getSimpleName() );
				} ).depth( 19 ).prepare( RoomForcing.special( AmbitiousImpRoom.class ));
	}

	private static Scenario impShopkeeper(){
		int[] stock = { -1 };
		return new Scenario( "city.ImpShopkeeper", "open the Imp's shop, then buy its stock once the quest is done",
				HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().gold( 2000 );
					quest( true, true, true, true, null );
					stock[ 0 ] = world.adjacentFree();
					world.drop( new PotionOfMindVision(), stock[ 0 ] ).type = Heap.Type.FOR_SALE;
					world.mob( new ImpShopkeeper(), world.adjacentFree() );
				},
				d -> {
					d.require( Imp.Quest.earnedShop(), "the Imp's shop is not open after the quest" );
					d.require( Dungeon.hero.belongings.getItem( PotionOfMindVision.class ) == null,
							"the hero already carries what the shop sells" );
					ImpShopkeeper shop = d.world().find( ImpShopkeeper.class );
					d.move( shop.pos );
					d.require( d.env().mode() == EnvMode.MENU, "the Imp asked nothing: " + d.env().mode() );
					d.require( WindowBridge.optionCount() == 2, "the Imp offered " + WindowBridge.optionCount()
							+ " options, not selling and talking" );
					d.menu( 1 );
					d.expect( d.env().mode() == EnvMode.WORLD, "the Imp's talk left the env in " + d.env().mode() );
					int gold = Dungeon.gold;
					d.move( stock[ 0 ] );
					d.require( d.env().mode() == EnvMode.MENU, "the shop's stock asked nothing: " + d.env().mode() );
					d.menu( 0 );
					d.expect( d.env().mode() == EnvMode.WORLD, "the purchase left the env in " + d.env().mode() );
					d.expect( Dungeon.gold < gold, "buying from the Imp left gold at " + Dungeon.gold + " of " + gold );
					d.expect( Dungeon.hero.belongings.getItem( PotionOfMindVision.class ) != null,
							"the bought potion is not in the pack" );
					d.expect( Dungeon.level.heaps.get( stock[ 0 ] ) == null, "the bought stock is still on the floor" );
				} ).depth( CITY_DEPTH );
	}

	private static Scenario descend(){
		return new Scenario( "city.descend", "take the stairs down from a city floor", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Arrays.fill( world.level().mapped, true );
				},
				d -> {
					d.require( Dungeon.level instanceof CityLevel, "depth " + Dungeon.depth + " is a "
							+ Dungeon.level.getClass().getSimpleName() );
					d.fixture( d.env().actionMask()[ Action.TRAVEL_STAIRS.index ] > 0.5f, "no known path to the exit" );
					d.until( () -> Dungeon.depth == 18, 100, () -> d.act( Action.TRAVEL_STAIRS ));
					d.expect( Dungeon.depth == 18, "100 TRAVEL_STAIRS steps from depth 17 left the hero on depth " + Dungeon.depth );
				} ).depth( CITY_DEPTH );
	}

	private static void quest( boolean old, boolean alternative, boolean given, boolean completed, Item reward ){
		Bundle node = new Bundle();
		node.put( "spawned", true );
		node.put( "old_quest", old );
		node.put( "alternative", alternative );
		node.put( "given", given );
		node.put( "completed", completed );
		node.put( "hazard_freebies", 2 );
		node.put( "mirror_used", false );
		node.put( "score", 0 );
		node.put( "reward_options", new ArrayList< Item >() );
		if (reward != null) node.put( "reward", reward );
		Bundle bundle = new Bundle();
		bundle.put( "demon", node );
		Imp.Quest.restoreFromBundle( bundle );
	}

	private static int tokens(){
		Item held = Dungeon.hero.belongings.getItem( DwarfToken.class );
		return held == null ? 0 : held.quantity();
	}

	private static Heap heapOf( Class< ? extends Item > type ){
		for (Heap heap : Dungeon.level.heaps.valueList()){
			for (Item item : heap.items) if (type.isInstance( item )) return heap;
		}
		return null;
	}

	private static void strike( Driver d, Mob mob ){
		if (Dungeon.level.adjacent( Dungeon.hero.pos, mob.pos )) d.move( mob.pos );
		else d.act( Action.WAIT );
	}
}
