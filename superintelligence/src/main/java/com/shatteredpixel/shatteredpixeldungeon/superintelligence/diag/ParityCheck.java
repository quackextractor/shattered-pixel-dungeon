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

package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Records runs across many seeds in one process, then proves each recording replays exactly.
 *
 * <pre>gradle :superintelligence:paritycheck</pre>
 * <pre>gradle :superintelligence:paritycheck -PparityArgs="--seeds 10 --heroes 5"</pre>
 *
 * <p><b>Why this is a gate when {@code verifycheck} and {@code collectcheck} already exist.</b> Both of
 * those prove that verification can fail: they alter a recording and require the altered step to be
 * caught. Neither proves that a recording made <em>now</em> replays. And the gap is not academic - it is
 * where the worst bug in this project lived for weeks. {@code GameScene.pendingCellListener} is a static
 * belonging to the process, so an episode that ended mid-aim left it armed, and the next episode's first
 * settle read every one of the agent's actions as a target choice. One run per process showed nothing at
 * all, because one process one episode has nothing to leak from. It took recordings verified
 * <em>in sequence</em> to see it: 4 of 10 diverged, and each of those four verified perfectly well on its
 * own.
 *
 * <p>So the sweep is deliberately sequential and in a single JVM, and each recording is written to disk
 * and read back before it is verified. Writing and reading is not ceremony: a recorder that keeps a
 * {@code Replay} in memory and one that produces a parseable file are not the same code, and the file is
 * what every other tool consumes.
 *
 * <p><b>What it compares against.</b> The recorded state, not a second run of this code - position, HP,
 * engine time and inventory per step, which is what {@link ReplayIO.Verification} already does. Replaying
 * twice and comparing the two replays would only prove the engine is deterministic; both sides would share
 * every headless-specific fault.
 *
 * <p><b>Two rounds per seed.</b> The first pass records and verifies in order; the second re-verifies
 * every recording again at the end, after all of them have run in between. A leak that only shows up on
 * the second visit to a seed cannot be caught by recording and immediately verifying, because nothing has
 * happened in between to leak.
 */
public class ParityCheck {

	/** Seeds used by the default sweep. Fixed strings, so a failure is reproducible. */
	private static final String[] DEFAULT_SEEDS = {
			"PARITY-ALPHA", "PARITY-BRAVO", "PARITY-CHARLIE", "PARITY-DELTA",
			"PARITY-ECHO", "PARITY-FOXTROT", "PARITY-GOLF", "PARITY-HOTEL"
	};

	/** Heroes used by the default sweep: one melee, one ranged, one that fights at range. */
	private static final HeroClass[] DEFAULT_HEROES = {
			HeroClass.WARRIOR, HeroClass.MAGE, HeroClass.HUNTRESS, HeroClass.ROGUE
	};

	/** Turn budget per run. Sized so the default sweep stays inside the gates time budget. */
	private static int turnLimit = 400;

	private static final List<String> failures = new ArrayList<>();

	/** Where recordings are written, so a failing one can be inspected rather than only reported. */
	private static File outDir;

