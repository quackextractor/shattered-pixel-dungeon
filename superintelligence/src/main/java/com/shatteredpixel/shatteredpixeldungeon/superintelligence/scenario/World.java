package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;

public final class World {

	public Level level(){
		return Dungeon.level;
	}

	public int hero(){
		return Dungeon.hero.pos;
	}

	public boolean free( int cell ){
		Level level = level();
		return cell >= 0 && cell < level.length() && level.insideMap( cell ) && level.passable[ cell ]
				&& !level.solid[ cell ] && !level.pit[ cell ] && Actor.findChar( cell ) == null
				&& level.heaps.get( cell ) == null && level.traps.get( cell ) == null
				&& level.plants.get( cell ) == null && level.getTransition( cell ) == null;
	}

	public int adjacentFree(){
		for (int offset : PathFinder.NEIGHBOURS8){
			int cell = hero() + offset;
			if (free( cell )) return cell;
		}
		throw new NoFixture( "no free cell beside the hero" );
	}

	public int adjacentFreeStraight(){
		int width = level().width();
		for (int offset : new int[]{ -width, 1, width, -1 }){
			if (free( hero() + offset )) return hero() + offset;
		}
		throw new NoFixture( "no free cell straight beside the hero" );
	}

	public int beside( int terrain ){
		for (int offset : PathFinder.NEIGHBOURS8){
			if (level().map[ hero() + offset ] == terrain) return hero() + offset;
		}
		throw new Driver.Stop( "no terrain " + terrain + " beside the hero" );
	}

	public int adjacentFreeWithRoomBehind( int behind ){
		for (int offset : PathFinder.NEIGHBOURS8){
			boolean clear = true;
			for (int k = 1; k <= behind + 1 && clear; k++) clear = free( hero() + offset * k );
			if (clear) return hero() + offset;
		}
		throw new NoFixture( "no free cell beside the hero with " + behind + " clear behind it" );
	}

	public int cellAtRange( int range, boolean lineOfSight ){
		Level level = level();
		for (int cell = 0; cell < level.length(); cell++){
			if (!free( cell ) || !level.heroFOV[ cell ] || level.distance( hero(), cell ) != range) continue;
			if (lineOfSight && !clearLine( cell )) continue;
			return cell;
		}
		throw new NoFixture( "no free cell " + range + " from the hero" + (lineOfSight ? " in a clear line" : "") );
	}

	public boolean clearLine( int cell ){
		Ballistica out = new Ballistica( hero(), cell, Ballistica.PROJECTILE );
		Ballistica back = new Ballistica( cell, hero(), Ballistica.PROJECTILE );
		if (out.collisionPos != cell || back.collisionPos != hero()) return false;
		for (int c : out.subPath( 1, out.dist )) if (level().pit[ c ]) return false;
		return true;
	}

	public void clearMobs( Mob... keep ){
		for (Mob mob : new ArrayList<>( level().mobs )){
			if (java.util.Arrays.asList( keep ).contains( mob )) continue;
			Actor.remove( mob );
			level().mobs.remove( mob );
			if (mob.sprite != null) mob.sprite.killAndErase();
		}
	}

	public < T extends Mob > T find( Class< T > type ){
		for (Mob mob : level().mobs) if (type.isInstance( mob )) return type.cast( mob );
		throw new Driver.Stop( "no " + type.getSimpleName() + " on the level" );
	}

	public < T extends Mob > T mob( T mob, int cell ){
		mob.pos = cell;
		GameScene.add( mob );
		return mob;
	}

	public < T extends Mob > T hunting( T mob, int cell ){
		mob( mob, cell );
		mob.aggro( Dungeon.hero );
		mob.state = mob.HUNTING;
		return mob;
	}

	public Heap drop( Item item, int cell ){
		return level().drop( item, cell );
	}

	public void terrain( int cell, int terrain ){
		Level.set( cell, terrain );
		GameScene.updateMap( cell );
	}

	public < T extends Trap > T trap( T trap, int cell, int terrain ){
		terrain( cell, terrain );
		level().setTrap( trap, cell );
		trap.visible = terrain != com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.SECRET_TRAP;
		return trap;
	}

	public < T extends Blob > T blob( Class< T > type, int cell, int amount ){
		T blob = Blob.seed( cell, amount, type );
		GameScene.add( blob );
		return blob;
	}
}
