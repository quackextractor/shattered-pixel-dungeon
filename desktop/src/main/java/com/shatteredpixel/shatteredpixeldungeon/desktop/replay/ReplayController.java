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

import com.badlogic.gdx.Input;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.watabou.input.GameAction;
import com.watabou.input.KeyBindings;
import com.watabou.input.KeyEvent;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import com.watabou.utils.Signal;

/**
 * Drives a recording inside the running game and draws the HUD over it.
 *
 * Not a scene. The game enters through {@code InterlevelScene}, which builds floor 1 and hands off
 * to {@code GameScene} - a hardcoded transition, so a {@code GameScene} subclass would never be
 * entered. Driving from outside avoids that entirely and means the replay is watched in the real
 * scene with its real sprites and its real update loop: nothing is re-created or approximated, so
 * anything that looks wrong on screen looks wrong because it is wrong.
 *
 * Input is suppressed rather than filtered. {@link GameScene#lockCellInput(boolean)} disables the
 * cell selector so nothing but this class can inject an action. Ignoring individual keys instead
 * would mean reasoning about which keys matter, and one missed keypress becomes a divergence many
 * steps later; a selector that accepts nothing cannot desync anything.
 */
public class ReplayController {

	private static ReplayController active;

	private final ReplayPlayer player;

	private BitmapText hud;
	private BitmapText help;

	private Signal.Listener<KeyEvent> viewerKeys;

	private ReplayController( ReplayPlayer player ){
		this.player = player;
	}

	/**
	 * Arms a recording and marks the game's cell input as locked.
	 *
	 * Called before the game starts, because the window's input is taken before the first frame.
	 */
	public static void install( ReplayPlayer player ){
		active = new ReplayController( player );
		GameScene.lockCellInput( true );
		GameScene.setFrameDriver( ReplayController::pump );
	}

	/** True while a recording is driving the game. */
	public static boolean installed(){
		return active != null;
	}

	/**
	 * Per-frame pump, called from the game scene.
	 *
	 * A static hook rather than a subclass, because the game's own transition to
	 * {@code GameScene} is not overridable.
	 */
	public static void pump(){
		ReplayController controller = active;
		if (controller == null) return;

		controller.player.update( Game.elapsed );

		if (controller.hud == null && PixelScene.uiCamera != null){
			controller.buildHud();
			controller.bindKeys();
		}
		controller.layout();
	}

	private void buildHud(){
		hud = new BitmapText( PixelScene.pixelFont );
		hud.visible = true;
		//without this the text inherits the scene's camera, which follows the hero, so the HUD
		//scrolls around the map and sits wherever the camera happens to be. Every other overlay in
		//the game assigns the UI camera explicitly for exactly this reason.
		hud.camera = PixelScene.uiCamera;
		Game.scene().addToFront( hud );

		help = new BitmapText( PixelScene.pixelFont );
		help.visible = true;
		help.text( "SPACE pause  +/- speed  [ ] coarser/finer  R restart  ESC quit" );
		help.camera = PixelScene.uiCamera;
		Game.scene().addToFront( help );
	}

/**
	 * Viewer controls.
	 *
	 * The keys are registered as hard bindings rather than only listened for. {@code
	 * InputHandler.keyDown} drops any key that is not bound before any listener sees it, so a bare
	 * listener on an unbound key silently never fires - which is exactly why these controls did
	 * nothing. They map to {@code GameAction.NONE} so they do not disturb the game's own actions,
	 * and being hard bindings they cannot be rebound away by the player mid-replay.
	 */
	private void bindKeys(){
		KeyBindings.addHardBinding( Input.Keys.SPACE, GameAction.NONE );
		KeyBindings.addHardBinding( Input.Keys.R, GameAction.NONE );
		KeyBindings.addHardBinding( Input.Keys.PLUS, GameAction.NONE );
		KeyBindings.addHardBinding( Input.Keys.EQUALS, GameAction.NONE );
		KeyBindings.addHardBinding( Input.Keys.MINUS, GameAction.NONE );
		KeyBindings.addHardBinding( Input.Keys.LEFT_BRACKET, GameAction.NONE );
		KeyBindings.addHardBinding( Input.Keys.RIGHT_BRACKET, GameAction.NONE );

		viewerKeys = new Signal.Listener<KeyEvent>() {
			@Override
			public boolean onSignal( KeyEvent event ){
				if (!event.pressed) return false;

				switch (event.code){
					case Input.Keys.SPACE:
						player.playing( !player.playing() );
						return true;
					case Input.Keys.PLUS:
					case Input.Keys.EQUALS:
						player.speed( player.speed() * 2f );
						return true;
					case Input.Keys.MINUS:
						player.speed( player.speed() / 2f );
						return true;
					case Input.Keys.LEFT_BRACKET:
						player.speed( player.speed() / 4f );
						return true;
					case Input.Keys.RIGHT_BRACKET:
						player.speed( player.speed() * 4f );
						return true;
					case Input.Keys.R:
						player.restart();
						return true;
					case Input.Keys.ESCAPE:
						//A window on top of the scene gets BACK first and swallows it - that is what
						//opened the little pause box in the first place. So close anything open
						//before falling through to quitting.
						if (!closeTopWindow()){
							quit();
						}
						return true;
					default:
						return false;
				}
			}
		};
		KeyEvent.addKeyListener( viewerKeys );
	}

	/**
	 * Closes the topmost window, if the game has one open.
	 *
	 * {@code Window.onBackPressed} is the game's own close path, so windows that confirm or cancel
	 * behave exactly as they do in normal play.
	 *
	 * @return true if a window was closed
	 */
	private static boolean closeTopWindow(){
		if (!GameScene.showingWindow()) return false;
		//the game's own cancel path, which closes whatever window is on top
		return GameScene.cancel();
	}

	/** Leaves the viewer and returns to the title. */
	private static void quit(){
		uninstall();
		com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon.switchNoFade(
				com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene.class );
	}

	private void layout(){
		if (hud == null || PixelScene.uiCamera == null) return;

		float x = 4;
		float y = PixelScene.uiCamera.height - 4;

		hud.text( player.playback().status()
				+ "\nseed " + player.playback().replay().seedText
				+ "   hero " + player.playback().replay().heroClass
				+ "\nrecorded score " + String.format( "%.2f", player.playback().replay().score )
				+ "   depth " + player.playback().replay().depth
				+ "   turns " + player.playback().replay().turns
				+ "\nspeed " + String.format( "%.1f", player.speed() ) + "x"
				+ "   " + ( player.playing() ? "playing" : "paused" )
				+ ( player.haltReason().isEmpty() ? "" : "   " + player.haltReason() ) );
		hud.measure();
		hud.x = x;
		hud.y = y - hud.height;

		if (help != null){
			help.measure();
			help.x = x;
			help.y = hud.y - help.height - 2;
		}
	}

	/** Releases cell input and stops driving. Called when the viewer quits. */
	public static void uninstall(){
		if (active != null) active.viewerKeys = null;
		active = null;
		GameScene.setFrameDriver( null );
		GameScene.lockCellInput( false );
	}
}
