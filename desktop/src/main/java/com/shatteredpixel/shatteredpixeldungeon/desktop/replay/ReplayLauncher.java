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

		//Kept so ReplayController can close the window when playback ends. Game.finish() is the game's
		//own exit path and needs an instance; the instance is only created here.
		ShatteredPixelDungeon game = new ShatteredPixelDungeon( new DesktopPlatformSupport() );
		ReplayController.gameInstance( game );

		new Lwjgl3Application( game, config );
	}

private static void describe( Replay replay ){
		System.out.println( "replay   " + file.getAbsolutePath() );
		System.out.println( "  seed   " + ( replay.seedText.isEmpty() ? "<random>" : replay.seedText ) );
		System.out.println( "  hero   " + replay.heroClass );
		System.out.println( "  steps  " + replay.steps.size() );

		//"actions", not "turns" - see the note in ReplayController.layout. Replay.turns is a count of
		//the agent's decisions, and the engine's own turn clock is Actor.now(), which is fractional and
		//is not the same quantity. Printing one under the other's name is what made the turn limit read
		//as a budget of game turns.
		System.out.println( "  depth  " + replay.depth
				+ "   actions " + replay.turns + "/" + replay.turnLimitPerFloor
				+ ( replay.termination.isEmpty() ? "" : "   ends " + replay.termination ) );

		//Score split into its two halves, which the recording already carries per step. A single net
		//number cannot distinguish a run that gained steadily from one that gained a great deal and gave
		//most of it back, and watching that happen is the point of watching a recording.
		double gained = 0, lost = 0;
		for (Replay.Step step : replay.steps){
			if (step.reward > 0) gained += step.reward;
			else lost += step.reward;
		}
		System.out.println( "  score  " + String.format( "%.2f", replay.score )
				+ "   gained " + String.format( "%.2f", gained )
				+ "   lost " + String.format( "%.2f", lost ) );
		System.out.println( "  keys   SPACE pause  +/- speed  [ ] coarser/finer  R restart  ESC quit" );
		System.out.println();
	}

	/**
	 * Seeds and builds floor 1, so the game scene has a live run to play back into.
	 *
	 * @return false if the seed was rejected, in which case nothing should be launched
	 */
	static boolean startRun( Replay replay ){
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

		//without this the loading screen parks on the region story with a "continue" button and
		//waits for a click that never comes
		InterlevelScene.autoContinue = true;

		return true;
	}

	/**
	 * Window setup, mirroring {@code DesktopLauncher}.
	 *
	 * Duplicated rather than extracted because the desktop launcher also installs the crash
	 * dialog, the update service and the news service, none of which a replay viewer should be
	 * talking to the network about.
	 */
