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

/**
 * A policy that needs no network.
 *
 * Two jobs. It is the smoke test for the environment: if a heuristic walk can loot, fight, take a
 * stair and die, then every seam between the agent and the game is wired correctly. And it is the
 * fallback when a worker is asked to roll out before the trainer has produced a checkpoint, which
 * is how the first sweeps get bootstrapped.
 *
 * It only ever picks actions the environment's mask allows, so it cannot exercise the illegal-action
 * path by accident and mask a real bug.
 */
public class ScriptedPolicy {

	private final Random random;

	/** Consecutive turns with no progress, used to break out of a local loop. */
	private int stuck;

/** The direction last chosen, avoided while looking for somewhere new to go. */
	private Action lastMove;

	/** Hero position at the previous choose, so a move straight back into it can be refused. */
	private int prevChoosePos = -1;

	/**
	 * Transition cells that were used and did nothing.
	 *
	 * Floor one's exit sits next to the entrance and can be stood on from turn zero, but using it
	 * is quest-gated, so INTERACT is legal and silently does nothing. Without this set the policy
	 * loops forever: interact, step off, interact, step off. That is exactly what every recording
	 * made so far looks like - 1499 turns of movement around the stairs with no pickup, no slot and
	 * no menu - and it is the stall guard, not the turn limit, that finally stops some of them.
	 */
	private final Set< Integer > deadTransitions = new HashSet<>();

	/** Transition cell whose use is waiting to be judged, and the depth it was used at. */
	private int pendingCell = -1;
	private int pendingDepth = -1;

	public ScriptedPolicy( ActionMapper mapper, long seed ){
		this.random = new Random( seed );
	}

	/**
	 * @param slotOut receives the secondary choice: a slot index, an option index or an aim offset
	 */
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

			default:
				slotOut[ 0 ] = 0;
				return chooseWorldAction( env );
		}
	}

	/**
	 * A dialog is open.
	 *
	 * Picks the first selectable option, which for a shop is "buy" and for a prompt is the
	 * affirmative. Shop buying is a documented exploit risk, but leaving every dialog unanswered
	 * would wedge the episode instead, so the option is taken and the reward model is what keeps
	 * a loop from paying.
	 */
	private Action windowChoice( SPDEnv env ){
		for (int i = 0; i < 32; i++){
			if (env.windowOptionSelectable( i )) return Action.MENU_SELECT;
		}
		return Action.CANCEL;
	}

	/**
	 * Aims at the nearest visible enemy among the eight neighbours, otherwise anywhere on the map.
	 *
	 * Only neighbours are offered as aim points, which is enough because shots resolve against a
	 * Ballistica collision cell and a shot further away has to start from one of these directions.
	 */
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

/**
 * One-shot exercises of the modes ordinary play rarely visits.
 *
 * A recording is only worth keeping if it reaches the parts of the step loop that rarely happen, and
 * these two actions are the only routes in: {@code OPEN_INVENTORY} is the sole entry to INVENTORY,
 * and a use started from WORLD is the sole entry to SLOT. Separate flags rather than one shared
 * budget, because sharing it let the first exercise consume the whole allowance and leave the second
 * one unreachable - which is how a recording ended up with INVENTORY and TARGETING but no SLOT.
 */
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

	/** True when a visible hostile is adjacent. */
	private boolean hasAdjacentEnemy(){
		for (Mob mob : Dungeon.hero.getVisibleEnemies()){
			if (mob.isAlive() && Dungeon.level.adjacent( mob.pos, Dungeon.hero.pos )) return true;
		}
		return false;
	}

/**
	 * True when {@code move} would walk straight back to where the hero stood two turns ago.
	 *
	 * Refusing the immediate backtrack is what breaks the classic two-cell cycle: a heap that is
	 * diagonal, or one whose grass cannot be cleared from where the hero stands, leaves no step that
	 * makes progress, and every heuristic answer points back the way it came. The hero then oscillated
	 * between two tiles until the stall guard ended the run - which is how several recordings ended
	 * after under ten steps.
	 */
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

	/** True where a heap can actually be collected, rather than needing grass cleared first. */

	/**
	 * True when any item is close enough to be worth walking to.
	 *
	 * A short leash on purpose: the goal is to make the recording exercise every mode once, not to
	 * clear a floor. Grabbing whatever is nearby is enough to reach the slot, inventory and menu
	 * branches; chasing distant loot would spend the whole turn budget walking.
	 */
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

	/** True when a high-grass heap is adjacent and still needs its grass trampled. */
	private boolean adjacentGrassItem(){
		int hero = Dungeon.hero.pos;
		for (Heap heap : Dungeon.level.heaps.valueList()){
			if (Dungeon.level.adjacent( heap.pos, hero ) && !onPickableTerrain( heap.pos )) return true;
		}
		return false;
	}

	/** The move that steps onto the adjacent high-grass heap, if there is one. */
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

/**
	 * Takes one step along a real path to the nearest item.
	 *
	 * This replaced a greedy Manhattan walk, which could not get around a wall. That version drove
	 * the hero into a corner on floor 1 and the run ended STALLED within two turns - the stall guard
	 * in the environment fires the moment nothing changes for its limit, and standing still against
	 * a wall changes nothing forever. A distance map from each candidate, walked greedily over real
	 * distances, gets around obstacles because the distances already account for them.
	 *
	 * @return the move to take, or null when there is nothing reachable
	 */
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

/**
	 * The nearest adjacent transition cell, or -1 when there is none.
	 *
	 * Checked separately from the generic INTERACT because descending early is how the run ends:
	 * research.md's main goal is depth, so reaching the exit immediately beats clearing the floor.
	 */
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

	/**
	 * Judges the transition use issued on the previous turn.
	 *
	 * A transition that works changes depth. One that does nothing leaves the depth alone, which is
	 * how a quest-locked exit is told apart from a usable stair without hardcoding either case.
	 */
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

	/**
	 * The slot to act on.
	 *
	 * Prefers something whose use needs an aim, because that is the only way to reach TARGETING.
	 * Falling back to the first occupied slot still drinks a potion or swaps equipment, so the
	 * common case stays sensible once the deliberate coverage is spent.
	 */
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

	/**
	 * Tracks whether the last few actions actually did anything.
	 *
	 * Without this a heuristic can settle into a cycle - interact, rest, interact - that consumes
	 * the turn budget without progressing. The environment's stall guard catches it, but only by
	 * ending the episode, which wastes a rollout. Here it just breaks the cycle.
	 */
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