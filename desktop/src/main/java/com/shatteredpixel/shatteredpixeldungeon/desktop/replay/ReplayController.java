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
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
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

	/** Whether the per-frame pump has logged at least once, so it only speaks up once. */
	private boolean pumpLogged;

	/**
	 * Whether the halt has been reported to stderr, so the reason is printed once rather than every frame.
	 *
	 * <p>Not a counter and not a timestamp. The reason is a fact about one moment - the step playback
	 * stopped agreeing on - and reprinting it at frame rate says nothing new sixty times a second.
	 */
	private boolean stoppedLogged;

	/** Frames pumped, for the heartbeat. Only counted when {@link #HEARTBEAT} is on. */
	private int frames;

	/**
	 * Whether to print the periodic liveness line. Enabled with {@code -Dspd.heartbeat}, off by default.
	 *
	 * <p>A diagnostic rather than a feature, the same as {@code -Dspd.trace}. It is worth having when the
	 * viewer itself is suspected of being at fault, and worth nothing to someone watching a recording:
	 * it kept printing after a halt - by design, the window stays open to be read - so it filled the
	 * output with lines that said only that the window was still up.
	 */
	private static final boolean HEARTBEAT = System.getProperty( "spd.heartbeat" ) != null;

	/** Scene the HUD was built against, so a replaced scene rebuilds it. */
	private com.watabou.noosa.Scene hudScene;



	private ReplayController( ReplayPlayer player ){
		this.player = player;
	}

	/** The running game, so the window can be closed when playback ends. */
	private static Game game;

	/** Records the running game. Called once, by the launcher. */
	public static void gameInstance( Game instance ){
		game = instance;
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
		log( "installed, driving " + player.playback().replay().seedText
				+ " (" + player.playback().total() + " steps)" );
	}

	/**
	 * Diagnostics for the viewer.
	 *
	 * Written to stderr rather than the HUD because the question they answer - did a keypress reach
	 * the listener at all - is exactly the one an on-screen overlay cannot answer. Key handling here
	 * depends on three separate gates (hard binding, {@code InputHandler} passing the key on, and
	 * {@link Signal} dispatch order), and when a control appears dead there is no way to tell which
	 * gate swallowed it without this.
	 */
	private static void log( String message ){
		System.err.println( "[replay] " + message );
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

		//Through FrameDelta, so -Dspd.fixedDelta can pin playback's clock to the animations' clock.
		//Left as raw Game.elapsed the two share a frame rate, which is what makes the number of frames
		//per step - and therefore how much the render loop gets to change the world - a property of the
		//machine rather than of the recording.
		controller.player.update( FrameDelta.current( Game.elapsed ));

if (controller.hud == null || controller.hudScene != Game.scene()){
			//Not just "has a HUD been built". A restart replaces the scene, and the old scene's gizmos
			//are discarded with it, so a HUD built against the outgoing scene silently vanishes - and
			//because the field was still set, nothing ever rebuilt it. Comparing against the live
			//scene heals that automatically, whenever the scene is replaced for any reason.
			controller.buildHud();
			controller.hudScene = Game.scene();
			log( "HUD built" );
		}

		//Separated from the HUD on purpose, and run on every frame rather than on a rebuild.
		//
		//InterlevelScene calls KeyEvent.clearListeners() on its way out, which removes every key listener
		//including this one. Rebinding therefore has to happen on scene change - but it was tied to the
		//HUD rebuild, so anything that stopped that rebuild from running (buildHud throwing, a scene whose
		//camera is not ready) left the viewer with no controls at all, while playback carried on perfectly
		//because it is driven by the frame hook rather than by keys. That is what makes it look like only
		//the controls broke.
		//
		//Doing it every frame also closes the window between clearListeners() and the next scene change.
		//In that window SPACE had no viewer listener, and the game's own SPDAction.WAIT_OR_PICKUP binding
		//was live and unopposed: pressing pause waited the hero instead, which spent a turn no recorded
		//step asked for and diverged the replay on the very next comparison.
		controller.bindKeys();

		if (!controller.pumpLogged){
			controller.pumpLogged = true;
			log( "pump running, waiting for uiCamera (now "
					+ (PixelScene.uiCamera == null ? "still null" : "available") + ")" );
		}
		controller.layout();
		controller.dismissUnanswerableWindow();

		//A heartbeat, because "the game is unresponsive" and "the controls do nothing" look
		//identical from the outside and have completely different causes. If this stops printing,
		//the render loop is stalled and the queued key events are never dispatched; if it keeps
		//printing, the loop is alive and the loss is between the key arriving and the listener.
		//
		//Opt-in with -Dspd.heartbeat, off by default. It answers a question only someone debugging the
		//viewer asks, and it asks it forever: the window is deliberately left open on a halt, so the
		//line kept printing at frame rate against a person who was only trying to watch a recording.
		//That is the same rule -Dspd.trace follows: a diagnostic is not a feature.
		//
		//The one-off lines above and below stay on. Each prints at most once, and they are what tells a
		//dead key apart from a dead loop, which is why they were added in the first place.
		if (HEARTBEAT){
			controller.frames++;
			if (controller.frames % 120 == 0){
				log( "heartbeat frame " + controller.frames
						+ " step " + controller.player.playback().cursor()
						+ " playing=" + controller.player.playing()
						+ " windowOpen=" + GameScene.showingWindow() );
			}
		}

		//Closed here rather than in halt(), so the frame's reporting above is written first.
		if (controller.player.closing()){
			log( "closing: " + controller.player.haltReason() );
			if (game != null ) game.finish();
		} else if (controller.player.finished() && !controller.stoppedLogged){
			//Playback has stopped and nothing asked for the window to close. Without this the process
			//lives forever: the frame loop keeps running, the heartbeat keeps reporting playing=false,
			//and a batch run blocks on a keypress nobody will send. Reaching the end of a recording was
			//indistinguishable from hanging on it, because neither printed anything.
			//
			//Reported rather than closed, since the point of leaving the window up is to read why it
			//stopped. A run that is meant to finish unattended passes --close.
			//
			//Once. pump() runs every frame for as long as the window is open, and finished() stays true
			//after the first halt, so an unguarded print put the same DIVERGED line on stderr sixty times
			//a second and buried everything printed around it. The window stays up either way - only the
			//reporting is once.
			controller.stoppedLogged = true;
			log( "stopped: " + controller.player.haltReason()
					+ "   (window left open; pass --close to exit on completion)" );
		}
	}

	/** The last window this method dismissed, so it is only reported once per window. */
	private Window dismissed;

	/**
	 * Starts the recording again from the first step.
	 *
	 * The live game cannot be unwound - terrain, mobs and the hero's own state have all moved on - so
	 * the level is rebuilt by re-entering the interlevel scene, which is the same path the viewer
	 * started on.
	 *
	 * Nulling the hero is what makes that path a fresh start rather than a descent.
	 * {@code InterlevelScene.descend()} has two branches: with no hero it calls {@code Dungeon.init()}
	 * and builds floor 1 again from the same seed, but with a hero still in place it takes the
	 * transition branch and lands on the <i>next</i> floor. Leaving the hero alone therefore restarted
	 * the recording on floor 2, where its very first step diverged and playback stopped dead.
	 */
	private void restart(){
		player.restart();

		Dungeon.hero = null;

		//InterlevelScene.mode is static and gameplay rewrites it: stepping onto a transition sets it
		//to ASCEND or DESCEND, so by the time restart is pressed it is whatever the recording last
		//did. Re-entering under that mode took the game's own path instead - "you return to floor 1"
		//restored the level as it was, which is a different map from the recording's first step and
		//diverged immediately. Both statics are set here so the re-entry cannot inherit either.
		InterlevelScene.mode = InterlevelScene.Mode.DESCEND;
		InterlevelScene.autoContinue = true;

		//the HUD belongs to the scene being replaced; the scene comparison in pump() rebuilds it
		hud = null;
		help = null;
		hudScene = null;
		dismissed = null;

		//Otherwise the second run's halt is silent. A restart re-arms the one-shot report, because a
		//fresh playback that stops again is a new event and the reason is a different one.
		//
		//Reported before the switch rather than after it. Game.switchScene only sets a flag; the scene
		//is rebuilt at the top of the next frame, which means the outgoing GameScene.update() still
		//runs once more with a null hero - and pump()'s finished() branch printed the *previous* run's
		//halt on it. That is why pressing R on a finished recording appeared to do nothing: the HUD was
		//immediately overwritten with the old run's "finished" line, while the player underneath had
		//already rewound.
		stoppedLogged = false;

		Game.switchScene( InterlevelScene.class );
	}

	/**
	 * Closes any window the recording has no way to answer.
	 *
	 * This is what made the viewer look broken. Reaching a transition the hero cannot use raises an
	 * informational {@link WndMessage} - "you cannot leave the dungeon yet" - and SPD windows are
	 * modal: while one is up the scene stops accepting input, so the game becomes completely
	 * unresponsive to the viewer controls. Playback kept going regardless, because the viewer injects
	 * actions through the mapper instead of through the blocked selector, which is exactly why it
	 * looked like the game had frozen rather than like a dialog was in the way.
	 *
	 * A recording cannot answer that message: there is no recorded step for it, because the policy
	 * never saw a choice. So it is dismissed through the window's own back path, which is what
	 * pressing escape on it would do.
	 *
	 * {@link WndOptions} is deliberately left alone. That one the recording <i>can</i> answer - it
	 * arrives together with a recorded {@code MENU} step, which resolves it through
	 * {@code WindowBridge}. Dismissing it would break exactly the case this viewer exists to show.
	 */
	private void dismissUnanswerableWindow(){
		Window wnd = GameScene.topWindow();
		if (wnd == null) return;

		if (wnd instanceof WndOptions) return;

		if (dismissed != wnd){
			dismissed = wnd;
			log( "dismissing unanswerable window " + wnd.getClass().getSimpleName() );
		}

		wnd.onBackPressed();
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
	 * The keys are forced to {@link GameAction#NONE} rather than merely listened for, for two
	 * independent reasons, and both were found the hard way.
	 *
	 * {@code InputHandler.keyDown} drops any key that is not bound before a listener can see it, so
	 * a bare listener on an unbound key never fires.
	 *
	 * And they have to be <i>forced</i>, not just registered: SPACE is bound to WAIT by default, so a
	 * pause key that left the binding alone still made the hero wait mid-replay, which desynced the
	 * recording. That needs {@link KeyBindings#addOverride}, which is consulted ahead of the player's
	 * bindings - unlike {@code addHardBinding}, which is checked last and so can only ever affect a
	 * key the player has not bound at all.
	 *
	 * <p>Called every frame, so it must be cheap and idempotent. The listener is created once and
	 * kept; only its registration is refreshed, because {@link #uninstall} nulls it and a scene change
	 * can drop it behind the viewer's back. Re-adding an existing listener is a no-op in
	 * {@code Signal.add}, so this costs a handful of map operations a frame.
	 */
	private void bindKeys(){
		if (viewerKeys == null){
			viewerKeys = newViewerKeyListener();
			log( "listener registered for SPACE/R/+/-/[/]/ESC" );
		} else {
			//A no-op when already registered, and the repair when KeyEvent.clearListeners() has dropped
			//it - which InterlevelScene does every time it hands off to the game scene, so on any restart.
			KeyEvent.addKeyListener( viewerKeys );
		}

		for (int key : VIEWER_KEYS){
			KeyBindings.addOverride( key, GameAction.NONE );
		}
	}

	/** The keys the viewer claims, and the reason each is listed is in {@link #bindKeys()}. */
	private static final int[] VIEWER_KEYS = {
			Input.Keys.SPACE, Input.Keys.R, Input.Keys.PLUS, Input.Keys.EQUALS,
			Input.Keys.MINUS, Input.Keys.LEFT_BRACKET, Input.Keys.RIGHT_BRACKET };

	private Signal.Listener<KeyEvent> newViewerKeyListener(){
		return new Signal.Listener<KeyEvent>() {
			@Override
			public boolean onSignal( KeyEvent event ){
				if (!event.pressed) return false;

				log( "key " + event.code );
				switch (event.code){
					case Input.Keys.SPACE:
						player.playing( !player.playing() );
						log( "  -> playing=" + player.playing() );
						return true;
					case Input.Keys.PLUS:
					case Input.Keys.EQUALS:
						player.speed( player.speed() * 2f );
						log( "  -> speed=" + player.speed() );
						return true;
					case Input.Keys.MINUS:
						player.speed( player.speed() / 2f );
						log( "  -> speed=" + player.speed() );
						return true;
					case Input.Keys.LEFT_BRACKET:
						player.speed( player.speed() / 4f );
						log( "  -> speed=" + player.speed() );
						return true;
					case Input.Keys.RIGHT_BRACKET:
						player.speed( player.speed() * 4f );
						log( "  -> speed=" + player.speed() );
						return true;
					case Input.Keys.R:
						restart();
						log( "  -> restart requested" );
						return true;
					case Input.Keys.ESCAPE:
						//A window on top of the scene gets BACK first and swallows it - that is what
						//opened the little pause box in the first place. So close anything open
						//before falling through to quitting.
						if (!closeTopWindow()){
							log( "  -> quit" );
							quit();
						} else {
							log( "  -> closed top window" );
						}
						return true;
					default:
						return false;
				}
			}
		};
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

		ReplayPlayback playback = player.playback();

		//The level width, read from the live level and handed to the playback so a cell can be printed
		//as coordinates. Null before floor 1 exists, and coordinates() falls back to the raw index.
		if (Dungeon.level != null){
			playback.gridWidth( Dungeon.level.width() );
		}

		hud.text( playback.status()
				+ "\nseed " + playback.replay().seedText
				+ "   hero " + playback.replay().heroClass
				//"actions", not "turns": Replay.turns is SPDEnv.turnsTotal(), a count of decisions the
				//agent made. The engine's own clock is Actor.now(), shown separately below, and the two are
				//not interchangeable - a turn is a duration and can be fractional, so a heavy weapon costs
				//two and haste less than one. Calling the decision count "turns" invited exactly the
				//reading that made the turn limit look like it counted something else.
				+ "\nrecorded depth " + playback.replay().depth
				+ "   actions " + playback.replay().turns
				+ "/" + playback.replay().turnLimitPerFloor
				+ "   engine time " + String.format( "%.1f", com.shatteredpixel.shatteredpixeldungeon.actors.Actor.now() )
				//Live score, not just the run's total. The total is on the recording and cannot say
				//which action lost a hundred points; the per-step delta and the gained/lost split can,
				//and the split is what tells a run that climbed to 40 from one that reached 40 and gave
				//most of it back.
				+ "\nscore " + String.format( "%.2f", playback.score() )
				+ "   last step " + String.format( "%+.3f", playback.stepReward() )
				+ "   +" + String.format( "%.1f", playback.gained() )
				+ " / " + String.format( "%.1f", playback.lost() )
				+ "   (final " + String.format( "%.2f", playback.replay().score ) + ")"
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
		//Process-wide, so it has to go back: the viewer quits to the title screen, where SPACE and R
		//belong to the game again.
		KeyBindings.clearOverrides();
	}
}
