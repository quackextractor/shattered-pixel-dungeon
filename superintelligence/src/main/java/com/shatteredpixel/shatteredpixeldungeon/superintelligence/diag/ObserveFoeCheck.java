package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Gnoll;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.GridChannel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.ObservationEncoder;

final class ObserveFoeCheck {

	private ObserveFoeCheck(){}

	private static float sum( ObservationEncoder encoder, GridChannel channel ){
		int plane = 0;
		for (GridChannel c : GridChannel.values()){
			if (c == channel) break;
			if (c.kind != GridChannel.Kind.SCALAR) plane++;
		}
		int area = encoder.gridWidth() * encoder.gridHeight();
		float total = 0f;
		for (int p = 0; p < area; p++) total += encoder.grid()[ plane * area + p ];
		return total;
	}

	private static int cellInView(){
		int hero = Dungeon.hero.pos;
		for (int c = 0; c < Dungeon.level.length(); c++){
			if (Dungeon.level.heroFOV[ c ] && Dungeon.level.passable[ c ] && Actor.findChar( c ) == null
					&& Dungeon.level.heaps.get( c ) == null && Dungeon.level.distance( hero, c ) >= 2) return c;
		}
		return -1;
	}

	private static float place( ObservationEncoder encoder, Mob mob, GridChannel channel ){
		int cell = cellInView();
		if (cell < 0){
			ObserveCheck.fail( "no free cell in view to place a mob on" );
			return 0f;
		}
		mob.pos = cell;
		GameScene.add( mob, 0f );
		Dungeon.level.occupyCell( mob );
		encoder.encode();
		float lit = sum( encoder, channel );
		mob.destroy();
		return lit;
	}

	static void checkAnEnemyShowsItsState( ObservationEncoder encoder ){
		Gnoll asleep = new Gnoll();
		asleep.state = asleep.SLEEPING;
		if (place( encoder, asleep, GridChannel.ENEMY_ASLEEP ) <= 0f) ObserveCheck.fail( "a sleeping gnoll lit no ENEMY_ASLEEP" );
		Gnoll hunting = new Gnoll();
		hunting.state = hunting.HUNTING;
		if (place( encoder, hunting, GridChannel.ENEMY_HUNTING ) <= 0f) ObserveCheck.fail( "a hunting gnoll lit no ENEMY_HUNTING" );
	}

	static void checkAnEnemyShowsItsHealth( ObservationEncoder encoder ){
		Gnoll whole = new Gnoll();
		float full = place( encoder, whole, GridChannel.ENEMY_HP );
		Gnoll hurt = new Gnoll();
		hurt.HP = hurt.HT / 2;
		float half = place( encoder, hurt, GridChannel.ENEMY_HP );
		if (full <= 0f || Math.abs( half - full / 2f ) > 0.02f) ObserveCheck.fail( "ENEMY_HP reads " + full + " whole and " + half + " at half health" );
	}

	static void checkEnemiesCanBeToldApart( ObservationEncoder encoder ){
		float gnoll = place( encoder, new Gnoll(), GridChannel.ENEMY_KIND );
		float rat = place( encoder, new Rat(), GridChannel.ENEMY_KIND );
		if (gnoll <= 0f || gnoll == rat) ObserveCheck.fail( "ENEMY_KIND reads " + gnoll + " for a gnoll and " + rat + " for a rat" );
	}

	static void checkDoorsAndWaterAreDrawn( ObservationEncoder encoder ){
		Level level = Dungeon.level;
		int cell = cellInView();
		if (cell < 0){
			ObserveCheck.fail( "no free cell in view for terrain" );
			return;
		}
		boolean water = level.water[ cell ];
		level.water[ cell ] = true;
		encoder.encode();
		float lit = sum( encoder, GridChannel.WATER );
		level.water[ cell ] = water;
		if (lit <= 0f) ObserveCheck.fail( "a water cell in view lit no WATER; Goo heals on it" );
		int map = level.map[ cell ];
		level.map[ cell ] = com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.DOOR;
		encoder.encode();
		lit = sum( encoder, GridChannel.DOOR );
		level.map[ cell ] = map;
		if (lit <= 0f) ObserveCheck.fail( "a door in view lit no DOOR" );
	}

	static void checkASeenItemIsRemembered( ObservationEncoder encoder ){
		Level level = Dungeon.level;
		int cell = cellInView();
		if (cell < 0){
			ObserveCheck.fail( "no free cell in view for an item" );
			return;
		}
		Heap heap = level.drop( new Food(), cell );
		heap.seen = true;
		level.visited[ cell ] = true;
		boolean inView = level.heroFOV[ cell ];
		level.heroFOV[ cell ] = false;
		encoder.encode();
		float remembered = sum( encoder, GridChannel.ITEM_KIND );
		heap.seen = false;
		encoder.encode();
		float unseen = sum( encoder, GridChannel.ITEM_KIND );
		level.heroFOV[ cell ] = inView;
		level.heaps.remove( cell );
		if (remembered <= unseen) ObserveCheck.fail( "a ration seen and out of view lit ITEM_KIND " + remembered + " against " + unseen + " unseen, so food is forgotten at the first corner" );
	}

	static void checkItemKindHidesIdentity( ObservationEncoder encoder ){
		Level level = Dungeon.level;
		int cell = cellInView();
		if (cell < 0){
			ObserveCheck.fail( "no free cell in view for an item" );
			return;
		}
		float[] seen = new float[ 2 ];
		int i = 0;
		for (com.shatteredpixel.shatteredpixeldungeon.items.Item potion : new com.shatteredpixel.shatteredpixeldungeon.items.Item[]{ new PotionOfHealing(), new PotionOfToxicGas() }){
			Heap heap = level.drop( potion, cell );
			heap.seen = true;
			encoder.encode();
			seen[ i++ ] = sum( encoder, GridChannel.ITEM_KIND );
			level.heaps.remove( cell );
		}
		if (seen[ 0 ] != seen[ 1 ]) ObserveCheck.fail( "two unidentified potions read " + seen[ 0 ] + " and " + seen[ 1 ] + " on ITEM_KIND, which tells them apart" );
	}
}