static Lwjgl3ApplicationConfiguration windowConfig(){
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

//An explicit file root wins over the platform default. Set by ViewCheck, which runs several
		//playbacks at once and cannot have them sharing one preferences file and one save directory:
		//the game writes preferences and a bones.dat there on the way in, and two children writing the
		//same file produced GdxRuntimeException "Error copying source file ... .spdtmp" on any run that
		//had more than one child in flight. Isolated roots also stop a gate run from touching whatever
		//the developer's own game state is, which a shared default does on a machine that has played.
		String rootOverride = System.getProperty( "spd.fileRoot" );
		if (rootOverride != null && !rootOverride.trim().isEmpty()){
			String root = rootOverride.trim();
			if (!root.endsWith( "/" ) && !root.endsWith( File.separator )) root = root + "/";
			basePath = root;
			baseFileType = Files.FileType.Absolute;

		} else if (SharedLibraryLoader.os == Os.Windows){
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

if (!new File( basePath ).isDirectory() && !new File( basePath ).mkdirs()){
			//Not fatal - the game creates the directory itself when it saves - but it means nothing can be
			//read back, so the window mode below is decided from defaults rather than from preferences. A
			//caller who pointed spd.fileRoot at somewhere unwritable gets a surprising window rather than
			//a clear failure, so it is reported.
			System.err.println( "[replay] cannot create the file root at " + basePath
					+ "; falling back to default preferences" );
		}

		config.setPreferencesConfig( basePath, baseFileType );
		SPDSettings.set( new Lwjgl3Preferences(
				new Lwjgl3FileHandle( basePath + SPDSettings.DEFAULT_PREFS_FILE, baseFileType ) ) );
		FileUtils.setDefaultFileProperties( baseFileType, basePath );

		//Fullscreen off, written into the preferences rather than requested of the window configuration.
		//
		//Every window call below is too early to stop this. The game goes fullscreen from its own
		//create() - ShatteredPixelDungeon.updateSystemUI -> DesktopPlatformSupport.updateSystemUI ->
		//Gdx.graphics.setFullscreenMode - which runs once the GL context exists, after this method has
		//returned. It decides by reading SPDSettings.fullscreen(), and that getter defaults to TRUE for a
		//key that is not present. So an isolated file root, which has no such key, produced a fullscreen
		//window on every run: a real window, on the primary monitor, that then took the desktop away from
		//whoever started the gate.
		//
		//GLFW_VISIBLE is ignored for fullscreen windows and glfwHideWindow does nothing to them, so once
		//that switch happens no flag set here can undo it. The only place that can be stopped is the
		//preference it reads.
		//
		//SPDSettings.put, not SPDSettings.fullscreen(false): the setter calls updateSystemUI() as a side
		//effect, which dereferences Gdx.app, and there is no application yet at this point. GameSettings.put
		//only writes and flushes, which is what is wanted here.
		if (WINDOWED || HIDDEN || MONITOR_X != null || MONITOR_Y != null){
			SPDSettings.put( SPDSettings.KEY_FULLSCREEN, false );
		}

		//The tutorial off, always - not only for a gate run.
		//
		//SPDSettings.intro() defaults to true and Hunger.act() returns early while it is set, which
		//freezes the hero's hunger clock for the whole run. So the world a recording describes is a world
		//whose hero never starves, and playing that recording back with the intro on is not playing it.
		//
		//This was a real gate failure and not a hypothetical one. The viewer used to inherit intro=false
		//from the developer's own settings.xml by luck; give it an isolated preferences file and the
		//default applies, and recordings that had been passing started diverging several steps in. The
		//trainer already sets this explicitly for exactly this reason, and the comment there names the
		//same cause - see LevelPipeline.startRun. Both sides of a replay comparison must state the setting
		//rather than inherit it, or the comparison is between two different games.
		//
		//Unconditional because a recording is never of the tutorial: the trainer cannot produce one.
		SPDSettings.put( SPDSettings.KEY_INTRO, false );

config.setWindowSizeLimits( 720, 400, -1, -1 );

//A gate run must never take over the desktop, whatever the saved preferences say. ViewCheck passes
		//-Dspd.windowed and -Dspd.hidden precisely so an unattended run cannot cover the display it was
		//started from, and a saved "maximised" preference would otherwise defeat that. So the mode is
		//stated rather than asked about. HIDDEN still creates a real GL context - the render loop is what
		//is being tested, not the pixels. None of this is sufficient on its own; see the preference write
		//above for the switch that actually fullscreens the game.
		boolean placed = MONITOR_X != null || MONITOR_Y != null;

		if (placed || WINDOWED || HIDDEN){
			config.setWindowedMode( WINDOW_SIZE.x, WINDOW_SIZE.y );
			config.setMaximized( false );
			config.setInitialVisible( !HIDDEN );
		} else {
			Point p = SPDSettings.windowResolution();
			config.setWindowedMode( p.x, p.y );
			config.setMaximized( SPDSettings.windowMaximized() );
		}

		config.setWindowListener( new DesktopWindowListener() );
		config.setWindowIcon( "icons/icon_16.png", "icons/icon_32.png", "icons/icon_48.png",
				"icons/icon_64.png", "icons/icon_128.png", "icons/icon_256.png" );

		if ( MUTE ){
			config.disableAudio( true );
		}

		//Position is given rather than looked up: a monitor cannot be enumerated before the window
		//exists, since Graphics.getMonitors() needs a running GL context.
		if (placed){
			int x = MONITOR_X == null ? 0 : MONITOR_X;
			int y = MONITOR_Y == null ? 0 : MONITOR_Y;
			config.setWindowPosition( x, y );
		}

		//Every option is stated, so what the run is actually doing is never a guess. Silence and
		//window closing in particular are easy to forget to ask for, and were both missed in practice.
		//States what this process asked for, not what it ended up with. The window can still be made
		//fullscreen later by the game itself, from a preference, after the context exists - which is why
		//the fullscreen preference is written above and why the diagnostic is not evidence of the final
		//mode. An earlier version of this line printed windowed=true while the window was fullscreen, and
		//was believed.
		System.err.println( "[replay] audio=" + (MUTE ? "off" : "on")
				+ " speed=" + speedHint()
				+ " windowed=" + (WINDOWED || placed)
				+ " hidden=" + HIDDEN
				+ " fullscreenPref=" + (WINDOWED || HIDDEN || placed ? "off" : "from settings")
				+ " position=" + (placed ? String.valueOf(MONITOR_X) + "," + String.valueOf(MONITOR_Y) : "default")
				+ " closeOnDiverge=" + AUTO_CLOSE_ON_DIVERGE
				+ " closeAlways=" + AUTO_CLOSE );

		return config;
	}

	/**
	 * Window position, set with {@code -Dspd.monitorX} and {@code -Dspd.monitorY}.
	 *
	 * <p>Coordinates rather than a monitor number: a monitor cannot be enumerated before the window
	 * exists, so the caller supplies where the second display starts.
	 */
	private static final Integer MONITOR_X = intProperty( "spd.monitorX" );
	private static final Integer MONITOR_Y = intProperty( "spd.monitorY" );

	/**
	 * Window size used when the mode is stated rather than read from preferences.
	 *
	 * <p>Fixed rather than {@code SPDSettings.windowResolution()}, because that reads a file which a
	 * gate run may have just created empty - and a missing or unreadable preferences file must not be what
	 * decides how big a window an unattended run puts on the desktop.
	 */
	private static final Point WINDOW_SIZE = new Point( 800, 600 );

	/** Mute everything, so an unattended run is silent. Enabled with {@code -Dspd.mute}. */
	private static final boolean MUTE = System.getProperty( "spd.mute" ) != null;

	/** Start as a normal window even if the saved preference says maximised. */
	private static final boolean WINDOWED = System.getProperty( "spd.windowed" ) != null;

	/** Start with the window not shown at all. */
	private static final boolean HIDDEN = System.getProperty( "spd.hidden" ) != null;

	private static final boolean AUTO_CLOSE = System.getProperty( "spd.autoClose" ) != null;

	private static final boolean AUTO_CLOSE_ON_DIVERGE = System.getProperty( "spd.autoCloseOnDiverge" ) != null;

	private static String speedHint(){
		return System.getProperty( "spd.fast" ) == null ? "1" : System.getProperty( "spd.fast" );
	}

	private static Integer intProperty( String name ){
		String raw = System.getProperty( name );
		if (raw == null ) return null;
		try {
			return Integer.valueOf( raw.trim() );
		} catch (NumberFormatException e){
			return null;
		}
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
