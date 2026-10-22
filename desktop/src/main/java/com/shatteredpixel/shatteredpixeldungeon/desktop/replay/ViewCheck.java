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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
 *   gradle :desktop:viewcheck -PspdReplayDir=some\other\corpus
 * </pre>
 *
 * <p>Each recording plays in its own JVM, because a diverged run leaves a live GL context and a
 * half-played scene behind, and because a viewer that cannot close its window would otherwise hang the
 * gate forever. That is a fork per file, so the parent does nothing but supervise.
 *
 * <p>The corpus is played as hard as the machine allows, and whatever diverged is then played again on
 * its own. Playback is timing sensitive, so a loaded machine produces failures that have nothing to do
 * with the recording; the serial re-run is what separates the two, and it is also the run whose failing
 * step can be acted on. See {@link #supervise(File)}.
 */
public class ViewCheck {

	/** Playback speed for the gate. Pacing only; the path taken is the same at 1x. */
	private static final float GATE_SPEED = 40f;

	private static final int ALTERED_STEP = 10;

	private static final File COMMITTED_CORPUS = new File( "superintelligence/test-replays" );

	private static final long CHILD_TIMEOUT_MS = 300_000;

	// a child is stuck when its heartbeat stops naming a new step, not when it is slow
	private static final long STALL_MS = 1000L * Long.getLong( "viewcheckStallSeconds", 120L );

	public static void main( String[] args ) throws Exception {
		List< String > parsed = new ArrayList<>( Arrays.asList( args ));

		int one = parsed.indexOf( "--one" );
		if (one >= 0 && one + 1 < parsed.size()){
			playOne( new File( parsed.get( one + 1 )) );
			return;
		}

		int probe = parsed.indexOf( "--probe-config" );
		if (probe >= 0){
			ReplayLauncher.windowConfig();
			System.out.flush();
			System.err.flush();
			Runtime.getRuntime().halt( 0 );
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
	 * <p>Two phases. The first plays the whole corpus in parallel, as hard as the machine allows; the
	 * second re-plays whatever diverged, one recording at a time, with nothing else running.
	 *
	 * <p>Playback is timing sensitive, and a loaded machine changes the timing. The gate's own note
	 * has always said so: at several children at once the failing <i>steps</i> are not reproducible,
	 * and which recordings fail is only reliable. A parallel phase on its own therefore fails for
	 * reasons that have nothing to do with the recording, and the fix is to let the serial phase
	 * decide rather than to run the whole corpus serially and wait for it.
	 *
	 * <p>So a recording that diverged under load and plays clean on its own is reported as what it
	 * is - a load artefact - and does not fail the gate. A recording that still diverges on its own
	 * is a real fault and fails it, and the step it fails at is reproducible because the serial run
	 * produced it.
	 */
	private static File[] recordings( File dir ){
		return dir.listFiles( ( d, name ) -> name.endsWith( ".replay" ) || name.endsWith( ".dat" ) );
	}

	private static void supervise( File corpus ) throws IOException, InterruptedException {
		File[] files = recordings( corpus );
		if (files == null || files.length == 0){
			System.err.println( "[ERROR] no recordings in " + corpus.getAbsolutePath() );
			System.exit( 1 );
			return;
		}
		Arrays.sort( files, Comparator.comparing( File::getName ) );

		List< String > failed = Collections.synchronizedList( new ArrayList<>() );
		AtomicInteger done = new AtomicInteger();

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
					outcome.set( runChild( file, new LinkedHashMap<>(), new StringBuilder(), true ));
				} catch (IOException | InterruptedException e){
					System.out.println( "        " + file.getName() + " could not be run ("
							+ e.getClass().getSimpleName() + ")" );
				} finally {
					if (outcome.get() != 0) failed.add( file.getName() );
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

		List< String > confirmed = new ArrayList<>();
		List< String > recovered = new ArrayList<>();

		if (!failed.isEmpty()){
			for (String name : sortedFailures( failed )){
				File file = new File( corpus, name );
				int code;
				try {
					code = runChild( file, new LinkedHashMap<>(), new StringBuilder(), false );
				} catch (IOException | InterruptedException e){
					code = 1;
				}
				if (code == 0) recovered.add( name );
				else confirmed.add( name );
			}
		}

		double total = (System.nanoTime() - started) / 1e9;

		int diagnosticsFailed = diagnostics( corpus );

		if (confirmed.isEmpty()){
			System.out.println( "[OK]     rendered playback: " + files.length
					+ " recordings played clean in the real viewer (" + jobs
					+ " at a time, " + String.format( Locale.ROOT, "%.1fs", seconds ) + ")"
					+ (recovered.isEmpty() ? ""
						: "; " + recovered.size() + " of them diverged under load and played clean on a"
							+ " serial re-run") );
			System.exit( diagnosticsFailed );
			return;
		}

		System.out.println( "[ERROR]  rendered playback: " + confirmed.size()
				+ " of " + files.length + " recordings diverged (" + jobs
				+ " at a time, " + String.format( Locale.ROOT, "%.1fs", total ) + ")" );

		if (jobs > 1){
			System.out.println( "        NOTE: each of these diverged again when re-run on its own, so"
					+ " the failing step below is reproducible. A recording that only diverged under"
					+ " load is not a failure and is not listed." );
		}

		for (String name : confirmed) System.out.println( "        " + name );
		System.exit( 1 );
	}

	/** Failed recordings, each with its reported step, so two runs can be compared line for line. */
	/**
 * The two viewer diagnostics that were verified by running them and gated by nothing.
 *
 * <p>{@code -Dspd.replayContinueOnDiverge} and {@code spd.hidden} were both in {@code issues.md} 29
 * with a measured answer and no gate, so both could stop working without anything going red. Neither
 * can be checked by the corpus sweep: the first only differs on a recording that diverges, and the
 * second is read by the launcher's window configuration before a window exists.
 *
 * <p>The second is checked without ever showing a window. {@code ReplayLauncher.HIDDEN} gates
 * {@code setInitialVisible}, and {@code --probe-config} calls {@code windowConfig()} and halts - the
 * property is read and printed, and no {@code Lwjgl3Application} is constructed. A gate that took
 * over the desktop to prove a flag was wired is a gate nobody runs.
 *
 * @return 0 when both hold, 1 otherwise.
 */
private static int diagnostics( File corpus ) throws IOException, InterruptedException {
		List< String > problems = new ArrayList<>();

		checkHiddenFlagReachesTheWindowConfig( problems );
		checkContinueOnDivergeKeepsPlaying( corpus, problems );

		if (problems.isEmpty()){
			System.out.println( "[OK]     viewer diagnostics: spd.replayContinueOnDiverge and"
					+ " spd.hidden both reach the viewer" );
			return 0;
		}

		System.out.println( "[ERROR]  viewer diagnostics: " + problems.size() + " of 2 checks failed" );
		for (String p : problems) System.out.println( "        " + p );
		return 1;
	}


	private static void checkHiddenFlagReachesTheWindowConfig( List< String > problems )
			throws IOException, InterruptedException {
		Map< String, String > without = new LinkedHashMap<>();
		without.put( "spd.hidden", null );

		String off = probeConfig( without );
		String on = probeConfig( new LinkedHashMap<>() );

		if (!off.contains( "hidden=false" )){
			problems.add( "a child launched without spd.hidden reported " + firstReplayLine( off )
					+ ", so the flag's absence does not read as a visible window. -PspdHidden on the"
					+ " gradle task set the property on the supervisor, whose children are the ones that"
					+ " open the window, which is why it looked set and was not." );
			return;
		}
		if (!on.contains( "hidden=true" )){
			problems.add( "a child launched with spd.hidden reported " + firstReplayLine( on )
					+ ", so the property is not being read by the window configuration at all" );
			return;
		}

		System.out.println( "  spd.hidden reaches the window configuration: hidden=false without it,"
				+ " hidden=true with it, and neither run opens a window" );
	}


	private static String probeConfig( Map< String, String > overrides )
			throws IOException, InterruptedException {
		StringBuilder sink = new StringBuilder();
		runProbe( overrides, sink );
		return sink.toString();
	}


	private static void checkContinueOnDivergeKeepsPlaying( File corpus, List< String > problems )
			throws IOException, InterruptedException {
		File source = null;
		for (File dir : new File[]{ corpus, COMMITTED_CORPUS }){
			File[] files = recordings( dir );
			if (files == null || source != null) continue;
			for (File file : files){
				if (file.getName().contains( "death-last" )) source = file;
			}
		}

		if (source == null){
			problems.add( "no recording named *death-last* is in " + corpus.getAbsolutePath()
					+ ", and that is the recording the continue-on-divergence flag is measured on. Without"
					+ " a fixture the flag has nothing to be checked against." );
			return;
		}

		File diverging = divergedCopy( source );
		if (diverging == null) return;

		Map< String, String > keepGoing = new LinkedHashMap<>();
		keepGoing.put( "spd.replayContinueOnDiverge", "1" );

		StringBuilder offSink = new StringBuilder();
		runChild( diverging, new LinkedHashMap<>(), offSink );
		StringBuilder onSink = new StringBuilder();
		runChild( diverging, keepGoing, onSink );

		Integer off = appliedSteps( offSink.toString() );
		Integer on = appliedSteps( onSink.toString() );

		diverging.delete();

		if (off == null || on == null){
			problems.add( "neither run of the altered recording reported how many steps it"
					+ " applied (off=" + off + ", on=" + on + "), so the two cannot be compared. The"
					+ " FAIL line is the only place that number is printed." );
			return;
		}

		if (on <= off){
			problems.add( "spd.replayContinueOnDiverge applied " + on + " steps against " + off
					+ " without it, so setting the flag changed nothing. Its whole purpose is that the"
					+ " steps after a divergence are the only ones worth looking at." );
			return;
		}

		System.out.println( "  spd.replayContinueOnDiverge keeps playing past the divergence: "
				+ on + " steps applied against " + off + " without it, on a recording altered at step "
				+ ALTERED_STEP );
	}


	private static File divergedCopy( File source ){
		Replay replay;
		try {
			replay = ReplayIO.read( source );
		} catch (IOException e){
			System.err.println( "[WARN] cannot read " + source + ": " + e.getMessage() );
			return null;
		}

		if (replay.steps.size() <= ALTERED_STEP){
			System.err.println( "[WARN] " + source.getName() + " has only " + replay.steps.size()
					+ " steps, so step " + ALTERED_STEP + " cannot be altered" );
			return null;
		}

		replay.steps.get( ALTERED_STEP ).heroPos += 1;

		File out = new File( System.getProperty( "java.io.tmpdir" ),
				"spd-viewcheck-diverged-" + System.nanoTime() + ".replay" );
		try {
			ReplayIO.write( replay, out );
		} catch (IOException e){
			System.err.println( "[ERROR] cannot write the altered recording: " + e.getMessage() );
			return null;
		}
		return out;
	}


	/** The applied/total step count out of a child's last {@code [viewcheck]} line, or null. */
	private static Integer appliedSteps( String output ){
		java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile( "\\[(\\d+)/(\\d+) steps" ).matcher( output );
		Integer found = null;
		while (matcher.find()){
			found = Integer.parseInt( matcher.group( 1 ));
		}
		return found;
	}


	private static String firstReplayLine( String output ){
		for (String line : output.split( "\n" )){
			if (line.contains( "[replay]" )) return line.trim();
		}
		return "nothing at all";
	}


	private static void runProbe( Map< String, String > overrides, StringBuilder sink )
			throws IOException, InterruptedException {
		runChild( overrides, sink, "--probe-config" );
	}


	private static void runChild( Map< String, String > overrides, StringBuilder sink,
			String... mode ) throws IOException, InterruptedException {
		String javaBin = System.getProperty( "java.home" )
				+ File.separator + "bin" + File.separator + "java";

		Map< String, String > flags = new LinkedHashMap<>();
		flags.put( "spd.mute", "1" );
		flags.put( "spd.windowed", "1" );
		flags.put( "spd.hidden", "1" );
		flags.put( "spd.autoClose", "1" );
		flags.put( "spd.fast", String.valueOf( (int) GATE_SPEED ) );
		flags.putAll( overrides );

		List< String > command = new ArrayList<>();
		command.add( javaBin );
		command.add( "-cp" );
		command.add( System.getProperty( "java.class.path" ) );
		for (Map.Entry< String, String > flag : flags.entrySet()){
			if (flag.getValue() == null) continue;
			command.add( "-D" + flag.getKey() + "=" + flag.getValue() );
		}
		command.add( "--enable-native-access=ALL-UNNAMED" );
		command.add( ViewCheck.class.getName() );
		command.addAll( Arrays.asList( mode ));

		Process child = new ProcessBuilder( command ).redirectErrorStream( true ).start();

		try (java.io.BufferedReader in = new java.io.BufferedReader(
				new java.io.InputStreamReader( child.getInputStream() ))){
			String line;
			while ((line = in.readLine()) != null) sink.append( line ).append( '\n' );
		}

		if (!child.waitFor( CHILD_TIMEOUT_MS, TimeUnit.MILLISECONDS )) child.destroyForcibly();
	}


	private static List< String > sortedFailures( List< String > failed ){
		List< String > sorted = new ArrayList<>( failed );
		Collections.sort( sorted );
		return sorted;
	}

	/**
	 * How many children to run at once.
	 *
	 * <p><b>As many as the machine has logical processors, capped at the corpus.</b> Override with
	 * {@code VIEWCHECK_JOBS} or {@code -PviewcheckJobs}, and set it to 1 to run the whole corpus
	 * serially.
	 *
	 * <p>Playback is timing sensitive, and a loaded machine changes the timing - which is the whole
	 * subject of the issue this gate tracks. Running the corpus flat out therefore produces failures
	 * that have nothing to do with the recording, and the gate answers that with a second phase
	 * rather than with a smaller number of children: whatever diverged in the parallel phase is
	 * re-played on its own, and only a recording that diverges again is a failure.
	 *
	 * <p>What the parallelism buys is the wall clock, and what it costs is that a parallel run is not
	 * step-addressable. The second phase gives that back for the recordings that matter, because the
	 * serial run is the one whose failing step can be acted on.
	 *
	 * <p>Measured on this machine over the 20 committed recordings: **46.4s at 20 children**, against
	 * 93.3s at 8 and 362.4s at one. The wall clock is the longest single recording rather than the sum
	 * of all of them, which is what makes the parallelism worth having.
	 */
	private static int parallelism(){
		int byDefault = Math.max( 1, Runtime.getRuntime().availableProcessors() );

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
		boolean interactive = stderrIsTerminal();

		Thread t = new Thread( () -> {
			int printed = 0;
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
				else if (n > printed) System.err.println( line );
				printed = n;

				if (n >= total) break;

				try { Thread.sleep( 200 ); } catch (InterruptedException e){ return; }
			}
			if (interactive) System.err.println();
		}, "viewcheck-progress" );

		t.setDaemon( true );
		t.start();
		return t;
	}

	private static boolean stderrIsTerminal(){
		java.io.Console console = System.console();
		if (console == null) return false;
		// JDK 22+ returns a Console even when redirected; isTerminal() is how it says so
		try {
			return (Boolean) java.io.Console.class.getMethod( "isTerminal" ).invoke( console );
		} catch (ReflectiveOperationException e){
			return true;
		}
	}

	/** Forks one playback and waits for it, killing it if it overruns. */
	private static int runChild( File file, Map< String, String > overrides )
			throws IOException, InterruptedException {
		StringBuilder sink = new StringBuilder();
		return runChild( file, overrides, sink );
	}

	private static int runChild( File file, Map< String, String > overrides, StringBuilder sink )
			throws IOException, InterruptedException {
		return runChild( file, overrides, sink, false );
	}

	/**
	 * Forks one playback and waits for it, killing it if it overruns.
	 *
	 * @param quiet suppress the child's own failure report. The parallel phase runs with it set,
	 * because its failures are re-run serially and the serial run is the one whose step number is
	 * reproducible - so printing the parallel failures would print numbers that cannot be acted on.
	 */
	private static final java.util.regex.Pattern HEARTBEAT_STEP = java.util.regex.Pattern.compile( "heartbeat frame \\d+ step (\\d+) " );

	private static int heartbeatStep( String line ){
		java.util.regex.Matcher m = HEARTBEAT_STEP.matcher( line );
		return m.find() ? Integer.parseInt( m.group( 1 ) ) : -1;
	}

	private static int runChild( File file, Map< String, String > overrides, StringBuilder sink,
			boolean quiet ) throws IOException, InterruptedException {
		String javaBin = System.getProperty( "java.home" )
				+ File.separator + "bin" + File.separator + "java";

		Map< String, String > flags = new LinkedHashMap<>();
		flags.put( "spd.mute", "1" );
		flags.put( "spd.windowed", "1" );
		flags.put( "spd.hidden", "1" );
		flags.put( "spd.autoClose", "1" );
		flags.put( "spd.heartbeat", "1" );
		flags.put( "spd.fast", String.valueOf( (int) GATE_SPEED ) );
		String fixedDelta = System.getProperty( "spd.fixedDelta" );
		if (fixedDelta != null && !fixedDelta.trim().isEmpty()) flags.put( "spd.fixedDelta", fixedDelta.trim() );
		String worldTrace = System.getProperty( "spd.worldTrace" );
		if (worldTrace != null && !worldTrace.trim().isEmpty()) flags.put( "spd.worldTrace", worldTrace.trim() );
		flags.putAll( overrides );

		List< String > command = new ArrayList<>();
		command.add( javaBin );
		command.add( "-cp" );
		command.add( System.getProperty( "java.class.path" ) );
		for (Map.Entry< String, String > flag : flags.entrySet()){
			if (flag.getValue() == null) continue;
			command.add( "-D" + flag.getKey() + "=" + flag.getValue() );
		}
		command.add( "--enable-native-access=ALL-UNNAMED" );
		command.add( ViewCheck.class.getName() );
		command.add( "--one" );
		command.add( file.getAbsolutePath() );

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
		java.util.concurrent.atomic.AtomicLong progressed = new java.util.concurrent.atomic.AtomicLong( System.nanoTime() );
		java.util.concurrent.atomic.AtomicInteger lastStep = new java.util.concurrent.atomic.AtomicInteger( -1 );
		Thread reader = new Thread( () -> {
			try (java.io.BufferedReader in = new java.io.BufferedReader(
					new java.io.InputStreamReader( child.getInputStream() ))){
				String line;
				while ((line = in.readLine()) != null){
					int step = heartbeatStep( line );
					if (step < 0){
						synchronized (captured){ captured.append( line ).append( '\n' ); }
					} else if (step != lastStep.getAndSet( step )){
						progressed.set( System.nanoTime() );
					}
				}
			} catch (IOException ignored){
			}
		}, "viewcheck-child-output" );
		reader.setDaemon( true );
		reader.start();

		while (!child.waitFor( 1, TimeUnit.SECONDS )){
			if (System.nanoTime() - progressed.get() > STALL_MS * 1_000_000L){
				child.destroyForcibly();
				System.err.println( "[ERROR]  " + file.getName() + " applied no step for " + ( STALL_MS / 1000 )
						+ "s after step " + lastStep.get() + "; the viewer stalled" );
				return 1;
			}
		}
		reader.join( 1000 );

		deleteRecursively( root );

		int code = child.exitValue();
		if (code != 0 && !quiet){
			System.out.println( "        " + file.getName() + " said:" );
		}
		synchronized (captured){
			sink.append( captured );
			if (code == 0) return code;
			if (quiet) return code;
			for (String line : captured.toString().split( "\n" )){
				if (line.contains( "[replay] halted" ) || line.contains( "Exception" )
						|| line.contains( "Error" )){
					System.out.println( "          " + line );
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
						+ "   [" + playback.cursor() + "/" + playback.total() + " steps; "
						+ player.frameReport() + "]" );
			}
			System.out.flush();
			System.err.flush();

			Runtime.getRuntime().halt( clean ? 0 : 1 );
		}, "viewcheck" );

		watcher.setDaemon( true );
		watcher.start();
	}
}
