package com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless;

import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.glscripts.Script;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Scene;
import com.watabou.utils.PlatformSupport;


public class HeadlessGame extends Game {


	private boolean switchRequested = false;


	private Class<? extends Scene> requestedSceneClass = null;

	public HeadlessGame(){
		super( HeadlessScene.class, new HeadlessPlatform() );
	}


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


	public static boolean renderStateRequired(){
		return false;
	}


	public boolean switchRequested(){
		return switchRequested || requestedReset;
	}


	public Class<? extends Scene> requestedSceneClass(){
		return requestedSceneClass;
	}


	public void clearSwitchRequest(){
		switchRequested = false;
		requestedReset = false;
		requestedSceneClass = null;
	}


	public static class HeadlessScene extends Scene {
		@Override public void create() { }
	}


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
