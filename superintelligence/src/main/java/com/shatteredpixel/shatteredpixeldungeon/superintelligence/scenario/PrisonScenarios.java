package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Bandit;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Elemental;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Necromancer;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.RotHeart;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.RotLasher;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Skeleton;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.SpectralNecromancer;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Tengu;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Thief;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.TormentedSpirit;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Wraith;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.CeremonialCandle;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.CorpseDust;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.Embers;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRemoveCurse;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.PrisonBossLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.PrisonLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.RegularLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.MassGraveRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.RitualSiteRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.quest.RotGardenRoom;
import com.shatteredpixel.shatteredpixeldungeon.plants.Rotberry;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class PrisonScenarios {

	private PrisonScenarios(){}

	private static final int QUEST_DEPTH = 9;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( FoeFamily.fight( "prison.Bandit", Bandit.class, 1, 12, PrisonScenarios::robbedAndBlinded,
				"the Bandit never stole from the hero and blinded him", ( world, kit ) -> kit.emptyPack().give( new Food() )).depth( 7 ));
		all.add( FoeFamily.fight( "prison.Skeleton", Skeleton.class, 1, 12, null, "the Skeleton never hurt the hero" ).depth( 7 ));
		all.add( FoeFamily.fight( "prison.Wraith", Wraith.class, 1, 12, null, "the Wraith never hurt the hero" ).depth( 7 ));
		all.add( FoeFamily.fight( "prison.SpectralNecromancer", SpectralNecromancer.class, 1, 12,
				() -> MobScenarios.count( Wraith.class ) > 0, "the Spectral Necromancer raised no Wraith" ).depth( 7 ));
		all.add( necroSkeleton().covers( Necromancer.NecroSkeleton.class ));
		all.add( tormentedSpirit().covers( TormentedSpirit.class ));
		all.add( massGrave().covers( MassGraveRoom.class, CorpseDust.class, CorpseDust.DustWraith.class ));
		all.add( ritualSite().covers( RitualSiteRoom.class, CeremonialCandle.class, Elemental.NewbornFireElemental.class, Embers.class ));
		all.add( rotGarden().covers( RotGardenRoom.class, RotLasher.class, RotHeart.class ));
		all.add( descend().covers( PrisonLevel.class ));
		all.add( tengu().covers( Tengu.class, PrisonBossLevel.class ));
		return all;
	}

	private static boolean robbedAndBlinded(){
		for (Mob mob : Dungeon.level.mobs){
			if (mob instanceof Bandit && ((Thief) mob).item != null) return Dungeon.hero.buff( Blindness.class ) != null;
		}
		return false;
	}

	private static Scenario necroSkeleton(){
		return new Scenario( "prison.NecroSkeleton", "a Necromancer raises a skeleton that fights the hero", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.hero().belongings.weapon.level( 10 );
					world.hunting( new Necromancer(), world.cellAtRange( 4, true ));
				},
				d -> {
					d.require( MobScenarios.count( Necromancer.NecroSkeleton.class ) == 0, "a skeleton stood before the Necromancer acted" );
					d.waitFor( () -> MobScenarios.count( Necromancer.NecroSkeleton.class ) > 0, 10 );
					d.require( MobScenarios.count( Necromancer.NecroSkeleton.class ) > 0, "the Necromancer raised no skeleton in 10 turns" );
					FoeFamily.brawl( d, Necromancer.NecroSkeleton.class, 12, null, "the raised skeleton never hurt the hero" );
				} ).depth( 7 );
	}

	private static Scenario tormentedSpirit(){
		return new Scenario( "prison.TormentedSpirit", "a tormented spirit hit and then freed by remove curse", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new ScrollOfRemoveCurse() );
					Mob spirit = world.hunting( new TormentedSpirit(), world.adjacentFree() );
					spirit.HP = spirit.HT = FoeFamily.MIN_HP;
				},
				d -> {
					TormentedSpirit spirit = d.world().find( TormentedSpirit.class );
					int hp = spirit.HP;
					d.until( () -> spirit.HP < hp, 12, () -> {
						if (Dungeon.level.adjacent( Dungeon.hero.pos, spirit.pos )) d.move( spirit.pos );
						else d.act( Action.WAIT );
					});
					d.expect( spirit.HP < hp, "12 steps beside the tormented spirit left it at " + spirit.HP );
					d.require( spirit.isAlive() && Dungeon.level.adjacent( Dungeon.hero.pos, spirit.pos ), "the spirit is not alive beside the hero" );
					int at = spirit.pos;
					d.require( Dungeon.level.heaps.get( at ) == null, "a heap lay under the spirit before the scroll" );
					d.use( ScrollOfRemoveCurse.class );
					d.expect( !Dungeon.level.mobs.contains( spirit ), "reading remove curse beside the spirit did not free it" );
					d.expect( Dungeon.level.heaps.get( at ) != null, "the freed spirit left no reward" );
					d.expect( Dungeon.hero.belongings.getItem( ScrollOfRemoveCurse.class ) == null, "the scroll was not used up" );
				} ).depth( 7 );
	}

	private static Room questRoom( Class< ? extends Room > type ){
		if (Dungeon.level instanceof RegularLevel){
			for (Room room : ((RegularLevel) Dungeon.level).rooms()) if (type.isInstance( room )) return room;
		}
		throw new NoFixture( "floor " + Dungeon.depth + " has no " + type.getSimpleName() );
	}

	private static Scenario massGrave(){
		return new Scenario( "prison.MassGrave", "corpse dust taken from the mass grave raises dust wraiths", HeroClass.WARRIOR,
				( world, kit ) -> {
					questRoom( MassGraveRoom.class );
					Heap grave = heapOf( CorpseDust.class );
					if (grave == null) throw new NoFixture( "the mass grave holds no corpse dust" );
					world.clearMobs();
					kit.sturdy();
					kit.give( new WandOfMagicMissile() ).curCharges = 2;
					grave.type = Heap.Type.HEAP;
					grave.haunted = false;
					ScrollOfTeleportation.appear( Dungeon.hero, besideFree( world, grave.pos ));
				},
				d -> {
					d.require( MobScenarios.count( CorpseDust.DustWraith.class ) == 0, "a dust wraith stood before the dust was taken" );
					int heroHp = Dungeon.hero.HP;
					d.move( heapOf( CorpseDust.class ).pos );
					d.require( Dungeon.hero.belongings.getItem( CorpseDust.class ) != null, "stepping onto the grave did not pick up the dust" );
					d.expect( Dungeon.hero.buff( CorpseDust.DustGhostSpawner.class ) != null, "the corpse dust laid no haunting on the hero" );
					d.waitFor( () -> MobScenarios.count( CorpseDust.DustWraith.class ) > 0, 6 );
					d.require( MobScenarios.count( CorpseDust.DustWraith.class ) > 0, "the corpse dust raised no dust wraith in 6 turns" );
					d.waitFor( () -> Dungeon.hero.HP < heroHp, 10 );
					d.expect( Dungeon.hero.HP < heroHp, "the dust wraith never hurt the hero" );
					List< Mob > wraiths = new ArrayList<>();
					for (Mob mob : Dungeon.level.mobs) if (mob instanceof CorpseDust.DustWraith) wraiths.add( mob );
					int[] hp = new int[ wraiths.size() ];
					for (int i = 0; i < hp.length; i++) hp[ i ] = wraiths.get( i ).HP;
					d.use( WandOfMagicMissile.class );
					d.aimNearest();
					boolean hurt = false;
					for (int i = 0; i < hp.length; i++) hurt |= !wraiths.get( i ).isAlive() || wraiths.get( i ).HP < hp[ i ];
					d.expect( hurt, "the zap left every dust wraith unhurt" );
				} ).depth( QUEST_DEPTH ).seeds( "SCN-A", "SCN-B", "SCN-C", "SCN-D", "SCN-E", "SCN-F", "SCN-G", "SCN-H" );
	}

	private static Scenario ritualSite(){
		return new Scenario( "prison.RitualSite", "four candles round the ritual site summon a newborn fire elemental", HeroClass.WARRIOR,
				( world, kit ) -> {
					questRoom( RitualSiteRoom.class );
					world.clearMobs();
					kit.sturdy();
					kit.hero().belongings.weapon.level( 10 );
					kit.give( new CeremonialCandle() ).quantity( 4 );
					int ritual = CeremonialCandle.ritualPos;
					for (int cell : candleCells( ritual )) if (!world.free( cell )) throw new NoFixture( "a candle spot is blocked" );
					ScrollOfTeleportation.appear( Dungeon.hero, ritual );
				},
				d -> {
					int heroHp = Dungeon.hero.HP;
					java.util.Set< Buff > buffs = new java.util.HashSet<>( Dungeon.hero.buffs() );
					d.require( MobScenarios.count( Elemental.NewbornFireElemental.class ) == 0, "an elemental stood before the ritual" );
					d.require( heapOf( Embers.class ) == null, "embers lay on the floor before the ritual" );
					for (int cell : candleCells( CeremonialCandle.ritualPos )){
						d.throwItem( CeremonialCandle.class );
						d.step( Action.USE, d.direction( cell ));
					}
					d.require( MobScenarios.count( Elemental.NewbornFireElemental.class ) > 0, "four candles round the ritual site summoned nothing" );
					d.expect( Dungeon.hero.belongings.getItem( CeremonialCandle.class ) == null, "candles stayed in the pack" );
					Elemental.NewbornFireElemental elemental = d.world().find( Elemental.NewbornFireElemental.class );
					d.waitFor( () -> Dungeon.hero.HP < heroHp || newDebuff( buffs ), 8 );
					d.expect( Dungeon.hero.HP < heroHp || newDebuff( buffs ), "the newborn elemental never hurt the hero" );
					chase( d, elemental, () -> !elemental.isAlive(), 24 );
					d.expect( !elemental.isAlive(), "24 steps did not kill the newborn elemental; it has " + elemental.HP );
					d.expect( heapOf( Embers.class ) != null, "the newborn elemental dropped no embers" );
				} ).depth( QUEST_DEPTH ).seeds( "SCN-A", "SCN-B", "SCN-C", "SCN-D", "SCN-E", "SCN-F", "SCN-G", "SCN-H" );
	}

	private static int[] candleCells( int ritual ){
		int width = Dungeon.level.width();
		return new int[]{ ritual - width, ritual + 1, ritual + width, ritual - 1 };
	}

	private static Scenario rotGarden(){
		return new Scenario( "prison.RotGarden", "fight a rot lasher, then kill the rot heart and its lashers die", HeroClass.WARRIOR,
				( world, kit ) -> {
					questRoom( RotGardenRoom.class );
					RotHeart heart = world.find( RotHeart.class );
					world.clearMobs( heart );
					kit.sturdy();
					ScrollOfTeleportation.appear( Dungeon.hero, besideFree( world, heart.pos ));
					world.hunting( new RotLasher(), world.adjacentFree() );
				},
				d -> {
					RotLasher lasher = d.world().find( RotLasher.class );
					FoeFamily.brawl( d, RotLasher.class, 12, null, "the rot lasher never hurt the hero" );
					RotHeart heart = d.world().find( RotHeart.class );
					d.require( heapOf( Rotberry.Seed.class ) == null, "a rotberry seed lay on the floor before the heart died" );
					d.require( lasher.isAlive(), "the rot lasher died before the heart" );
					d.until( () -> !heart.isAlive(), 60, () -> d.move( heart.pos ));
					d.expect( !heart.isAlive(), "60 attacks left the rot heart at " + heart.HP );
					d.expect( heapOf( Rotberry.Seed.class ) != null, "the rot heart dropped no rotberry seed" );
					d.expect( !lasher.isAlive(), "the rot lasher outlived its heart" );
				} ).depth( QUEST_DEPTH ).seeds( "SCN-A", "SCN-B", "SCN-C", "SCN-D", "SCN-E", "SCN-F", "SCN-G", "SCN-H" );
	}

	private static Scenario descend(){
		return new Scenario( "prison.descend", "take the stairs down from a prison floor", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Arrays.fill( world.level().mapped, true );
				},
				d -> {
					d.require( Dungeon.level instanceof PrisonLevel, "depth 7 is a " + Dungeon.level.getClass().getSimpleName() );
					d.fixture( d.env().actionMask()[ Action.TRAVEL_STAIRS.index ] > 0.5f, "no known path to the exit" );
					d.until( () -> Dungeon.depth == 8, 100, () -> d.act( Action.TRAVEL_STAIRS ));
					d.expect( Dungeon.depth == 8, "100 TRAVEL_STAIRS steps from depth 7 left the hero on depth " + Dungeon.depth );
				} ).depth( 7 );
	}

	private static Scenario tengu(){
		return new Scenario( "prison.tengu", "unlock Tengu's cell, walk in and hit Tengu", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					int door = lockedDoor();
					if (door < 0) throw new NoFixture( "floor 10 has no locked cell door" );
					Notes.add( new IronKey( Dungeon.depth ));
					ScrollOfTeleportation.appear( Dungeon.hero, door - world.level().width() );
				},
				d -> {
					int door = lockedDoor();
					int width = Dungeon.level.width();
					d.move( door );
					d.waitFor( () -> Dungeon.level.map[ door ] != Terrain.LOCKED_DOOR, 3 );
					d.require( Dungeon.level.map[ door ] != Terrain.LOCKED_DOOR, "the iron key did not open Tengu's cell" );
					d.move( door );
					d.move( door + width );
					Tengu tengu = d.world().find( Tengu.class );
					int hp = tengu.HP;
					int heroHp = Dungeon.hero.HP;
					java.util.Set< Buff > buffs = new java.util.HashSet<>( Dungeon.hero.buffs() );
					boolean[] hit = { false };
					chase( d, tengu, () -> (hit[ 0 ] |= Dungeon.hero.HP < heroHp || newDebuff( buffs )) && tengu.HP < hp, 40 );
					d.expect( tengu.HP < hp, "40 steps after Tengu woke left him at " + tengu.HP + " of " + hp );
					d.expect( hit[ 0 ], "Tengu never hurt or debuffed the hero in 40 steps" );
					d.expect( d.env().running(), "the fight ended the episode: " + d.env().endReason() );
				} ).depth( 10 );
	}

	private static void chase( Driver d, Mob mob, java.util.function.BooleanSupplier done, int max ){
		d.until( done, max, () -> {
			if (Dungeon.level.adjacent( Dungeon.hero.pos, mob.pos )){
				d.move( mob.pos );
				return;
			}
			boolean[] open = new boolean[ Dungeon.level.length() ];
			for (int i = 0; i < open.length; i++) open[ i ] = Dungeon.level.passable[ i ];
			int next = PathFinder.getStep( Dungeon.hero.pos, mob.pos, open );
			if (next < 0) d.act( Action.WAIT );
			else d.move( next );
		});
	}

	private static int lockedDoor(){
		for (int cell = 0; cell < Dungeon.level.length(); cell++) if (Dungeon.level.map[ cell ] == Terrain.LOCKED_DOOR) return cell;
		return -1;
	}

	private static Heap heapOf( Class< ? extends Item > type ){
		for (Heap heap : Dungeon.level.heaps.valueList()) for (Item item : heap.items) if (type.isInstance( item )) return heap;
		return null;
	}

	private static int besideFree( World world, int cell ){
		for (int offset : PathFinder.NEIGHBOURS8) if (world.free( cell + offset )) return cell + offset;
		throw new NoFixture( "no free cell beside " + cell );
	}

	private static boolean newDebuff( java.util.Set< Buff > before ){
		for (Buff buff : Dungeon.hero.buffs()) if (buff.type == Buff.buffType.NEGATIVE && !before.contains( buff )) return true;
		return false;
	}
}
