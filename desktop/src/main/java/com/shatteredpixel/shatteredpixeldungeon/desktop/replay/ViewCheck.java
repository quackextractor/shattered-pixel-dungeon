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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

	/**
	 * Removes a child's scratch directory, and says so when it cannot.
	 *
	 * <p>Deliberately quiet on failure. A locked file on Windows is a leftover temp directory, not a
	 *failed gate, and a gate that reports a warning nobody can act on trains people to ignore warnings.
	 */
	private static void deleteRecursively( File dir ){
		if (!dir.exists()) return;

		File[] children = dir.listFiles();
		if (children != null){
			for (File child : children) deleteRecursively( child );
		}

		if (!dir.delete()){
			dir.deleteOnExit();
		}
	}

	/** A {@code -Dflag=value} argument, or null when the property is unset. */
	private static String optional( String flag, String value ){
		return value == null || value.trim().isEmpty() ? null : flag + "=" + value.trim();
	}

	// --- parent ------------------------------------------------------------

	/**
	 * Plays every recording in a child JVM and reports.
	 *
	 * <p>One child at a time by default, and parallelising is opt-in via {@code VIEWCHECK_JOBS} rather
	 * than the other way round — see {@link #parallelism()} for why the default is one.
	 */
	private static void supervise( File corpus ) throws IOException, InterruptedException {
		File[] files = corpus.listFiles( ( dir, name ) -> name.endsWith( ".replay" ) );
		if (files == null || files.length == 0){
			System.err.println( "[ERROR] no recordings in " + corpus.getAbsolutePath() );
			System.exit( 1 );
			return;
		}
		Arrays.sort( files, Comparator.comparing( File::getName ) );

		List< String > failed = Collections.synchronizedList( new ArrayList<>() );
		AtomicInteger done = new AtomicInteger();
		AtomicInteger passed = new AtomicInteger();

		int jobs = Math.min( files.length, parallelism() );
		long started = System.nanoTime();

		ExecutorService pool = Executors.newFixedThreadPool( jobs, r -> {
			Thread t = new Thread( r );
			t.setDaemon( true );
			return t;
		});

		//A progress line on one rewritten row, not one line per file: a 17-file corpus finished in
		//seconds once parallelised and the per-file lines were what made it look slow.
		Thread progress = progressPrinter( done, files.length, started );

		for (File file : files){
			pool.submit( () -> {
				//Boxed rather than a local, because the catch below has to assign before the finally
				//runs and a plain int is not definitely assigned on the exception path.
				AtomicInteger outcome = new AtomicInteger( 1 );
				try {
					outcome.set( runChild( file ));
				} catch (IOException | InterruptedException e){
					//Reported as a failure of this file rather than aborting the corpus: one unrunnable
					//recording is a fact about the run, and the other sixteen are still worth knowing about.
					System.out.println( "        " + file.getName() + " could not be run ("
							+ e.getClass().getSimpleName() + ")" );
				} finally {
					if (outcome.get() == 0) passed.incrementAndGet();
					else failed.add( file.getName() );
					int n = done.incrementAndGet();
					if (n == files.length) progress.interrupt();
				}
			});
		}

		pool.shutdown();
		while (!pool.awaitTermination( 1, TimeUnit.SECONDS )){
			if (pool.isTerminated()) break;
		}

		if (done.get() == files.length) progress.interrupt();
		try { progress.join( 500 ); } catch (InterruptedException ignored){ Thread.currentThread().interrupt(); }

		double seconds = (System.nanoTime() - started) / 1e9;

		if (failed.isEmpty()){
			System.out.println( "[OK]     rendered playback: " + files.length
					+ " recordings played clean in the real viewer (" + jobs
					+ " at a time, " + String.format( Locale.ROOT, "%.1fs", seconds ) + ")" );
			return;
		}

		System.out.println( "[ERROR]  rendered playback: " + failed.size()
				+ " of " + files.length + " recordings diverged (" + jobs
				+ " at a time, " + String.format( Locale.ROOT, "%.1fs", seconds ) + ")" );

		if (jobs > 1){
			//Said out loud, because it is the difference between a result worth acting on and one that
			//cannot be compared with the next run.
			System.out.println( "        NOTE: run at " + jobs + " children at once, so the failing steps are"
					+ " not reproducible. Re-run with VIEWCHECK_JOBS=1 before acting on them." );
		}

		for (String name : sortedFailures( failed )) System.out.println( "        " + name );
		System.exit( 1 );
	}

	/** Failed recordings, each with its reported step, so two runs can be compared line for line. */
	private static List< String > sortedFailures( List< String > failed ){
		List< String > sorted = new ArrayList<>( failed );
		Collections.sort( sorted );
		return sorted;
	}

	/**
	 * How many children to run at once.
	 *
	 * <p>One, by default, and the parallelism is opt-in rather than the other way round. What the default
	 * one buys is a reproducible <i>failing step</i>: two sequential runs of this corpus report the same
	 * fourteen recordings failing at the same fourteen steps, while two runs at ten children at once report
	 * the same recordings but shuffle the steps between them. Playback is timing sensitive - that is the
	 * whole subject of the issue this gate tracks - so anything that changes how fast a child renders
	 * changes what the child finds.
	 *
	 * <p>What it does <i>not</i> buy is a trustworthy pass/fail. Whether a recording plays clean is a
	 * per-file answer and holds at any job count, which is why {@link #supervise} prints its "not
	 * reproducible" warning only on failure. Parallel runs are valid results; they are just not
	 * step-addressable.
	 *
	 * <p><b>Parallel is much faster, and this comment used to say the opposite.</b> Measured on this
	 * machine over the 17 committed recordings: <b>37.0s at 8 children against 219.7s sequential</b>,
	 * roughly six times faster, with 17 of 17 clean at both. The text here previously read "the run is 90s
	 * rather than 6 minutes", which described sequential as the fast mode and made the knob look worthless.
	 * It also contradicted the progress-printer comment 70 lines up, which correctly said the corpus
	 * "finished in seconds once parallelised". The default stays at one because a step number is worth more
	 * than five minutes - not because concurrency is expensive or unreliable here.
	 */
	private static int parallelism(){
		String override = System.getProperty( "viewcheckJobs", System.getenv( "VIEWCHECK_JOBS" ) );
		if (override == null || override.trim().isEmpty()) return 1;

		try {
			return Math.max( 1, Integer.parseInt( override.trim() ));
		} catch (NumberFormatException e){
			System.err.println( "[viewcheck] VIEWCHECK_JOBS is not a number (" + override + "); using 1" );
			return 1;
		}
	}

	/**
	 * One rewritten line showing how far the corpus has got.
	 *
	 * <p>Only when stderr is a terminal. Redirected into a build log a carriage-return progress bar is
	 * unreadable, and CI output that is a wall of repeated frames helps nobody.
	 */
	private static Thread progressPrinter( AtomicInteger done, int total, long started ){
		boolean interactive = System.console() != null;

		Thread t = new Thread( () -> {
			while (!Thread.currentThread().isInterrupted()){
				int n = done.get();
				double seconds = (System.nanoTime() - started) / 1e9;
				int pct = total == 0 ? 100 : (int)Math.round( 100.0 * n / total );
				int filled = pct / 5;

				StringBuilder bar = new StringBuilder();
				for (int i = 0; i < 20; i++) bar.append( i < filled ? "#" : "." );

				String line = "[viewcheck] [" + bar + "] " + pct + "%  "
						+ n + "/" + total + "  " + String.format( Locale.ROOT, "%.0fs", seconds );

				if (interactive) System.err.print( "\r" + line );
				else if (n > 0) System.err.println( line );

				if (n >= total) break;

				try { Thread.sleep( 200 ); } catch (InterruptedException e){ return; }
			}
			if (interactive) System.err.println();
		}, "viewcheck-progress" );

		t.setDaemon( true );
		t.start();
		return t;
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
				//Passed through so one recording can be investigated without touching this file. Both are
				//diagnostics and both default to off; the gate's own verdict is unaffected by them.
				optional( "-Dspd.fixedDelta", System.getProperty( "spd.fixedDelta" )),
				optional( "-Dspd.worldTrace", System.getProperty( "spd.worldTrace" )),
				"--enable-native-access=ALL-UNNAMED",
				ViewCheck.class.getName(),
				"--one", file.getAbsolutePath()
		));

		//A private file root per child. The game writes preferences and a bones.dat under the platform
		//default before playback starts, so children sharing one root collide - GdxRuntimeException
		//"Error copying source file ... .spdtmp" - and a gate run also rewrites whatever the developer's
		//own game state is. A scratch directory that is recreated per run keeps both out of the way.
		File root = new File( System.getProperty( "java.io.tmpdir" ),
				"spd-viewcheck" + System.nanoTime() );

		//Created here, not relied upon being created for us. libGDX reads its preferences from this
		//directory and cannot create it, so a missing root made SPDSettings.windowResolution() return
		//nothing usable and the window came up in the wrong mode - full screen, over the desktop. That is
		//worse than the collision the isolation was added to prevent.
		if (!root.mkdirs() && !root.isDirectory()){
			System.err.println( "[ERROR] could not create a scratch file root at " + root
					+ "; refusing to run, because children would share the default one" );
			return 1;
		}

		//Inserted before the main class rather than appended: a -D after the class name is an argument to
		//the application, not a system property, and was silently doing nothing.
		command.add( command.indexOf( ViewCheck.class.getName() ),
				"-Dspd.fileRoot=" + root.getAbsolutePath() );

		command.removeIf( java.util.Objects::isNull );

		//Output captured, not inherited. Seventeen children writing to one console interleave their
		//[replay] lines into something unreadable, and with the run parallelised that is no longer a
		//rare nuisance but the normal case. The parent's own line per recording is the report; the
		//child's detail is kept and reprinted only for a failure, where it is the evidence.
		ProcessBuilder builder = new ProcessBuilder( command ).redirectErrorStream( true );
		Process child = builder.start();

		StringBuilder captured = new StringBuilder();
		Thread reader = new Thread( () -> {
			try (java.io.BufferedReader in = new java.io.BufferedReader(
					new java.io.InputStreamReader( child.getInputStream() ))){
				String line;
				while ((line = in.readLine()) != null){
					synchronized (captured){ captured.append( line ).append( '\n' ); }
				}
			} catch (IOException ignored){
				//the child died; the exit code says so
			}
		}, "viewcheck-child-output" );
		reader.setDaemon( true );
		reader.start();

		if (!child.waitFor( CHILD_TIMEOUT_MS, TimeUnit.MILLISECONDS )){
			child.destroyForcibly();
			System.err.println( "[ERROR]  " + file.getName() + " did not finish within "
					+ ( CHILD_TIMEOUT_MS / 1000 ) + "s; the viewer never stopped" );
			return 1;
		}
		reader.join( 1000 );

		deleteRecursively( root );

		int code = child.exitValue();
		if (code != 0){
			System.out.println( "        " + file.getName() + " said:" );
			synchronized (captured){
				for (String line : captured.toString().split( "\n" )){
					if (line.contains( "[replay] halted" ) || line.contains( "Exception" )
							|| line.contains( "Error" )){
						System.out.println( "          " + line );
					}
				}
			}
		}
		return code;
	}

	// --- child -------------------------------------------------------------

	/**
	 * States the properties a forked gate child is launched with, for the case where this JVM is the one
	 * running the viewer.
	 *
	 * <p>{@code --one} used to inherit nothing, and the difference was audible: the gate forks every child
	 * with {@code -Dspd.mute=1} - no audio device is guaranteed on a build machine, and an unhandled one
	 * aborts the context rather than degrading - while an investigation run played the recording out loud
	 * on the developer's speakers. It also left the window visible and open, which is not what anybody
	 * asking to re-run one recording through the gate means.
	 *
	 * <p>Set only when unset, so an explicit {@code -Dspd.hidden=0} or {@code -Dspd.mute=0} on the
	 * command line still wins. {@code ReplayLauncher.MUTE} is a {@code static final} read at class
	 * initialisation, which is why this has to run before that class is touched rather than at the first
	 * call that needs it.
	 */
	private static void applyChildDefaults(){
		defaultProperty( "spd.mute", "1" );
		defaultProperty( "spd.windowed", "1" );
		defaultProperty( "spd.hidden", "1" );
		defaultProperty( "spd.autoClose", "1" );
		defaultProperty( "spd.fast", String.valueOf( GATE_SPEED ));
	}

	private static void defaultProperty( String key, String value ){
		if (System.getProperty( key ) == null) System.setProperty( key, value );
	}

	/**
	 * Plays one recording in the real viewer and exits with the verdict.
	 *
	 * <p>Almost all of this is {@link ReplayLauncher}'s bootstrap, called rather than copied: the seed
	 * has to be applied through settings before the context exists, and floor 1 has to be built by the
	 * interlevel scene because the game's own transition is hardcoded. Duplicating that here would be a
	 * second copy to drift.
	 */
	private static void playOne( File file ) throws IOException {
		applyChildDefaults();

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
			//The window mode is checked, not assumed. The game goes fullscreen from its own create() by
			//reading a preference that defaults to true, which happens after the launcher's window
			//configuration is already complete - so a gate that says it passed can still have spent its
			//time fullscreen on the developer's desktop. This reads the mode back from the live context.
			//Waited on, not slept through: polling at 50ms against a frame loop can observe the player
			//as finished before a single frame has run, which at gate speed is fast enough to happen and
			//would report a pass for a run that never played.
			while (player.playing() && player.playback().cursor() == 0){
				try {
					Thread.sleep( 50 );
				} catch (InterruptedException e){
					return;
				}
			}

			while (player.playing()){
				try {
					Thread.sleep( 10 );
				} catch (InterruptedException e){
					return;
				}
			}

			boolean fullscreen = false;
			try {
				fullscreen = com.badlogic.gdx.Gdx.graphics != null
						&& com.badlogic.gdx.Gdx.graphics.isFullscreen();
			} catch (RuntimeException ignored){
				//No graphics context yet, or already torn down. Reported as not-fullscreen rather than
				//unknown: the failure this guards against is the window being up, and a context that
				//cannot be queried has not put a window on the desktop either.
			}

			if (fullscreen){
				System.out.println( "[viewcheck] FAIL  " + file.getName()
						+ "  the window went fullscreen during an unattended run" );
				System.out.flush();
				Runtime.getRuntime().halt( 1 );
				return;
			}

			ReplayPlayback playback = player.playback();
			boolean clean = !playback.diverged();

			//The frame accounting is printed on every line, pass or fail. It is the one number that says
			//how much of the render loop was spent not applying a recorded step, which is the whole
			//subject here: a recording needing 40 frames per step is one whose playback timing is decided
			//by animation duration rather than by the recording, and it is those recordings whose outcome
			//depends on how fast the machine renders. A failure that does not print it sends the next
			//reader back to the viewer with nothing to compare against.
			if (clean){
				System.out.println( "[viewcheck] ok    " + file.getName()
						+ "  (" + playback.cursor() + "/" + playback.total() + " steps;  "
						+ player.frameReport() + ")" );
			} else {
				System.out.println( "[viewcheck] FAIL  " + file.getName()
						+ "  " + player.haltReason()
						+ "   [" + player.frameReport() + "]" );
			}
			System.out.flush();
			System.err.flush();

			Runtime.getRuntime().halt( clean ? 0 : 1 );
		}, "viewcheck" );

		watcher.setDaemon( true );
		watcher.start();
	}
}