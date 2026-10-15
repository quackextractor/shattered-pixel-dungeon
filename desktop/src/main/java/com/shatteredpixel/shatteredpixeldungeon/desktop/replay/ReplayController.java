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

import java.util.ArrayList;

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

	/**
	 * The HUD as a pool of one-line {@link BitmapText} gizmos, not as one multi-line string.
	 *
	 * <p>{@link BitmapText} cannot wrap. Its font has no newline glyph - {@code LATIN_FULL} has no
	 * {@code \n} - and both {@code measure()} and {@code updateVertices()} feed every character
	 * straight through {@code font.get()}, so a {@code \n} contributes no line advance and no line
	 * break. It renders as a blank glyph and the rest of the string keeps running right, off the
	 * screen. The game never relies on this: its own multi-line component, {@code RenderedTextBlock},
	 * exists precisely because {@code BitmapText} will not wrap, and no {@code BitmapText} anywhere
	 * in core is given a {@code \n}.
	 *
	 * <p>The HUD has always built its text with {@code \n}, so this was broken before the run data
	 * grew: every line but the first simply kept going to the right, and the info meant to sit on
	 * its own row was only readable when the window happened to be wide enough. Adding the score
	 * line made the string long enough to run off at any ordinary size.
	 */
	private final ArrayList<BitmapText> hudLines = new ArrayList<>();

	/** Same wrapping, for the key-hint line. */
	private final ArrayList<BitmapText> helpLines = new ArrayList<>();

	/** Bounds measured text against, rebuilt when the UI camera changes. */
	private float maxTextWidth;

	/**
	 * Camera {@link #maxTextWidth} was measured against.
	 *
	 * <p>Held by identity rather than by a boolean "measured" flag. A flag cannot tell "measured
	 * once" from "measured for a window that has since been resized", and the UI camera is re-created
	 * whenever the zoom changes, so a resized window would keep wrapping to the width it had before.
	 */
	private com.watabou.noosa.Camera textWidthCamera;

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

		controller.player.update( FrameDelta.current( Game.elapsed ));