	public static void main( String[] args ) throws IOException {
		List<String> seeds = new ArrayList<>();
		List<HeroClass> heroes = new ArrayList<>();
		boolean full = false;

		for (int i = 0; i < args.length; i++){
			switch (args[ i ]) {
				case "--seeds":
					int n = Integer.parseInt( args[ ++i ] );
					for (int s = 0; s < n && s < DEFAULT_SEEDS.length; s++){
						seeds.add( DEFAULT_SEEDS[ s ] );
					}
					break;
				case "--heroes":
					int h = Integer.parseInt( args[ ++i ] );
					for (int k = 0; k < h && k < DEFAULT_HEROES.length; k++){
						heroes.add( DEFAULT_HEROES[ k ] );
					}
					break;
				case "--turns":
					turnLimit = Integer.parseInt( args[ ++i ] );
					break;
				case "--full":
					full = true;
					break;
				default:
					System.err.println( "[WARN] unknown option: " + args[ i ] );
			}
		}

		if (seeds.isEmpty()){
			for (String s : DEFAULT_SEEDS) seeds.add( s );
		}
		if (heroes.isEmpty()){
			for (HeroClass h : DEFAULT_HEROES) heroes.add( h );
		}

		//A longer sweep for a nightly job, deliberately not the default: the point of a gate is to be
		//run on every commit, and a gate nobody runs is not a gate.
		if (full){
			turnLimit = 1500;
		}

		outDir = new File( System.getProperty( "java.io.tmpdir" ), "spd-paritycheck" );
		//noinspection ResultOfMethodCallIgnored
		outDir.mkdirs();

		HeadlessServices.install( outDir );
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = turnLimit;

		System.out.println( "parity sweep: " + seeds.size() + " seed(s) x " + heroes.size()
				+ " hero(es), " + turnLimit + " turns, one process" );

		//Round one: record and immediately verify, in order. This is the case that catches a static left
		//armed by the previous recording.
		List< Case > recorded = new ArrayList<>();
		for (String seed : seeds){
			for (HeroClass hero : heroes){
				Case c = recordAndVerify( config, seed, hero );
				recorded.add( c );
			}
		}

		//Round two: re-verify every recording, in the same order, having run all of them in between.
		//Nothing has been re-recorded, so any divergence here is state the first pass left behind that
		//the reset did not clear - which is the whole class of fault this check exists to find.
		List<String> secondPass = new ArrayList<>();
		for (Case c : recorded){
			ReplayIO.Verification again = verify( c.replay );
			if (again.diverged){
				secondPass.add( c.name() + " diverged on re-verification at step " + again.divergedAt
						+ ": " + again.divergence + " (it verified cleanly the first time, so state from the"
						+ " other runs reached it)" );
			}
		}

		report( recorded, secondPass );

		if (failures.isEmpty()){
			System.out.println( "[OK]     replay parity: " + recorded.size() + " recording(s) over "
					+ seeds.size() + " seed(s) replayed exactly, twice, in one process" );
		} else {
			System.out.println( "[ERROR]  replay parity: " + failures.size()
					+ " recording(s) did not replay exactly" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	// --------------------------------------------------------------------------- one case

	/** One seed/hero pair, recorded once and verified twice. */
	private static class Case {
		/**
		 * The recording as it came back off disk, which is the one verified.
		 *
		 * <p>Not the object {@code ScriptedEpisode} returned: verifying in-memory state would skip the
		 * entire write/read round trip, and a header that does not survive serialisation is a different
		 * fault from a replay that does not reproduce.
		 */
		Replay replay;
		final File file;
		final String seed;
		final HeroClass hero;
		int steps;
		final double recordedScore;
		double reproducedScore;
		int verifiedSteps;
		boolean diverged;
		int divergedAt = -1;
		String divergence = "";

		Case( Replay replay, File file, String seed, HeroClass hero, int steps, double recordedScore ){
			this.replay = replay;
			this.file = file;
			this.seed = seed;
			this.hero = hero;
			this.steps = steps;
			this.recordedScore = recordedScore;
		}

		String name(){
			return seed + "/" + hero;
		}
	}

	/**
	 * Records one run, writes it, reads it back, and verifies what came back.
	 *
	 * <p>The policy is seeded from the seed text, so a failure is reproducible by naming the case - a
	 * random policy would make the sweep's own result irreproducible, which for a determinism check would
	 * be a contradiction.
	 */
	private static Case recordAndVerify( EnvConfig config, String seed, HeroClass hero ){
		ScriptedEpisode.Result episode = ScriptedEpisode.record( config, seed, hero, 0, seed.hashCode() );

		Replay replay = episode.replay;
		File file = new File( outDir, seed + "-" + hero.name() + ".replay" );

		Case c = new Case( replay, file, seed, hero, replay.length(), replay.score );

		try {
			ReplayIO.write( replay, file );
			//read back rather than verifying the object in hand: a header that does not round-trip is a
			//different failure from a replay that does not reproduce, and conflating them hides the first
			Replay read = ReplayIO.read( file );
			c.replay = read;
			c.steps = read.length();
		} catch (IOException e){
			c.diverged = true;
			c.divergence = "could not write and re-read the recording (" + e.getClass().getSimpleName()
					+ ": " + e.getMessage() + ")";
			failures.add( c.name() + ": " + c.divergence );
			return c;
		}

		ReplayIO.Verification result = verify( c.replay );

		c.diverged = result.diverged;
		c.divergedAt = result.divergedAt;
		c.divergence = result.divergence;
		c.verifiedSteps = result.stepsVerified;
		c.reproducedScore = result.score;

		if (result.diverged){
			failures.add( c.name() + " (" + c.steps + " steps) diverged at step " + result.divergedAt
					+ " of " + c.steps + ": " + result.divergence + " - recording at "
					+ file.getAbsolutePath() );
		} else if (Math.abs( result.score - c.recordedScore ) > 1e-6){
			//not a divergence by the step-wise comparison, so it is reported separately rather than as
			//one: the trajectory matched and the total did not, which points at the reward accounting
			//rather than at the simulation
			failures.add( c.name() + " replayed step for step but scored " + result.score
					+ " against a recorded " + c.recordedScore );
		} else if (result.stepsVerified < c.steps){
			failures.add( c.name() + " verified only " + result.stepsVerified + " of " + c.steps
					+ " recorded steps before the episode ended" );
		}

		return c;
	}

	/**
	 * Verifies a recording in its own environment.
	 *
	 * <p>A fresh {@link SPDEnv} per verification rather than a shared one: the whole point is that a
	 * recording replays in a process that has done something else, so reusing an environment would hide
	 * the thing being tested.
	 */
	private static ReplayIO.Verification verify( Replay replay ){
		EnvConfig config = ReplayIO.configFor( replay );
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		return ReplayIO.verify( replay, env );
	}

	private static void report( List<Case > cases, List<String> secondPass ){
		System.out.println();
		System.out.printf( "  %-22s %-9s %6s %7s %10s  %s%n",
				"seed", "hero", "steps", "turns", "score", "verdict" );
		System.out.println( "  " + "-".repeat( 72 ));

		for (Case c : cases){
			String verdict = c.diverged
					? "DIVERGED at " + c.divergedAt + ": " + c.divergence
					: "reproduced (" + c.verifiedSteps + " steps, " + String.format( "%.3f", c.reproducedScore ) + ")";

			System.out.printf( "  %-22s %-9s %6d %7d %10s  %s%n",
					c.seed, c.hero, c.steps, c.replay.turns,
					String.format( "%.3f", c.recordedScore ), verdict );
		}

		if (!secondPass.isEmpty()) failures.addAll( secondPass );

		System.out.println();
		if (secondPass.isEmpty()){
			System.out.println( "  all " + cases.size() + " recordings also re-verified cleanly after the"
					+ " whole sweep had run in between them" );
		} else {
			System.out.println( "  " + secondPass.size() + " recording(s) failed only on re-verification,"
					+ " which is the signature of state surviving a reset:" );
			for (String s : secondPass) System.out.println( "        " + s );
		}
	}
}