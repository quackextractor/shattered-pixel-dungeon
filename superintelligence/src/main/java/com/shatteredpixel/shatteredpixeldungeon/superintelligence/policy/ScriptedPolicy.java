package com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;

import com.watabou.utils.PathFinder;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;


public class ScriptedPolicy {

	private final Random random;


	private int stuck;


	private Action lastMove;


	private int prevChoosePos = -1;


	private final Set< Integer > deadTransitions = new HashSet<>();


	private int pendingCell = -1;
	private int pendingDepth = -1;

	public ScriptedPolicy( ActionMapper mapper, long seed ){
		this.random = new Random( seed );
	}


	public Action choose( SPDEnv env, int[] slotOut ){
		EnvMode mode = env.mode();

		switch (mode) {
			case MENU:
				slotOut[ 0 ] = 0;
				return windowChoice( env );

case TARGETING:

				slotOut[ 0 ] = chooseTarget( env );
				return Action.USE;

case SLOT:
			case INVENTORY:
				int pick = pickItemWorthUsing( env );

				if (pick < 0){
					slotOut[ 0 ] = 0;
					return Action.CANCEL;
				}
				slotOut[ 0 ] = pick;
				return Action.USE;

			case TALENT:
				slotOut[ 0 ] = random.nextInt( Math.max( 1, env.talentChoices().size() ));
				return Action.MENU_SELECT;

			default:
				slotOut[ 0 ] = 0;
				return chooseWorldAction( env );
		}
	}


	private Action windowChoice( SPDEnv env ){
		for (int i = 0; i < 32; i++){
			if (env.windowOptionSelectable( i )) return Action.MENU_SELECT;
		}
		return Action.CANCEL;
	}


	private int chooseTarget( SPDEnv env ){
		int best = -1;
		int bestDistance = Integer.MAX_VALUE;
		int hero = Dungeon.hero.pos;
		int width = Dungeon.level.width();

		for (int i = 1; i < ActionMapper.TARGET_COUNT; i++){
			if (env.targetMask()[ i ] <= 0.5f ) continue;
			int cell = hero + ActionMapper.TARGET_OFFSETS[ i ][ 0 ] + ActionMapper.TARGET_OFFSETS[ i ][ 1 ] * width;
			Char ch = Actor.findChar( cell );
			if (ch instanceof Mob && ch.isAlive()){
				int d = Math.abs( ch.pos % width - hero % width ) + Math.abs( ch.pos / width - hero / width );
				if (d < bestDistance){ bestDistance = d; best = i; }
			}
		}
		return best >= 0 ? best : 0;
	}


private boolean inventoryExercised;
private boolean useExercised;

private Action chooseWorldAction( SPDEnv env ){
		float[] mask = env.actionMask();
		float hp = env.heroFeatures()[ 0 ];

		prevChoosePos = Dungeon.hero.pos;
		settlePendingTransition();

		if (!useExercised && mask[ Action.USE.index ] > 0.5f ){
			useExercised = true;
			noteProgress( env );
			return Action.USE;
		}

		if (!inventoryExercised && mask[ Action.OPEN_INVENTORY.index ] > 0.5f ){
			inventoryExercised = true;
			noteProgress( env );
			return Action.OPEN_INVENTORY;
		}

		if (hp < 0.35f && !hasAdjacentEnemy() && mask[ Action.REST.index ] > 0.5f ){
			noteProgress( env );
			return Action.REST;
		}

		if (hp < 0.8f && mask[ Action.USE.index ] > 0.5f ){
			int slot = pickItemWorthUsing( env );
			if (slot >= 0){
				noteProgress( env );
				return Action.USE;
			}
		}

		int stairs = adjacentTransitionCell( env );
		if (mask[ Action.INTERACT.index ] > 0.5f && stairs >= 0 && !deadTransitions.contains( stairs )
				&& !itemInReach() ){
			pendingCell = stairs;
			pendingDepth = Dungeon.depth;
			noteProgress( env );
			return Action.INTERACT;
		}

		if (hasAdjacentEnemy() && mask[ Action.INTERACT.index ] > 0.5f ){
			noteProgress( env );
			return Action.INTERACT;
		}

		if (mask[ Action.INTERACT.index ] > 0.5f && adjacentItem()){
			noteProgress( env );
			return Action.INTERACT;
		}

		Action[] moves = {
				Action.MOVE_E, Action.MOVE_W, Action.MOVE_S, Action.MOVE_N,
				Action.MOVE_SE, Action.MOVE_SW, Action.MOVE_NE, Action.MOVE_NW };

Action toward = moveTowardItem( env, moves );
		if (toward != null && mask[ toward.index ] > 0.5f && !backtrack( toward ) ){
			lastMove = toward;
			noteProgress( env );
			return toward;
		}

		if (adjacentGrassItem()){
			Action step = stepOntoGrassItem( env, moves );
			if (step != null && mask[ step.index ] > 0.5f ){
				lastMove = step;
				noteProgress( env );
				return step;
			}
		}

Action fallback = null;
		int start = random.nextInt( moves.length );
		Action unbacked = null;

		for (int i = 0; i < moves.length; i++){
			Action a = moves[ (start + i) % moves.length ];
			if (mask[ a.index ] <= 0.5f ) continue;
			if (unbacked == null && !backtrack( a )) unbacked = a;
			if (fallback == null) fallback = a;
			if (a != lastMove ){
				lastMove = a;
				noteProgress( env );
				return a;
			}
		}

		if (unbacked != null){
			lastMove = unbacked;
			noteProgress( env );
			return unbacked;
		}

		if (fallback != null){
			lastMove = fallback;
			noteProgress( env );
			return fallback;
		}

		if (mask[ Action.REST.index ] > 0.5f ){
			noteProgress( env );
			return Action.REST;
		}

		noteProgress( env );
		return Action.WAIT;
	}


