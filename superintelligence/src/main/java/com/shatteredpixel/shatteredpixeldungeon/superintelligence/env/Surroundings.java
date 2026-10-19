package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.NPC;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;

final class Surroundings {

	private Surroundings(){}

	private static final int[] LOCKED_DOOR_TERRAINS = {
			Terrain.LOCKED_DOOR,
			Terrain.LOCKED_EXIT,
			Terrain.CRYSTAL_DOOR,
			Terrain.HERO_LKD_DR,
	};

	private static final int TARGET_COUNT = ActionMapper.TARGET_COUNT;

	static int offsetCell( int dx, int dy ){
		return Dungeon.hero.pos + dx + dy * Dungeon.level.width();
	}

	static int targetCell( int index ){
		if (index < 0) index = 0;
		if (index >= TARGET_COUNT) index = TARGET_COUNT - 1;
		int[] o = ActionMapper.TARGET_OFFSETS[ index ];
		return offsetCell( o[0], o[1] );
	}

	static boolean insideMap( int cell ){
		return cell >= 0
				&& cell < Dungeon.level.length()
				&& Dungeon.level.insideMap( cell );
	}



	static boolean interactive( int cell ){
		if (!insideMap( cell )) return false;
		if (cell == Dungeon.hero.pos) return false;

		Char ch = Actor.findChar( cell );
		if (ch instanceof Mob) return Dungeon.hero.canAttack( (Mob)ch );
		if (ch instanceof NPC) return true;
		if (Dungeon.level.heaps.get( cell ) != null) return true;
		if (Dungeon.level.getTransition( cell ) != null) return true;

		int terrain = Dungeon.level.map[ cell ];
		for (int t : LOCKED_DOOR_TERRAINS) if (terrain == t) return true;
		return terrain == Terrain.ALCHEMY;
	}


	static int pickInteractCell(){
		StringBuilder dbg = PickupTrace.enabled() ? new StringBuilder() : null;

		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (!insideMap( cell )) continue;
			Char ch = Actor.findChar( cell );
			if (dbg != null){
				dbg.append( " [o=" ).append( o ).append( " cell=" ).append( cell )
						.append( " ch=" ).append( ch == null ? "none" : ch.getClass().getSimpleName() )
						.append( " mob=" ).append( (ch instanceof Mob) )
						.append( " canAtk=" ).append( (ch instanceof Mob) && Dungeon.hero.canAttack( (Mob)ch ) )
						.append( " heap=" ).append( (Dungeon.level.heaps.get( cell ) != null)
								? (Dungeon.level.heaps.get( cell ).size() + "x" + Dungeon.level.heaps.get( cell ).type) : "no" )
						.append( " trans=" ).append( Dungeon.level.getTransition( cell ) != null )
						.append( " ]" );
			}
			if (ch instanceof Mob && Dungeon.hero.canAttack( (Mob)ch )) {
				if (dbg != null) System.err.println( "[interact] picked mob at " + cell + dbg );
				return cell;
			}
		}
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			Heap h = insideMap( cell ) ? Dungeon.level.heaps.get( cell ) : null;
			if (h != null) {
				if (PickupTrace.enabled()) PickupTrace.pickup( cell, h );
				if (dbg != null){
					System.err.println( "[interact] picked heap at " + cell + " size=" + h.size()
							+ " type=" + h.type + " peek=" + (h.peek() == null ? "null" : h.peek().getClass().getSimpleName())
							+ " seen=" + h.seen + dbg );
				}
				return cell;
			}
		}
		Heap underfoot = Dungeon.level.heaps.get( Dungeon.hero.pos );
		if (underfoot != null ){
			if (PickupTrace.enabled()) PickupTrace.pickup( Dungeon.hero.pos, underfoot );
			if (dbg != null){
				System.err.println( "[interact] picked heap under the hero at " + Dungeon.hero.pos
						+ " size=" + underfoot.size() + " type=" + underfoot.type
						+ " peek=" + (underfoot.peek() == null ? "null" : underfoot.peek().getClass().getSimpleName())
						+ " seen=" + underfoot.seen + dbg );
			}
			return Dungeon.hero.pos;
		}
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (!insideMap( cell )) continue;
			int terrain = Dungeon.level.map[ cell ];
			for (int t : LOCKED_DOOR_TERRAINS) if (terrain == t) {
				if (dbg != null) System.err.println( "[interact] picked door at " + cell + dbg );
				return cell;
			}
		}
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (insideMap( cell ) && Dungeon.level.getTransition( cell ) != null ) {
				if (dbg != null) System.err.println( "[interact] picked transition at " + cell + dbg );
				return cell;
			}
		}
		if (dbg != null) System.err.println( "[interact] found nothing" + dbg );
		return Dungeon.hero.pos;
	}

	static boolean moveLegal( Action action ){

		int cell = offsetCell( action.dx, action.dy );
		if (!insideMap( cell )) return false;

		Char ch = Actor.findChar( cell );
		if (ch instanceof Mob) return Dungeon.hero.canAttack( (Mob)ch );
		if (Dungeon.level.heaps.get( cell ) != null) return true;
		return Dungeon.level.passable[ cell ];
	}

	static boolean adjacentMob(){
		Hero hero = Dungeon.hero;
		for (Mob mob : hero.getVisibleEnemies()){
			if (mob.isAlive() && Dungeon.level.adjacent( hero.pos, mob.pos )) return true;
		}
		return false;
	}

	static boolean adjacentHeap(){
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (insideMap( cell ) && Dungeon.level.heaps.get( cell ) != null ) return true;
		}
		return false;
	}

	static boolean adjacentDoor(){
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (!insideMap( cell )) continue;
			int terrain = Dungeon.level.map[ cell ];
			for (int t : LOCKED_DOOR_TERRAINS) if (terrain == t) return true;
		}
		return false;
	}

	static boolean adjacentTransition(){
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (insideMap( cell ) && Dungeon.level.getTransition( cell ) != null ) return true;
		}
		return false;
	}

	static boolean adjacentNpc(){
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (!insideMap( cell )) continue;
			if (Actor.findChar( cell ) instanceof NPC ) return true;
		}
		return false;
	}

	static boolean adjacentAlchemy(){
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (insideMap( cell ) && Dungeon.level.map[ cell ] == Terrain.ALCHEMY ) return true;
		}
		return false;
	}

	static boolean adjacentForSale(){
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (!insideMap( cell )) continue;
			Heap heap = Dungeon.level.heaps.get( cell );
			if (heap != null && heap.type == Heap.Type.FOR_SALE ) return true;
		}
		return false;
	}
}