if (controller.hudLines.isEmpty() || controller.hudScene != Game.scene()){
			controller.buildHud();
			controller.hudScene = Game.scene();
			log( "HUD built" );
		}

		controller.bindKeys();

		if (!controller.pumpLogged){
			controller.pumpLogged = true;
			log( "pump running, waiting for uiCamera (now "
					+ (PixelScene.uiCamera == null ? "still null" : "available") + ")" );
		}
		controller.layout();
		controller.dismissUnanswerableWindow();

		if (HEARTBEAT){
			controller.frames++;
			if (controller.frames % 120 == 0){
				log( "heartbeat frame " + controller.frames
						+ " step " + controller.player.playback().cursor()
						+ " playing=" + controller.player.playing()
						+ " windowOpen=" + GameScene.showingWindow() );
			}
		}

		if (controller.player.closing()){
			log( "closing: " + controller.player.haltReason() );
			if (game != null ) game.finish();
		} else if (controller.player.finished() && !controller.stoppedLogged){
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

		InterlevelScene.mode = InterlevelScene.Mode.DESCEND;
		InterlevelScene.autoContinue = true;

		hudLines.clear();
		helpLines.clear();
		textWidthCamera = null;
		hudScene = null;
		dismissed = null;

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
		hudLines.clear();
		helpLines.clear();

		hudLines.add( lineGizmo() );
		helpLines.add( lineGizmo() );

		textWidthCamera = null;
	}

	/**
	 * Creates one empty HUD line gizmo.
	 *
	 * <p>The UI camera is assigned explicitly because without it the text inherits the scene's
	 * camera, which follows the hero - so the HUD scrolls around the map and sits wherever the
	 * camera happens to be. Every other overlay in the game assigns the UI camera for the same reason.
	 */
	private BitmapText lineGizmo(){
		BitmapText text = new BitmapText( PixelScene.pixelFont );
		text.visible = true;
		text.camera = PixelScene.uiCamera;
		Game.scene().addToFront( text );
		return text;
	}

	/**
	 * Splits text into lines that each fit within {@code maxWidth}, wrapping on spaces.
	 *
	 * <p>{@code \n} in the input is an explicit break and is honoured as one. That is what makes this
	 * work at all: {@link BitmapText} has no newline glyph and no wrapping, so a newline handed
	 * straight to it is drawn as a blank and the line keeps running off the screen. Splitting here
	 * and drawing each piece as its own gizmo is the only way this class can show more than one line.
	 *
	 * <p>A word wider than the whole line - a deep seed hash on a narrow window - cannot be broken on
	 * spaces, so it is emitted whole rather than dropped. Dropping it would silently hide the seed,
	 * which is the one value identifying which recording is on screen.
	 *
	 * <p>{@code widthOf} is a parameter rather than a call to {@link PixelScene#pixelFont} so that
	 * this is testable headlessly, where there is no font at all and every measurement is zero.
	 */
	static ArrayList<String> wrapText( String text, float maxWidth,
									   java.util.function.ToDoubleFunction<String> widthOf ){
		ArrayList<String> lines = new ArrayList<>();
		if (text == null || text.isEmpty()) return lines;

		for (String paragraph : text.split( "\n", -1 )){
			StringBuilder line = new StringBuilder();
			for (String word : paragraph.split( " " )){
				String candidate = line.length() == 0 ? word : line + " " + word;
				if (line.length() > 0 && widthOf.applyAsDouble( candidate ) > maxWidth){
					lines.add( line.toString() );
					line = new StringBuilder( word );
				} else {
					if (line.length() > 0) line.append( ' ' );
					line.append( word );
				}
			}
			lines.add( line.toString() );
		}
		return lines;
	}

	/** {@link #wrapText} measured against the real UI font. */
	private static ArrayList<String> wrapText( String text, float maxWidth ){
		BitmapText probe = new BitmapText( PixelScene.pixelFont );
		return wrapText( text, maxWidth, candidate -> {
			probe.text( candidate );
			probe.measure();
			return probe.width;
		} );
	}

	/**
	 * Which corner of the window the HUD is pinned to.
	 *
	 * <p>Not a preference stored anywhere: the HUD's anchor is a property of how someone wants to
	 * watch this run, and which corner is right depends on the window and on whatever else is on
	 * screen. It cycles on a hotkey rather than being configured, because the alternative is a
	 * setting that has to be found, changed and then found again.
	 *
	 * <p>Default is {@link #TOP_LEFT}. It was {@link #BOTTOM_LEFT}, which put the block directly over
	 * the hero: at the bottom of the screen the text sits exactly where the hero is, and a recording
	 * spends most of its time on a hero that has not moved. Top left is the one corner the game keeps
	 * clear - the depth banner is centred, and the item log runs up the right edge.
	 */
	enum Anchor {
		TOP_LEFT( false, false ),
		TOP_RIGHT( true, false ),
		BOTTOM_RIGHT( true, true ),
		BOTTOM_LEFT( false, true );

		private final boolean right;
		private final boolean bottom;

		Anchor( boolean right, boolean bottom ){
			this.right = right;
			this.bottom = bottom;
		}

		boolean right(){ return right; }

		boolean bottom(){ return bottom; }

		/** The next corner in reading order, wrapping. */
		Anchor next(){
			Anchor[] all = values();
			return all[ (ordinal() + 1) % all.length ];
		}
	}

	/** Default anchor. Top left - see {@link Anchor}. Package-visible so a check can assert it. */
	static final Anchor DEFAULT_ANCHOR = Anchor.TOP_LEFT;

	/** Where the HUD is pinned right now. */
	private Anchor anchor = DEFAULT_ANCHOR;

	/** Gap between the window edge and the text, in UI pixels. */
	private static final float MARGIN = 4;

	/**
	 * Where a line of the given width starts, for an anchor.
	 *
	 * <p>A pure function of the anchor and the window so the geometry can be checked without a
	 * renderer, which is the only way it can be checked at all - the rendered viewer needs a window.
	 */
	static float anchorX( Anchor at, float cameraWidth, float lineWidth ){
		return at.right() ? cameraWidth - MARGIN - lineWidth : MARGIN;
	}

	/**
	 * Where the first line of a block starts, before any line has been drawn.
	 *
	 * <p>{@code inset} pushes the block away from the anchored edge, which is how the help line is
	 * stacked next to the HUD rather than drawn on top of it.
	 */
	static float anchorStartY( Anchor at, float cameraHeight, float inset ){
		return at.bottom() ? cameraHeight - MARGIN - inset : MARGIN + inset;
	}

	/**
	 * Draws wrapped lines outward from the anchored corner, and reports the height it used.
	 *
	 * <p>Grows the pool as needed rather than fixing a count, because how many lines the HUD needs
	 * depends on the window: the same text is one line on a wide monitor and four on a narrow one.
	 * A fixed pool silently truncated the overflow, which is the failure being fixed here.
	 *
	 * @param inset distance to push the whole block away from the anchored edge
	 * @return the height consumed, so the next block can be stacked against it
	 */
	private float drawBlock( ArrayList<BitmapText> pool, ArrayList<String> lines,
							 Anchor at, float inset ){
		while (pool.size() < lines.size()){
			pool.add( lineGizmo() );
		}

		float cameraWidth = PixelScene.uiCamera.width;
		float y = anchorStartY( at, PixelScene.uiCamera.height, inset );
		float used = 0;

		for (int i = 0; i < pool.size(); i++){
			BitmapText gizmo = pool.get( i );
			if (i >= lines.size()){
				gizmo.visible = false;
				gizmo.text( "" );
				continue;
			}
			gizmo.visible = true;
			gizmo.text( lines.get( i ) );
			gizmo.measure();
			gizmo.x = anchorX( at, cameraWidth, gizmo.width );
			if (at.bottom()){
				gizmo.y = y - gizmo.height;
				y = gizmo.y - LINE_GAP;
			} else {
				gizmo.y = y;
				y = gizmo.y + gizmo.height + LINE_GAP;
			}
			used += gizmo.height + (i > 0 ? LINE_GAP : 0 );
		}
		return used;
	}

	/** Space between two lines of the same block. */
	private static final float LINE_GAP = 1;

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
			log( "listener registered for SPACE/R/A/+/-/[/]/ESC" );
		} else {
			KeyEvent.addKeyListener( viewerKeys );
		}

		for (int key : VIEWER_KEYS){
			KeyBindings.addOverride( key, GameAction.NONE );
		}
	}

	/** The keys the viewer claims, and the reason each is listed is in {@link #bindKeys()}. */
	private static final int[] VIEWER_KEYS = {
			Input.Keys.SPACE, Input.Keys.R, Input.Keys.A, Input.Keys.PLUS, Input.Keys.EQUALS,
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
					case Input.Keys.A:
						anchor = anchor.next();
						log( "  -> HUD anchor=" + anchor );
						return true;
					case Input.Keys.ESCAPE:
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
		return GameScene.cancel();
	}

	/** Leaves the viewer and returns to the title. */
	private static void quit(){
		uninstall();
		com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon.switchNoFade(
				com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene.class );
	}

	private void layout(){
		if (hudLines.isEmpty() || PixelScene.uiCamera == null || PixelScene.pixelFont == null) return;

		if (textWidthCamera != PixelScene.uiCamera){
			maxTextWidth = PixelScene.uiCamera.width - 2 * MARGIN;
			textWidthCamera = PixelScene.uiCamera;
		}

		ReplayPlayback playback = player.playback();

		if (Dungeon.level != null){
			playback.gridWidth( Dungeon.level.width() );
		}

		ArrayList<String> wrapped = wrapText( playback.status()
				+ "\nseed " + playback.replay().seedText
				+ "   hero " + playback.replay().heroClass
				+ "\nrecorded depth " + playback.replay().depth
				+ "   actions " + playback.replay().turns
				+ "/" + playback.replay().turnLimitPerFloor
				+ "   engine time " + String.format( "%.1f", com.shatteredpixel.shatteredpixeldungeon.actors.Actor.now() )
				+ "\nscore " + String.format( "%.2f", playback.score() )
				+ "   last step " + String.format( "%+.3f", playback.stepReward() )
				+ "   +" + String.format( "%.1f", playback.gained() )
				+ " / " + String.format( "%.1f", playback.lost() )
				+ "   (final " + String.format( "%.2f", playback.replay().score ) + ")"
				+ "\nspeed " + String.format( "%.1f", player.speed() ) + "x"
				+ "   " + ( player.playing() ? "playing" : "paused" )
				+ ( player.haltReason().isEmpty() ? "" : "   " + player.haltReason() ),
				maxTextWidth );

		float used = drawBlock( hudLines, wrapped, anchor, 0 );

		drawBlock( helpLines, wrapText(
				"SPACE pause  +/- speed  [ ] coarser/finer  R restart  A anchor  ESC quit", maxTextWidth ),
				anchor, used + MARGIN );
	}

/** Releases cell input and stops driving. Called when the viewer quits. */
	public static void uninstall(){
		if (active != null) active.viewerKeys = null;
		active = null;
		GameScene.setFrameDriver( null );
		GameScene.lockCellInput( false );
		KeyBindings.clearOverrides();
	}
}
