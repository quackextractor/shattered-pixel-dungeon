package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.CrystalKey;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.IronKey;
import com.shatteredpixel.shatteredpixeldungeon.items.keys.Key;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SpecialRoom;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class RoomVisit {

	private RoomVisit(){}

	static boolean inside( SpecialRoom room, int cell ){
		return room.inside( Dungeon.level.cellToPoint( cell ));
	}

	static int door( SpecialRoom room ){
		return Dungeon.level.pointToCell( room.entrance() );
	}

	static int insideDoor( SpecialRoom room ){
		for (int offset : PathFinder.NEIGHBOURS4) if (inside( room, door( room ) + offset )) return door( room ) + offset;
		throw new NoFixture( "the " + room.getClass().getSimpleName() + " door opens onto nothing inside it" );
	}

	private static int outsideDoor( SpecialRoom room ){
		Level level = Dungeon.level;
		for (int offset : PathFinder.NEIGHBOURS4){
			int cell = door( room ) + offset;
			if (!inside( room, cell ) && level.passable[ cell ] && !level.avoid[ cell ] && Actor.findChar( cell ) == null) return cell;
		}
		throw new NoFixture( "no free cell outside the " + room.getClass().getSimpleName() + " door" );
	}

	static void atDoor( World world, SpecialRoom room ){
		List< Mob > keep = new ArrayList<>();
		for (Mob mob : world.level().mobs) if (inside( room, mob.pos )) keep.add( mob );
		world.clearMobs( keep.toArray( new Mob[ 0 ] ));
		int door = door( room );
		int terrain = world.level().map[ door ];
		if (terrain == Terrain.LOCKED_DOOR) Notes.add( new IronKey( Dungeon.depth ));
		if (terrain == Terrain.CRYSTAL_DOOR) Notes.add( new CrystalKey( Dungeon.depth ));
		ScrollOfTeleportation.appear( Dungeon.hero, terrain == Terrain.BARRICADE ? insideDoor( room ) : outsideDoor( room ));
		Dungeon.observe();
	}

	static void enter( Driver d, SpecialRoom room ){
		int door = door( room );
		int inside = insideDoor( room );
		if (Dungeon.hero.pos == inside) return;
		int terrain = Dungeon.level.map[ door ];
		if (terrain == Terrain.LOCKED_DOOR || terrain == Terrain.CRYSTAL_DOOR){
			d.move( door );
			d.waitFor( () -> Dungeon.level.map[ door ] != terrain, 3 );
			d.require( Dungeon.level.map[ door ] != terrain, "the key did not open the " + room.getClass().getSimpleName() + " door" );
		}
		d.until( () -> Dungeon.hero.pos == door, 3, () -> d.move( door ));
		d.until( () -> Dungeon.hero.pos == inside, 3, () -> d.move( inside ));
		d.require( Dungeon.hero.pos == inside, "the hero did not step inside the " + room.getClass().getSimpleName() );
	}

	static boolean[] walkable( int target ){
		Level level = Dungeon.level;
		boolean[] open = new boolean[ level.length() ];
		for (int i = 0; i < open.length; i++) open[ i ] = level.passable[ i ] && !level.avoid[ i ] && level.heaps.get( i ) == null;
		open[ target ] = true;
		return open;
	}

	static void walk( Driver d, int cell, int max ){
		for (int i = 0; i < max && d.env().running() && Dungeon.hero.pos != cell && !Dungeon.level.adjacent( Dungeon.hero.pos, cell ); i++){
			int next = PathFinder.getStep( Dungeon.hero.pos, cell, walkable( cell ));
			d.require( next >= 0, "no path from " + Dungeon.hero.pos + " to " + cell );
			d.move( next );
		}
	}

	static int nearest( List< Integer > cells, String what ){
		int best = -1;
		int bestDistance = Integer.MAX_VALUE;
		for (int cell : cells){
			int distance = distance( cell );
			if (distance < bestDistance){
				best = cell;
				bestDistance = distance;
			}
		}
		if (best < 0) throw new Driver.Stop( "no reachable " + what );
		return best;
	}

	private static int distance( int cell ){
		PathFinder.buildDistanceMap( cell, walkable( cell ));
		return PathFinder.distance[ Dungeon.hero.pos ];
	}

	static boolean reachable( int cell ){
		return distance( cell ) < Integer.MAX_VALUE;
	}

	static int heap( SpecialRoom room, Predicate< Heap > wanted, String what ){
		List< Integer > cells = new ArrayList<>();
		for (Heap heap : Dungeon.level.heaps.valueList()){
			if (inside( room, heap.pos ) && wanted.test( heap )) cells.add( heap.pos );
		}
		return nearest( cells, what );
	}

	static int terrain( SpecialRoom room, int terrain ){
		List< Integer > cells = new ArrayList<>();
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (Dungeon.level.map[ cell ] == terrain && inside( room, cell )) cells.add( cell );
		}
		return nearest( cells, "terrain " + terrain + " in the " + room.getClass().getSimpleName() );
	}

	static < T extends Mob > T mob( SpecialRoom room, Class< T > type ){
		for (Mob mob : Dungeon.level.mobs) if (type.isInstance( mob ) && inside( room, mob.pos )) return type.cast( mob );
		throw new Driver.Stop( "no " + type.getSimpleName() + " in the " + room.getClass().getSimpleName() );
	}

	static Mob besideHero( Class< ? extends Mob > type ){
		for (Mob mob : Dungeon.level.mobs){
			if (type.isInstance( mob ) && mob.isAlive() && Dungeon.level.adjacent( mob.pos, Dungeon.hero.pos )) return mob;
		}
		return null;
	}

	static int held( Item item ){
		if (item instanceof Gold) return Dungeon.gold;
		if (item instanceof Key) return Notes.keyCount( (Key) item );
		int count = 0;
		for (Item carried : Dungeon.hero.belongings) if (carried.getClass() == item.getClass()) count += carried.quantity();
		return count;
	}

	static boolean take( Driver d, int cell ){
		Item item = Dungeon.level.heaps.get( cell ).peek();
		int before = held( item );
		walk( d, cell, 40 );
		return d.until( () -> held( item ) > before, 8, () -> {
			if (Dungeon.hero.pos == cell) d.act( Action.TRAVEL_ITEM );
			else d.move( cell );
		});
	}
}
