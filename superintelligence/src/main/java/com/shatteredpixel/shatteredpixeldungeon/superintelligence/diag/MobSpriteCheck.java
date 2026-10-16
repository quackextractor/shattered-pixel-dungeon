package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.MirrorImage;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfMirrorImage;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MobSpriteCheck {

	private static final int CHECKS = 3;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		checkMirrorImageIsUsable();
		checkTheImagesTakePart();
		checkARenderedGameNeverReachesTheBranch();

		if (failures.isEmpty()){
			System.out.println( "[OK]     item-created mobs: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  item-created mobs: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	private static void checkMirrorImageIsUsable(){
		List< MirrorImage > images = readTheScroll( "MOBSPROUT-A" );

		if (lastThrown != null ){
			fail( "reading a Scroll of Mirror Image threw " + lastThrown.getClass().getName()
					+ ": " + lastThrown.getMessage() + " at " + topFrame( lastThrown )
					+ ". GameScene.add(Mob) gives a mob a sprite only when a scene exists, so an"
					+ " item-created mob reaches Char.move with none and the next move is an NPE."
					+ " This is the crash a training run hit." );
			return;
		}

		if (images.isEmpty()){
			fail( "using a Scroll of Mirror Image created no MirrorImage on the level. The fixture did not"
					+ " reach the code it guards - either the scroll was refused, or it spawned nothing"
					+ " because every neighbouring cell was occupied." );
			return;
		}

		int withoutSprite = 0;
		for (MirrorImage image : images){
			if (image.sprite == null) withoutSprite++;
		}

		if (withoutSprite > 0 ){
			fail( withoutSprite + " of " + images.size() + " MirrorImages have no sprite after the scroll was"
					+ " read. GameScene.add(Mob) gives one only when a scene exists, and Char.move assigns"
					+ " sprite.visible unconditionally - so the next move of any of them is an NPE." );
			return;
		}

		System.out.println( "  " + images.size() + " mirror image(s) created by a scroll, every one with a"
				+ " sprite" );
	}

	private static void checkTheImagesTakePart(){
		List< MirrorImage > images = readTheScroll( "MOBSPROUT-B" );

		if (lastThrown != null ){
			fail( "reading a Scroll of Mirror Image threw " + lastThrown.getClass().getName()
					+ " at " + topFrame( lastThrown ) );
			return;
		}

		if (images.isEmpty()){
			fail( "the second run created no MirrorImage, so nothing was checked." );
			return;
		}

		int offStage = 0;
		for (MirrorImage image : images){
			if (Actor.findChar( image.pos ) != image ) offStage++;
		}

		if (offStage > 0 ){
			fail( offStage + " of " + images.size() + " MirrorImages are not in Actor.chars. Without Actor.add"
					+ " they are in level.mobs and nowhere else: they never act, and Hero.handle's"
					+ " findChar cannot see a mob on the hero's own cell." );
			return;
		}

		System.out.println( "  every mirror image is in level.mobs and in Actor.chars, so it acts and the"
				+ " hero can walk into it" );
	}

	private static void checkARenderedGameNeverReachesTheBranch(){
		final int[] consulted = { 0 };

		boolean wasHeadless = GameScene.headlessGameplay();
		java.util.function.Consumer< com.shatteredpixel.shatteredpixeldungeon.actors.Char > previous = null;
		GameScene.headlessGameplay( false );
		GameScene.headlessMobSprite( ch -> consulted[ 0 ]++ );

		try {
			SPDEnv env = freshRun( "MOBSPROUT-C" );
			Mob probe = Dungeon.level.createMob();
			if (probe == null){
				fail( "the level produced no mob to add, so the branch was never reached with the flag off."
						+ " The case cannot say whether a rendered game would consult it." );
				return;
			}

			GameScene.add( probe, 0f );
		} catch (RuntimeException e){
			fail( "GameScene.add(mob) threw with the headless flag off: " + e );
			return;
		} finally {
			GameScene.headlessGameplay( wasHeadless );
			GameScene.headlessMobSprite( previous );
		}

		if (consulted[ 0 ] != 0 ){
			fail( "GameScene.add(mob) consulted the headless sprite factory " + consulted[ 0 ]
					+ " time(s) with headlessGameplay off. Nothing sets that flag when a renderer is"
					+ " present, so this branch is unreachable in the game - and this case is what says so"
					+ " rather than the comment on it." );
			return;
		}

		System.out.println( "  with the headless flag off, GameScene.add(mob) never consults the stand-in"
				+ " factory" );
	}

	private static Throwable lastThrown;

	private static List< MirrorImage > readTheScroll( String seed ){
		lastThrown = null;
		try {
			return useTheScroll( seed );
		} catch (Throwable t){
			lastThrown = t;
			return new ArrayList<>();
		}
	}

	private static List< MirrorImage > useTheScroll( String seed ){
		SPDEnv env = freshRun( seed );

		ScrollOfMirrorImage scroll = new ScrollOfMirrorImage();
		scroll.identify();
		Dungeon.hero.belongings.backpack.items.add( scroll );

		if (env.running() && env.mode() == EnvMode.WORLD) env.step( Action.WAIT, 0 );

		int slot = -1;
		List< com.shatteredpixel.shatteredpixeldungeon.items.Item > slots = env.mapper().slots();
		for (int i = 0; i < slots.size(); i++){
			if (slots.get( i ) == scroll ) slot = i;
		}

		if (slot >= 0 ){
			env.step( Action.USE, 0 );
			if (env.mode() == EnvMode.SLOT) env.step( Action.USE, slot );
		}

		List< MirrorImage > images = new ArrayList<>();
		for (Mob mob : Dungeon.level.mobs){
			if (mob instanceof MirrorImage) images.add( (MirrorImage) mob );
		}
		return images;
	}

	private static SPDEnv freshRun( String seed ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-mobspritecheck" ));
		HeadlessServices.disableSaving( true );

		SPDEnv env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );
		env.reset( seed, HeroClass.WARRIOR );

		for (int i = 0; i < 3 && env.running(); i++){
			if (env.mode() == EnvMode.WORLD) env.step( Action.WAIT, 0 );
			else env.step( Action.CANCEL, 0 );
		}
		return env;
	}

	private static void fail( String message ){
		failures.add( message );
	}

	private static String topFrame( Throwable t ){
		for (StackTraceElement frame : t.getStackTrace()){
			if (frame.getClassName().startsWith( "com.shatteredpixel." )){
				return frame.getClassName().substring( frame.getClassName().lastIndexOf( '.' ) + 1 )
						+ "." + frame.getMethodName() + ":" + frame.getLineNumber();
			}
		}
		return "(no frame in this project)";
	}
}