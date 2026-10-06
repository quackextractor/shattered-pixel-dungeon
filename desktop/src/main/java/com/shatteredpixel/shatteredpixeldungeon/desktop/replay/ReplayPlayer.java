/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.shatteredpixel.shatteredpixeldungeon.desktop.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;

/**
 * Replays a recording by applying its actions to the live game.
 *
 * The injection seam is the point of the whole design. The game's own input path is
 *
 * <pre>
 *   CellSelector.onSignal(KeyEvent) -&gt; heldAction1/2/3
 *     -&gt; directionFromAction       -&gt; Dungeon.hero.handle( cell )
 *     -&gt; Dungeon.hero.next()
 * </pre>
 *
 * and {@code ActionMapper} already wraps exactly that pair, because the headless trainer needs the
 * same seam. So this class replays through the game's real action resolution rather than
 * synthesising key events: attack-versus-loot-versus-stairs-versus-menu resolves identically to a
 * human playing, which is the only way a replay is worth watching.
 *
 * Timing matters and is easy to get wrong. {@link Actor#headlessStep()} must run before testing
 * {@code hero.ready} for the same reason the headless pipeline does it: injecting an action sets
 * {@code curAction} and clears {@code ready} only inside {@code Hero.act()}, so testing readiness
 * first would see a stale {@code true} forever and the run would sit still - a stall that looks
 * exactly like a policy that has chosen not to move.
 */
public class ReplayPlayer {

	/** Seconds between steps at 1x. Without this a 1500-step replay flashes past unreadably. */
	public static final float BASE_STEP_SECONDS = 0.06f;

	private final ReplayPlayback playback;
	private final ActionMapper mapper;

	private float sinceStep = 0;
	private float speed = 1f;
	private boolean playing = true;

	/** Set once a step has been applied and the engine has not yet settled for the next. */
	private boolean awaitingSettle = false;

	private int settledPosition = -1;

	/** Why playback stopped, for the HUD. Empty while playing. */
	private String haltReason = "";

	public ReplayPlayer( Replay replay ){
		this.playback = new ReplayPlayback( replay );
		this.mapper = new ActionMapper( new EnvConfig() );
	}

	public ReplayPlayback playback(){
		return playback;
	}

	public float speed(){
		return speed;
	}

	public void speed( float multiplier ){
		//clamped so a stray keypress cannot make playback unplayable or instant
		this.speed = Math.max( 0.1f, Math.min( 16f, multiplier ) );
	}

	public boolean playing(){
		return playing;
	}

	public void playing( boolean value ){
		this.playing = value;
	}

	public String haltReason(){
		return haltReason;
	}

	/**
	 * Advances playback. Call once per rendered frame.
	 *
	 * @param elapsed seconds since the previous frame
	 */
	public void update( float elapsed ){
		if (playing){
			sinceStep += elapsed;
		}

		//let the engine settle even while paused, or pausing mid-turn would freeze a half-finished
		//animation and resuming would jump
		if (awaitingSettle){
			settle();
			return;
		}

		if (!playing || !readyToAct()) return;

		if (sinceStep < BASE_STEP_SECONDS / speed) return;

		sinceStep = 0;
		applyNextStep();
	}

	/** True when the hero is waiting for input. */
	private boolean readyToAct(){
		if (Dungeon.hero == null) return false;
		if (!Dungeon.hero.isAlive()){
			halt( "run ended - hero is dead" );
			return false;
		}
		if (Dungeon.hero.paralysed > 0) return false;
		return Dungeon.hero.ready;
	}

	/**
	 * Applies one recorded step.
	 *
	 * A step can need two calls: the first action may open a menu, a slot choice, or a targeting
	 * prompt, and the recording stores the follow-up on the next step. The recording's own
	 * {@code mode} field says which case this is, so the recorded mode is used rather than the live
	 * one - if they disagree the recording is already wrong and the divergence check will say so.
	 */
	private void applyNextStep(){
		Replay.Step step = playback.peek();
		if (step == null){
			playback.finish();
			return;
		}

		Action action;
		try {
			action = Action.valueOf( step.action );
		} catch (IllegalArgumentException e){
			halt( "unknown action in recording: " + step.action );
			return;
		}

		EnvMode mode = modeOf( step.mode );

		//the follow-up half of a two-step action
		if (mode == EnvMode.SLOT || mode == EnvMode.INVENTORY || mode == EnvMode.MENU){
			mapper.applySecondary( mode, action, step.slot );
		} else if (mode == EnvMode.TARGETING){
			mapper.applySecondary( mode, action, step.slot );
		} else {
			mapper.apply( action, step.slot );
		}

		mapper.refreshSlots();

		playback.advance();
		awaitingSettle = true;
	}

	/**
	 * Runs the actor scheduler until the turn resolves, then checks the hero landed where the
	 * recording says it should.
	 */
	private void settle(){
		if (Dungeon.hero == null) return;

		//a bounded number of scheduler steps: a pathological mob chain must not hang the viewer
		for (int i = 0; i < 4000; i++){
			Actor.headlessStep();

			if (Dungeon.hero == null) break;
			if (!Dungeon.hero.isAlive()) break;
			if (Dungeon.hero.ready && Dungeon.hero.paralysed == 0) break;
		}

		if (Dungeon.hero != null && Dungeon.hero.isAlive()){
			settledPosition = Dungeon.hero.pos;
			if (!playback.checkPosition( settledPosition )){
				halt( playback.status() );
			}
		} else {
			playback.finish();
			halt( "run ended" );
		}

		awaitingSettle = false;
	}

	private void halt( String reason ){
		if (playing){
			playing = false;
			haltReason = reason;
		}
	}

	/** Resets to the first step, leaving the live game where it is. */
	public void restart(){
		playing = true;
		sinceStep = 0;
		awaitingSettle = false;
		haltReason = "";
	}

	private static EnvMode modeOf( String name ){
		try {
			return EnvMode.valueOf( name );
		} catch (IllegalArgumentException e){
			return EnvMode.WORLD;
		}
	}
}
