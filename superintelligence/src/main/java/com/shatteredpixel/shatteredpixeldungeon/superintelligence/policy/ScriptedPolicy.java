package com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;

import java.util.Random;

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
				return WindowChoice( env );

			case TARGETING:
				slotOut[ 0 ] = chooseTarget( env );
				return Action.CANCEL;

			case SLOT:
			case INVENTORY:
				slotOut[ 0 ] = firstOccupiedSlot( env );
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
	private Action WindowChoice( SPDEnv env ){
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

	private Action chooseWorldAction( SPDEnv env ){
		float[] mask = env.actionMask();
		float hp = env.heroFeatures()[ 0 ];

		//heal up when badly hurt and nothing is adjacent
		if (hp < 0.35f && !hasAdjacentEnemy() && mask[ Action.REST.index ] > 0.5f ){
			noteProgress( env );
			return Action.REST;
		}

		//drink when hurt
		if (hp < 0.8f && mask[ Action.USE.index ] > 0.5f ){
			int slot = firstOccupiedSlot( env );
			if (slot >= 0){
				noteProgress( env );
				return Action.USE;
			}
		}

		//take the stairs when one is adjacent, which is how a floor is cleared
		if (mask[ Action.INTERACT.index ] > 0.5f && adjacentTransitionPreferred( env ) ){
			noteProgress( env );
			return Action.INTERACT;
		}

		//fight an adjacent hostile
		if (hasAdjacentEnemy() && mask[ Action.INTERACT.index ] > 0.5f ){
			noteProgress( env );
			return Action.INTERACT;
		}

		//otherwise move. avoiding the direction just taken stops the classic oscillation between
		//two cells that a greedy walk falls into
		Action[] moves = {
				Action.MOVE_E, Action.MOVE_W, Action.MOVE_S, Action.MOVE_N,
				Action.MOVE_SE, Action.MOVE_SW, Action.MOVE_NE, Action.MOVE_NW };

		Action fallback = null;
		int start = random.nextInt( moves.length );

		for (int i = 0; i < moves.length; i++){
			Action a = moves[ (start + i) % moves.length ];
			if (mask[ a.index ] <= 0.5f ) continue;
			if (fallback == null) fallback = a;
			if (a != lastMove ){
				lastMove = a;
				noteProgress( env );
				return a;
			}
		}

		if (fallback != null){
			lastMove = fallback;
			noteProgress( env );
			return fallback;
		}

		//boxed in on every side; rest to let regen work, which also burns a turn legally
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
	 * True when a stair is adjacent.
	 *
	 * Checked separately from the generic INTERACT because descending early is how the run ends:
	 * research.md's main goal is depth, so reaching the exit immediately beats clearing the floor.
	 */
	private boolean adjacentTransitionPreferred( SPDEnv env ){
		int hero = Dungeon.hero.pos;
		int width = Dungeon.level.width();

		for (int i = 1; i < ActionMapper.TARGET_COUNT; i++){
			int cell = hero + ActionMapper.TARGET_OFFSETS[ i ][ 0 ] + ActionMapper.TARGET_OFFSETS[ i ][ 1 ] * width;
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			if (Dungeon.level.getTransition( cell ) != null ) return true;
		}
		return false;
	}

	private int firstOccupiedSlot( SPDEnv env ){
		float[] mask = env.slotMask();
		for (int i = 0; i < mask.length; i++){
			if (mask[ i ] > 0.5f ) return i;
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
		}
	}
}