	private boolean hasAdjacentEnemy(){
		for (Mob mob : Dungeon.hero.getVisibleEnemies()){
			if (mob.isAlive() && Dungeon.level.adjacent( mob.pos, Dungeon.hero.pos )) return true;
		}
		return false;
	}


	private boolean backtrack( Action move ){
		if (prevChoosePos < 0) return false;
		int cell = Dungeon.hero.pos + move.dx + move.dy * Dungeon.level.width();
		return cell == prevChoosePos;
	}

	private boolean adjacentItem(){
		int hero = Dungeon.hero.pos;
		for (Heap heap : Dungeon.level.heaps.valueList()){
			if (!Dungeon.level.adjacent( heap.pos, hero )) continue;

			if (onPickableTerrain( heap.pos )) return true;
		}
		return false;
	}




	private boolean itemInReach(){
		int hero = Dungeon.hero.pos;
		for (Heap heap : Dungeon.level.heaps.valueList()){
			if (Dungeon.level.adjacent( heap.pos, hero )) return true;
		}
		return false;
	}

	private boolean onPickableTerrain( int cell ){
		return Dungeon.level.map[ cell ] != Terrain.GRASS
				&& Dungeon.level.map[ cell ] != Terrain.FURROWED_GRASS;
	}


	private boolean adjacentGrassItem(){
		int hero = Dungeon.hero.pos;
		for (Heap heap : Dungeon.level.heaps.valueList()){
			if (Dungeon.level.adjacent( heap.pos, hero ) && !onPickableTerrain( heap.pos )) return true;
		}
		return false;
	}


	private Action stepOntoGrassItem( SPDEnv env, Action[] moves ){
		int hero = Dungeon.hero.pos;
		for (Heap heap : Dungeon.level.heaps.valueList()){
			if (Dungeon.level.adjacent( heap.pos, hero ) && !onPickableTerrain( heap.pos )
					&& Dungeon.level.passable[ heap.pos ]){
				for (Action move : moves){
					if (hero + move.dx + move.dy * Dungeon.level.width() == heap.pos) return move;
				}
			}
		}
		return null;
	}


