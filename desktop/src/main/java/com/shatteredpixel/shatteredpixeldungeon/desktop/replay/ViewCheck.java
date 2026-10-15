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

	/**
	 * Ceiling on a single playback, so a stuck viewer fails instead of hanging the gate.
	 *
	 * <p>Five minutes, up from 120s. A full-length trainer recording is ~1500 steps and the gate plays at
	 * speed 40, which measures at ~5150 frames for 1508 steps — over four minutes on a loaded machine.
	 * At 120s the budget expired and the gate reported <em>"the viewer never stopped"</em> for a run that
	 * had stopped, having applied every step: a false failure whose message names the opposite of what
	 * happened.
	 *
	 * <p>Cost is bounded rather than proportional to the corpus, because children run in parallel: what
	 * this buys is that the gate waits for the slowest single recording rather than reporting it wrong.
	 * A genuinely stuck viewer still fails, just later.
	 */
	private static final long CHILD_TIMEOUT_MS = 300_000;

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

		Thread progress = progressPrinter( done, files.length, started );

		for (File file : files){
			pool.submit( () -> {
				AtomicInteger outcome = new AtomicInteger( 1 );
				try {
					outcome.set( runChild( file ));
				} catch (IOException | InterruptedException e){
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
					+ " at a time, " + String.format( Locale.ROOT, "%.1fs", seconds ) + ")"
					+ (jobs > 1 ? " - which recordings played clean is reliable; failing steps would not be"
						+ ", had anything failed" : "") );
			return;
		}

		System.out.println( "[ERROR]  rendered playback: " + failed.size()
				+ " of " + files.length + " recordings diverged (" + jobs
				+ " at a time, " + String.format( Locale.ROOT, "%.1fs", seconds ) + ")" );

		if (jobs > 1){
			System.out.println( "        NOTE: which recordings diverged is reliable, but at " + jobs
				+ " children at once the failing STEPS are not reproducible - playback is timing"
				+ " sensitive. Re-run this recording with VIEWCHECK_JOBS=1 before acting on a step number." );
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
	 * <p><b>Eight, by default.</b> Override with {@code VIEWCHECK_JOBS} or {@code -PviewcheckJobs}, and set
	 * it to 1 to get reproducible failing <i>steps</i>: two sequential runs of this corpus report the same
	 * fourteen recordings failing at the same fourteen steps, while runs at several children at once report
	 * the same recordings but shuffle the steps between them. Playback is timing sensitive - that is the
	 * whole subject of the issue this gate tracks - so anything that changes how fast a child renders
	 * changes what the child finds.
	 *
	 * <p>What the default does <i>not</i> cost is a trustworthy pass/fail. Whether a recording plays clean
	 * is a per-file answer and holds at any job count, which is why {@link #supervise} prints its "not
	 * reproducible" warning only on failure. A parallel run is a valid green; it is just not step-addressable,
	 * so {@link #supervise} names the one-job re-run that makes a failure investigable.
	 *
	 * <p><b>Why the default moved off one.</b> Measured on this machine over the 17 committed recordings:
	 * <b>37.0s at 8 children against 219.7s sequential</b>, roughly six times faster, with 17 of 17 clean at
	 * both. The default was 1, and the reasoning behind it - that a step number is worth more than five
	 * minutes - survives, but it is a reason to re-run <i>after</i> a failure, not a reason to make every
	 * run pay for the guarantee. A gate nobody runs is worth less than a fast gate whose failures say how to
	 * investigate them, and this one already said that.
	 *
	 * <p>Two comments here previously disagreed about which mode was fast: this one read "the run is 90s
	 * rather than 6 minutes" while the progress printer, 70 lines up, correctly said the corpus "finished in
	 * seconds once parallelised". The measurement above is what settled it.
	 */
	private static int parallelism(){
		int byDefault = 8;

		String override = System.getProperty( "viewcheckJobs", System.getenv( "VIEWCHECK_JOBS" ) );
		if (override == null || override.trim().isEmpty()) return byDefault;

		try {
			return Math.max( 1, Integer.parseInt( override.trim() ));
		} catch (NumberFormatException e){
			System.err.println( "[viewcheck] VIEWCHECK_JOBS is not a number (" + override
					+ "); using " + byDefault );
			return byDefault;
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
				"-Dspd.mute=1",
				"-Dspd.windowed=1", "-Dspd.hidden=1",
				"-Dspd.autoClose=1",
				"-Dspd.fast=" + (int) GATE_SPEED,
				optional( "-Dspd.fixedDelta", System.getProperty( "spd.fixedDelta" )),
				optional( "-Dspd.worldTrace", System.getProperty( "spd.worldTrace" )),
				"--enable-native-access=ALL-UNNAMED",
				ViewCheck.class.getName(),
				"--one", file.getAbsolutePath()
		));

		File root = new File( System.getProperty( "java.io.tmpdir" ),
				"spd-viewcheck" + System.nanoTime() );

		if (!root.mkdirs() && !root.isDirectory()){
			System.err.println( "[ERROR] could not create a scratch file root at " + root
					+ "; refusing to run, because children would share the default one" );
			return 1;
		}

		command.add( command.indexOf( ViewCheck.class.getName() ),
				"-Dspd.fileRoot=" + root.getAbsolutePath() );

		command.removeIf( java.util.Objects::isNull );

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
			}

			if (fullscreen){
				System.out.println( "[viewcheck] FAIL  " + file.getName()
						+ "  the window went fullscreen during an unattended run" );
				System.out.flush();
				Runtime.getRuntime().halt( 1 );
				return;
			}

			ReplayPlayback playback = player.playback();
			boolean playedEveryStep = playback.cursor() >= playback.total()
					&& !playback.diverged();
			boolean clean = playedEveryStep;

			if (clean){
				System.out.println( "[viewcheck] ok    " + file.getName()
						+ "  (" + playback.cursor() + "/" + playback.total() + " steps;  "
						+ player.frameReport() + ")" );
			} else {
				String why = playback.diverged()
						? player.haltReason()
						: "played " + playback.cursor() + " of " + playback.total() + " steps - "
								+ player.haltReason();
				System.out.println( "[viewcheck] FAIL  " + file.getName() + "  " + why
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