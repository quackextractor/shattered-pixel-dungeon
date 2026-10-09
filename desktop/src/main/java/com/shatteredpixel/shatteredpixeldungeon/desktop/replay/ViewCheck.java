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

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.desktop.DesktopPlatformSupport;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.watabou.noosa.Game;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Plays the committed corpus through the real viewer and fails on any divergence.
 *
 * <p>{@link PlaybackCheck} exists because the viewer's own logic could not be run in a test. It calls
 * {@link ReplayPlayer#update(float)} directly in a loop. That is not what the viewer does, and the
 * difference was worth an entire class of bug.
 *
 * <p>The viewer installs itself as the game scene's <i>frame driver</i>:
 * {@code GameScene.setFrameDriver( ReplayController::pump )}, and {@code GameScene} calls that driver
 * from inside its own update. So the shipped path is
 * {@code GameScene.update -> pump -> player.update( Game.elapsed )}, driven by real frame timing, in a
 * real scene with real UI. Every one of those is absent from a direct call.
 *
 * <p>What that costs is a scheduler the viewer does not own. {@code GameScene.update} advances the
 * world's actors on its own schedule, alongside the drain {@link ReplayPlayer} performs to reach the
 * hero. Mobs get to act on frames that are not applying a recorded step. The recording was made
 * against a trainer that only ever advances the scheduler deliberately, so the viewer drifts: on
 * {@code warrior-mid} the hero stands at {@code pos=492} for step 16 and takes a hit the trainer
 * never permitted, and the viewer reports {@code hp is 19, recording says 20}. {@code playbackcheck}
 * passes that same recording, because the fault is in what surrounds {@code player.update}, not in it.
 *
 * <p>So this gate runs the recording the way a person runs it, and the window is never shown. The
 * visible window is not a detail: the loop must be the render loop, because the scheduler advance
 * being tested happens in it. {@code -Dspd.hidden} keeps it off the desk; it still creates a real GL
 * context, which is the whole point.
 *
 * <pre>
 *   gradle :desktop:viewcheck
 *   gradle :desktop:viewcheck -Pspd.replayDir=some\other\corpus
 * </pre>
 *
 * <p>Each recording plays in its own JVM, because a diverged run leaves a live GL context and a
 * half-played scene behind, and because a viewer that cannot close its window would otherwise hang the
 * gate forever. That is a fork per file, so the parent does nothing but supervise.
 */
public class ViewCheck {

	/** Playback speed for the gate. Pacing only; the path taken is the same at 1x. */
	private static final float GATE_SPEED = 40f;

	/** Ceiling on a single playback, so a stuck viewer fails instead of hanging the gate. */
	private static final long CHILD_TIMEOUT_MS = 120_000;

	public static void main( String[] args ) throws Exception {
		List< String > parsed = new ArrayList<>( Arrays.asList( args ));

		int one = parsed.indexOf( "--one" );
		if (one >= 0 && one + 1 < parsed.size()){
			playOne( new File( parsed.get( one + 1 )) );
			return;
		}

		int dir = parsed.indexOf( "--dir" );
		File corpus = new File( dir >= 0 && dir + 1 < parsed.size()
				? parsed.get( dir + 1 )
				: System.getProperty( "spd.replayDir", "replays" ) );

		supervise( corpus );
	}

	// --- parent ------------------------------------------------------------

	/**
	 * Plays every recording in a child JVM and reports.
	 *
	 * <p>Sequential on purpose. Each child wants a GL context, and forking them in parallel turns a
	 * 20 second gate into a machine that cannot composite its own windows.
	 */
	private static void supervise( File corpus ) throws IOException, InterruptedException {
		File[] files = corpus.listFiles( ( dir, name ) -> name.endsWith( ".replay" ) );
		if (files == null || files.length == 0){
			System.err.println( "[ERROR] no recordings in " + corpus.getAbsolutePath() );
			System.exit( 1 );
			return;
		}
		Arrays.sort( files, Comparator.comparing( File::getName ) );

		List< String > failed = new ArrayList<>();

		for (File file : files){
			int code = runChild( file );
			if (code != 0) failed.add( file.getName() );
		}

		if (failed.isEmpty()){
			System.out.println( "[OK]     rendered playback: " + files.length
					+ " recordings played clean in the real viewer" );
			return;
		}

		System.out.println( "[ERROR]  rendered playback: " + failed.size()
				+ " of " + files.length + " recordings diverged" );
		for (String name : failed) System.out.println( "        " + name );
		System.exit( 1 );
	}

	/** Forks one playback and waits for it, killing it if it overruns. */
	private static int runChild( File file ) throws IOException, InterruptedException {
		String javaBin = System.getProperty( "java.home" )
				+ File.separator + "bin" + File.separator + "java";

		List< String > command = new ArrayList<>( Arrays.asList(
				javaBin,
				"-cp", System.getProperty( "java.class.path" ),
				//no audio device is guaranteed on a build machine, and an unhandled one aborts the
				//context rather than degrading
				"-Dspd.mute=1",
				//the window must exist to own the render loop; it just must not be looked at
				"-Dspd.windowed=1", "-Dspd.hidden=1",
				//close on completion so the child exits on its own instead of waiting for a keypress
				"-Dspd.autoClose=1",
				"-Dspd.fast=" + (int) GATE_SPEED,
				"--enable-native-access=ALL-UNNAMED",
				ViewCheck.class.getName(),
				"--one", file.getAbsolutePath()
		));

		Process child = new ProcessBuilder( command ).inheritIO().start();
		if (!child.waitFor( CHILD_TIMEOUT_MS, TimeUnit.MILLISECONDS )){
			child.destroyForcibly();
			System.err.println( "[ERROR]  " + file.getName() + " did not finish within "
					+ ( CHILD_TIMEOUT_MS / 1000 ) + "s; the viewer never stopped" );
			return 1;
		}
		return child.exitValue();
	}

	// --- child -------------------------------------------------------------

	/**
	 * Plays one recording in the real viewer and exits with the verdict.
	 *
	 * <p>Almost all of this is {@link ReplayLauncher}'s bootstrap, called rather than copied: the seed
	 * has to be applied through settings before the context exists, and floor 1 has to be built by the
	 * interlevel scene because the game's own transition is hardcoded. Duplicating that here would be a
	 * second copy to drift.
	 */
	private static void playOne( File file ) throws IOException {
		if (!file.isFile()){
			System.err.println( "[ERROR] no replay at " + file.getAbsolutePath() );
			Runtime.getRuntime().halt( 1 );
			return;
		}

		Replay replay = ReplayIO.read( file );

		Lwjgl3ApplicationConfiguration config = ReplayLauncher.windowConfig();
		if (!ReplayLauncher.startRun( replay )){
			Runtime.getRuntime().halt( 1 );
			return;
		}

		ReplayPlayer player = new ReplayPlayer( replay );
		player.speed( GATE_SPEED );
		ReplayController.install( player );

		Game.setSceneClass( InterlevelScene.class );

		ShatteredPixelDungeon game = new ShatteredPixelDungeon( new DesktopPlatformSupport() );
		ReplayController.gameInstance( game );

		watch( player, file );

		//returns only once the loop is up; the watcher thread decides the exit code from here
		new Lwjgl3Application( game, config );
	}

	/**
	 * Waits for playback to stop, then reports and halts the process.
	 *
	 * <p>A thread rather than a hook because there is no callback for "playback finished": the viewer
	 * reports it from inside the frame it happens in. Polling {@code playing()} is the same thing the
	 * viewer already does with it.
	 *
	 * <p>{@code halt} rather than {@code exit}: the GL context and its threads are still up, and a
	 * normal exit waits on them.
	 */
	private static void watch( ReplayPlayer player, File file ){
		Thread watcher = new Thread( () -> {
			while (player.playing()){
				try {
					Thread.sleep( 50 );
				} catch (InterruptedException e){
					return;
				}
			}

			ReplayPlayback playback = player.playback();
			boolean clean = !playback.diverged();

			if (clean){
				System.out.println( "[viewcheck] ok    " + file.getName()
						+ "  (" + playback.cursor() + "/" + playback.total() + " steps)" );
			} else {
				System.out.println( "[viewcheck] FAIL  " + file.getName()
						+ "  " + player.haltReason() );
			}
			System.out.flush();
			System.err.flush();

			Runtime.getRuntime().halt( clean ? 0 : 1 );
		}, "viewcheck" );

		watcher.setDaemon( true );
		watcher.start();
	}
}