	private Action moveTowardItem( SPDEnv env, Action[] moves ){
		List<Heap> heaps = Dungeon.level.heaps.valueList();
		if (heaps.isEmpty()) return null;

		int hero = Dungeon.hero.pos;

		Action best = null;
		int bestDistance = Integer.MAX_VALUE;

		for (Heap heap : heaps){
			if (heap.pos == hero) continue;

			boolean[] passable = Dungeon.level.passable;
			PathFinder.buildDistanceMap( heap.pos, passable );

			int[] distance = PathFinder.distance;
			if (distance[ hero ] <= 0) continue;

			for (Action move : moves){
				int cell = hero + move.dx + move.dy * Dungeon.level.width();
				if (cell < 0 || cell >= distance.length) continue;

				if (distance[ cell ] <= 0 || distance[ cell ] >= distance[ hero ]) continue;

				if (distance[ cell ] < bestDistance){
					bestDistance = distance[ cell ];
					best = move;
				}
			}

			if (best != null) break;

			for (Action move : moves){
				int cell = hero + move.dx + move.dy * Dungeon.level.width();
				if (cell < 0 || cell >= distance.length) continue;
				if (distance[ cell ] <= 0 || distance[ cell ] > distance[ hero ]) continue;

				if (distance[ cell ] < bestDistance){
					bestDistance = distance[ cell ];
					best = move;
				}
			}

			if (best != null) break;
		}

		return best;
	}


	private int adjacentTransitionCell( SPDEnv env ){
		int hero = Dungeon.hero.pos;
		int width = Dungeon.level.width();

		for (int i = 1; i < ActionMapper.TARGET_COUNT; i++){
			int cell = hero + ActionMapper.TARGET_OFFSETS[ i ][ 0 ] + ActionMapper.TARGET_OFFSETS[ i ][ 1 ] * width;
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			if (Dungeon.level.getTransition( cell ) != null ) return cell;
		}
		return -1;
	}


	private void settlePendingTransition(){
		if (pendingCell < 0) return;

		if (Dungeon.depth != pendingDepth){

			deadTransitions.clear();
		} else {
			deadTransitions.add( pendingCell );
		}

		pendingCell = -1;
		pendingDepth = -1;
	}

private int firstOccupiedSlot( SPDEnv env ){
		float[] mask = env.slotMask();
		for (int i = 0; i < mask.length; i++){
			if (mask[ i ] > 0.5f ) return i;
		}
		return -1;
	}


	private int pickItemWorthUsing( SPDEnv env ){
		float[] mask = env.slotMask();

		for (int i = 0; i < mask.length; i++){
			if (mask[ i ] <= 0.5f ) continue;
			Item item = env.mapper().slot( i );
			if (item != null && item.usesTargeting) return i;
		}

		for (int i = 0; i < mask.length; i++){
			if (mask[ i ] <= 0.5f ) continue;
			Item item = env.mapper().slot( i );
			if (item == null) continue;
			if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag) continue;
			if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.Waterskin
					&& ((com.shatteredpixel.shatteredpixeldungeon.items.Waterskin) item).isEmpty()){
				continue;
			}
			return i;
		}

		return -1;
	}

	private int lastPos = -1;
	private int lastHp = -1;


	private void noteProgress( SPDEnv env ){
		int pos = Dungeon.hero.pos;
		int hp = Dungeon.hero.HP;

if (pos == lastPos && hp == lastHp){
			stuck++;
		} else {
			stuck = 0;
			lastPos = pos;
			lastHp = hp;
		}

		if (stuck > 6){
			stuck = 0;
			lastMove = null;

			prevChoosePos = -1;
		}
	}
}