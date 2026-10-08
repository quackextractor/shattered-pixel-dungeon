package com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless;

import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.watabou.glscripts.Script;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import com.watabou.utils.PlatformSupport;

/**
 * A noosa {@link Game} that has every rendering and audio entry point removed.
 *
 * research.md: "To achieve extremely lightweight iterations reduced purely to numbers, bypass
 * the android, ios, and desktop platform modules and operate strictly within the Java based
 * core module. You must strip out the com.watabou.noosa UI framework and libGDX rendering
 * logic."
 *
 * The game still expects a {@code Game.instance} to exist, because a great deal of core logic
 * calls the static {@code Game} helpers - {@code Game.switchingScene()} gates the actor
 * scheduler, {@code Game.runOnRenderThread} opens shop windows, {@code Game.elapsed} drives
 * tweeners. This subclass provides that instance while guaranteeing that nothing is ever
 * rasterised, decoded or played.
 *
 * The scene system is intentionally inert: {@code switchScene()} only records the request
 * (via the inherited static {@code Game.switchScene}), which is exactly the signal the RL
 * environment uses to detect that a floor transition is pending. See
 * {@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.LevelPipeline}.
 */
public class HeadlessGame extends Game {

	/** Set by {@code Game.switchScene(...)}; true means "a scene wants to take over". */
	private boolean switchRequested = false;

	/** The scene class the game most recently asked to switch to, if any. */
	private Class<? extends Scene> requestedSceneClass = null;

	public HeadlessGame(){
		super( HeadlessScene.class, new HeadlessPlatform() );
	}

	/** Idempotent; safe to call from every worker. */
	public static HeadlessGame install(){
		if (HeadlessServices.installed && Game.instance instanceof HeadlessGame){
			return (HeadlessGame) Game.instance;
		}
		HeadlessGame game = new HeadlessGame();
		game.primeStatics();
		return game;
	}

	/**
	 * Sets up the handful of noosa statics that game logic dereferences unconditionally.
	 * The default SPD size is used purely so that Camera maths stays finite; nothing is drawn.
	 */
	private void primeStatics(){
		width  = 480;
		height = 320;
		density = 1f;
		version = "";
		versionCode = 0;
		timeScale = 1f;
		elapsed = 1f/60f;
		timeTotal = 0f;

		//Camera.main is null until something calls Camera.reset(). PixelScene.shake() - reached
		//from stairs, traps and mining - dereferences it unconditionally.
		Camera.reset();

		//Every CellEmitter entry point dereferences the scene's emitter pool without checking, and
		//GameScene.emitter() returns null with no scene. A heap of gold dropping reaches it from
		//ItemSprite.drop, so the first chest opened headless died with an NPE.
		com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene.headlessEmitter(
				new com.watabou.noosa.particles.Emitter() );

		//PixelScene.uiCamera is created in PixelScene.create(), which never runs headlessly.
		//Window's constructor reads its dimensions to size a click blocker, so every dialog
		//constructed without a scene would fail here.
		if (com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene.uiCamera == null) {
			com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene.uiCamera =
					Camera.createFullscreen( 1f );
			Camera.add( com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene.uiCamera );
		}
	}

	// ---------------------------------------------------------------- libGDX callbacks
	// Each of these normally allocates GL resources. Headlessly they must not.

	@Override
	public void create(){
		//no super.create(): that queries Gdx.graphics for GLVersion and reloads textures
	}

	@Override
	public void resize(int width, int height){
		//the "real" implementation compares Gdx.gl object identity, which is null here
	}

	@Override
	public void render(){
		//the "real" implementation clears the framebuffer and draws the scene
	}

	@Override
	public void pause(){
		if (scene != null) scene.onPause();
		Script.reset();
	}

	@Override
	public void resume(){
		//no-op
	}

	@Override
	public void finish(){
		//no-op: a headless run is not a window that can be closed
	}

	@Override
	public void destroy(){
		//the "real" implementation resets Music/Sample, which would touch the audio device
	}

	// ---------------------------------------------------------------- noosa internals

	@Override
	protected void step(){
		//The "real" implementation instantiates and swaps in the requested scene. A rollout must
		//never do that: InterlevelScene would take over the turn loop. We just acknowledge the
		//request so the caller can service it.
		if (requestedReset){
			requestedReset = false;
			requestedSceneClass = sceneClass;
			switchRequested = true;
		}
	}

	@Override
	protected void draw(){
		//no-op
	}

	@Override
	protected void update(){
		//The "real" implementation polls input, updates audio and advances the scene. All three
		//are meaningless headlessly, and input processing would consume the (nonexistent) device.
		//elapsed/timeTotal are still advanced so tweeners that read them behave deterministically.
		elapsed = 1f/60f;
		timeTotal += elapsed;
	}

	@Override
	protected void switchScene(){
		//the "real" implementation destroys the old scene, clears vertexbuffers and calls create()
		//on the new one. All GL work, all skipped.
		scene = null;
		elapsed = 0f;
		timeScale = 1f;
		timeTotal = 0f;
	}

	@Override
	protected void logException(Throwable tr){
		//The "real" implementation calls Gdx.app.error. We keep the stack trace because a
		//crashing rollout is a training bug that must not be swallowed silently.
		tr.printStackTrace();
	}

	/**
	 * Verdict on render state: none of it is reachable, by design. Blending, Vertexbuffer,
	 * TextureCache, Music, Sample and PixelScene all require a live GL/audio context or an
	 * active Scene. If a future code path needs one of them, that path has leaked out of the
	 * simulation and into the renderer and should be refactored away rather than supported here.
	 */
	public static boolean renderStateRequired(){
		return false;
	}

	// ---------------------------------------------------------------- switch observation

	/** True once the game has been asked to switch scenes, until {@link #clearSwitchRequest}. */
	public boolean switchRequested(){
		return switchRequested;
	}

	/** The scene class the game last asked for. Useful for asserting we never wanted a UI scene. */
	public Class<? extends Scene> requestedSceneClass(){
		return requestedSceneClass;
	}

	/**
	 * Acknowledges the pending scene switch without running the scene. The RL environment calls
	 * this after it has serviced a floor transition itself.
	 */
	public void clearSwitchRequest(){
		switchRequested = false;
		requestedReset = false;
		requestedSceneClass = null;
	}

	/** Marker scene class. Never instantiated - {@link #step} refuses to instantiate anything. */
	public static class HeadlessScene extends Scene {
		@Override public void create() { }
	}

	/**
	 * PlatformSupport is abstract with a handful of methods the renderer calls. Headlessly only
	 * {@code supportsVibration} is ever consulted (by {@code Game.vibrate}, which the simulation
	 * never calls) but the class must be constructible to build a {@link Game}.
	 */
	public static class HeadlessPlatform extends PlatformSupport {

		@Override public void updateDisplaySize() { }
		@Override public void updateSystemUI() { }
		@Override public boolean connectedToUnmeteredNetwork() { return false; }
		@Override public boolean supportsVibration() { return false; }
		@Override public void vibrate(int ms) { }
		@Override public void setupFontGenerators(int pageSize, boolean systemFont) { }
		@Override protected FreeTypeFontGenerator getGeneratorForString(String string) { return null; }
		@Override public String[] splitforTextBlock(String string, boolean split) { return new String[]{ string }; }
	}
}
