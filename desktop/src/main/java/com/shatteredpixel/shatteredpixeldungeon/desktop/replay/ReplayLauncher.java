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

import com.badlogic.gdx.Files;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3FileHandle;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Preferences;
import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopPlatformSupport;
import com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopWindowListener;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.watabou.noosa.Game;
import com.watabou.utils.FileUtils;
import com.watabou.utils.Point;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Entry point for watching a replay in the rendered game.
 *
 * <pre>
 *   gradle :desktop:replay --args="--file run.replay"
 * </pre>
 *
 * The run is set up the same way a hand-started run is, because a recording only has to name a
 * seed and a hero: {@link ReplayIO#verify} already proved the engine reproduces those exactly, so
 * if the seed is applied correctly the rest follows.
 *
 * <pre>
 *   SPDSettings.customSeed( seed ) -&gt; Dungeon.initSeed() -&gt; Dungeon.init()
 * </pre>
 *
 * then {@link InterlevelScene#Mode#DESCEND} builds floor 1 and hands off to the game scene, which
 * {@link ReplayScene} has made the entry point.
 *
 * The seed is verified rather than assumed. {@code SPDSettings.customSeed()} reads with a 20
 * character cap and {@code GameSettings.getString(key, def, maxLength)} silently discards anything
 * longer, so an over-long seed would produce a run that looks fine and does not match the recording.
 * The viewer refuses instead. The headless side enforces the same rule - see
 * {@code LevelPipeline.requireSeedSticks}.
 */
public class ReplayLauncher {

	/** Mirrors the cap SPDSettings reads the custom seed with. */
	private static final int MAX_SEED_TEXT_LENGTH = 20;

	private static File file;

	public static void main( String[] args ){
		if (!parseArgs( args )){
			usage();
			System.exit( 1 );
			return;
		}

		if (!file.isFile()){
			System.err.println( "[ERROR] no replay at " + file.getAbsolutePath() );
			System.exit( 1 );
			return;
		}

		Replay replay;
		try {
			replay = ReplayIO.read( file );
		} catch (IOException e){
			System.err.println( "[ERROR] could not read replay: " + e.getMessage() );
			System.exit( 1 );
			return;
		}

		describe( replay );

		//The window config has to be built before anything touches SPDSettings: customSeed() writes
		//through libGDX preferences, and Gdx.app only exists once the game is running. So the
		//preferences object is created here, the seed is applied, and only then is the application
		//started - after which the game scene takes over as the entry point.
		Lwjgl3ApplicationConfiguration config = windowConfig();

		if (!startRun( replay )){
			System.exit( 1 );
			return;
		}

		ReplayController.install( new ReplayPlayer( replay ) );

		//the game enters through the interlevel scene, which builds floor 1 and hands off to the
		//game scene. That transition is hardcoded, so entering at the game scene directly would
		//arrive before floor 1 existed.
		Game.setSceneClass( InterlevelScene.class );

		new Lwjgl3Application( new ShatteredPixelDungeon( new DesktopPlatformSupport() ), config );
	}

	private static void describe( Replay replay ){
		System.out.println( "replay   " + file.getAbsolutePath() );
		System.out.println( "  seed   " + ( replay.seedText.isEmpty() ? "<random>" : replay.seedText ) );
		System.out.println( "  hero   " + replay.heroClass );
		System.out.println( "  steps  " + replay.steps.size() );
		System.out.println( "  score  " + String.format( "%.2f", replay.score )
				+ "   depth " + replay.depth + "   turns " + replay.turns );
		System.out.println( "  keys   SPACE pause   +/- speed   R restart   ESC quit" );
		System.out.println();
	}

	/**
	 * Seeds and builds floor 1, so the game scene has a live run to play back into.
	 *
	 * @return false if the seed was rejected, in which case nothing should be launched
	 */
	private static boolean startRun( Replay replay ){
		String seed = replay.seedText == null ? "" : replay.seedText;

		if (seed.length() > MAX_SEED_TEXT_LENGTH){
			System.err.println( "[ERROR] this recording's seed is " + seed.length()
					+ " characters; the game caps custom seed text at " + MAX_SEED_TEXT_LENGTH
					+ " and silently discards anything longer." );
			System.err.println( "        Refusing to play a run that would not match the recording." );
			return false;
		}

		HeroClass heroClass;
		try {
			heroClass = HeroClass.valueOf( replay.heroClass );
		} catch (IllegalArgumentException e){
			heroClass = HeroClass.WARRIOR;
		}

		SPDSettings.customSeed( seed );

		String stored = SPDSettings.customSeed();
		if (!seed.equals( stored )){
			System.err.println( "[ERROR] seed was not applied: asked for \"" + seed
					+ "\", settings hold \"" + stored + "\"" );
			return false;
		}

		GamesInProgress.selectedClass = heroClass;
		Dungeon.hero = null;
		Dungeon.daily = Dungeon.dailyReplay = false;
		Dungeon.initSeed();

		//DESCEND with no hero built is exactly how a first floor is entered in normal play, so the
		//viewer starts on the same path a player does. InterlevelScene then hands off to GameScene,
		//which is where the recording is pumped from.
		InterlevelScene.mode = InterlevelScene.Mode.DESCEND;

		return true;
	}

	/**
	 * Window setup, mirroring {@code DesktopLauncher}.
	 *
	 * Duplicated rather than extracted because the desktop launcher also installs the crash
	 * dialog, the update service and the news service, none of which a replay viewer should be
	 * talking to the network about.
	 */
private static Lwjgl3ApplicationConfiguration windowConfig(){
		//DeviceCompat.isDebug() reads this and InterlevelScene.create() calls it on the way in, so
		//it has to be set before the game starts or the first scene throws on a null.
		//
		//Deliberately NOT "INDEV". isDebug() zeroes the loading fade, which is tempting, but it
		//also makes InterlevelScene.descend() pre-generate every prior floor to keep levelgen
		//consistent with a save - and with the hero null on a first run that is a loop over depths
		//that produces nothing useful and a 10s levelgen warning. The splash is short enough.
		Game.version = System.getProperty( "Implementation-Version" );
		if (Game.version == null) Game.version = "4.0.1";
		try {
			Game.versionCode = Integer.parseInt( Game.version );
		} catch (NumberFormatException e){
			Game.versionCode = 401;
		}

		Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
		config.setTitle( "Shattered Pixel Dungeon - replay" );

		String basePath;
		Files.FileType baseFileType;

		if (SharedLibraryLoader.os == Os.Windows){
			basePath = "AppData/Roaming/." + vendor() + "/Shattered Pixel Dungeon/";
			baseFileType = Files.FileType.External;
		} else if (SharedLibraryLoader.os == Os.MacOsX){
			basePath = "Library/Application Support/Shattered Pixel Dungeon/";
			baseFileType = Files.FileType.External;
		} else {
			String xdgHome = System.getenv( "XDG_DATA_HOME" );
			if (xdgHome == null) xdgHome = System.getProperty( "user.home" ) + "/.local/share";
			basePath = xdgHome + "/." + vendor() + "/shattered-pixel-dungeon/";
			baseFileType = Files.FileType.Absolute;
		}

		config.setPreferencesConfig( basePath, baseFileType );
		SPDSettings.set( new Lwjgl3Preferences(
				new Lwjgl3FileHandle( basePath + SPDSettings.DEFAULT_PREFS_FILE, baseFileType ) ) );
		FileUtils.setDefaultFileProperties( baseFileType, basePath );

		config.setWindowSizeLimits( 720, 400, -1, -1 );
		Point p = SPDSettings.windowResolution();
		config.setWindowedMode( p.x, p.y );
		config.setMaximized( SPDSettings.windowMaximized() );
		config.setWindowListener( new DesktopWindowListener() );
		config.setWindowIcon( "icons/icon_16.png", "icons/icon_32.png", "icons/icon_48.png",
				"icons/icon_64.png", "icons/icon_128.png", "icons/icon_256.png" );

		return config;
	}

	private static String vendor(){
		String vendor = ReplayLauncher.class.getPackage().getImplementationTitle();
		if (vendor == null) vendor = System.getProperty( "Implementation-Title" );
		if (vendor == null || vendor.indexOf( '.' ) < 0) return "shatteredpixel";
		return vendor.split( "\\." )[ 1 ];
	}

	private static boolean parseArgs( String[] args ){
		file = null;

		for (int i = 0; i < args.length; i++){
			if (args[ i ].equals( "--file" ) && i + 1 < args.length){
				file = new File( args[ ++i ] );
			} else if (!args[ i ].startsWith( "--" ) && file == null){
				//a bare path is accepted, since that is what people actually type
				file = new File( args[ i ] );
			}
		}

		return file != null;
	}

	private static void usage(){
		System.out.println( "Shattered Pixel Dungeon - replay viewer" );
		System.out.println();
		System.out.println( "  gradle :desktop:replay --args=\"--file <replay>\"" );
		System.out.println();
		System.out.println( "options:" );
		System.out.println( "  --file <path>   replay to play (required; a bare path also works)" );
	}
}
