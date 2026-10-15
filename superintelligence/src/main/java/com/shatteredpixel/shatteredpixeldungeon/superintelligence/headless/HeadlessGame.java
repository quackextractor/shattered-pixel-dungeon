package com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless;

import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
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

		GameScene.headlessGameplay( true );

		GameScene.headlessMobSprite(
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.LevelPipeline::spriteFor );
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

		Camera.reset();

		com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene.headlessEmitter(
				new com.watabou.noosa.particles.Emitter() );

		if (com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene.uiCamera == null) {
			com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene.uiCamera =
					Camera.createFullscreen( 1f );
			Camera.add( com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene.uiCamera );
		}
	}

	@Override
	public void create(){

	}

	@Override
	public void resize(int width, int height){

	}

	@Override
	public void render(){

	}

	@Override
	public void pause(){
		if (scene != null) scene.onPause();
		Script.reset();
	}

	@Override
	public void resume(){

	}

	@Override
	public void finish(){

	}

	@Override
	public void destroy(){

	}

	@Override
	protected void step(){

		if (requestedReset){
			requestedReset = false;
			requestedSceneClass = sceneClass;
			switchRequested = true;
		}
	}

	@Override
	protected void draw(){

	}

	@Override
	protected void update(){

		elapsed = 1f/60f;
		timeTotal += elapsed;
	}

	@Override
	protected void switchScene(){

		scene = null;
		elapsed = 0f;
		timeScale = 1f;
		timeTotal = 0f;
	}

	@Override
	protected void logException(Throwable tr){

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

	/**
	 * True once the game has been asked to switch scenes, until {@link #clearSwitchRequest}.
	 *
	 * <p><b>This reads the request as well as the acknowledgement, and that is the whole fix.</b>
	 * There are two signals and the difference between them is what ended every episode that reached
	 * a set of stairs:
	 *
	 * <ul>
	 *   <li>{@code Game.switchScene(...)} sets {@code requestedReset}, synchronously, from inside
	 *       whatever asked - {@code Level.activateTransition} on the way down. That is the
	 *       <i>request</i>.</li>
	 *   <li>{@link #step()} clears it and records {@link #switchRequested} instead. That is the
	 *       <i>acknowledgement</i>, and it only happens because a real game reaches {@code step()}
	 *       from {@code Game.render()}. A headless rollout never renders - that is the entire point
	 *       of {@link HeadlessGame#render()} being empty - so the acknowledgement was never raised
	 *       and the field stayed false for the whole run.</li>
	 * </ul>
	 *
	 * <p>{@code LevelPipeline.runToHeroReady} polls this to decide whether the game is handing off to
	 * a level transition, so it never saw the hand-off. {@code Actor.headlessStep()} already honours
	 * the request - it reads {@code Game.switchingScene()}, the same {@code requestedReset}, and
	 * refuses to advance - so the scheduler stopped dead while the pipeline kept asking for an
	 * acknowledgement that could not arrive. The drain then ran out its budget and reported
	 * {@code STEP_LIMIT}, which {@code SPDEnv} maps to {@code STALLED}: the hero walked onto the
	 * stairs at full health and the episode ended on the spot.
	 *
	 * <p>Measured on {@code duelist-mid}, whose last step is {@code INTERACT} onto the exit at cell
	 * 338 and whose header says {@code termination=STALLED}. It is why no rollout had ever reported a
	 * depth above 1 - {@code TODO.md} 1.4, open across every run - and why the rendered viewer, which
	 * <i>does</i> render and therefore <i>does</i> descend, showed the hero on a new floor while the
	 * recording declared the run over.
	 */
	public boolean switchRequested(){
		return switchRequested || requestedReset;
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
