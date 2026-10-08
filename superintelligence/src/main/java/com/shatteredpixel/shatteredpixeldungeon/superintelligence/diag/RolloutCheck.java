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
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Asserts what a recorded episode records, on the path the {@code rollout} command actually uses.
 *
 * <p>{@code collectcheck} already covers the collector path that training runs on. This covers the
 * command, and it exists because two faults lived there and neither was reachable from any gate: an
 * empty-seed guard that rejected the flag <em>and</em> its absence, making random seeds unreachable
 * outright, and a recording that stored the seed it was asked for rather than the one the environment
 * resolved. On a random episode that second one produces a replay nothing can ever verify - verify
 * draws a different random world and reports a step-0 divergence that reads like a broken seed lock.
 *
 * <p>{@code SeedPool} releases 10% of episodes onto random seeds deliberately, so a random-seed episode
 * is the common case rather than an edge case.
 *
 * <p>Both halves are asserted: that a random episode records a usable seed, and that what it records
 * verifies. The first alone would pass against a recording carrying a seed that cannot rebuild the
 * world, which is the actual failure.
 */
public class RolloutCheck {

	private static final int CHECKS = 3;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-rolloutcheck" ));
		HeadlessServices.disableSaving( true );
		HeadlessGame.install();

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 120;

		check( () -> checkRandomSeedIsRecorded( config ) );
		check( () -> checkRandomSeedRecordingVerifies( config ) );
		check( () -> checkAnExplicitSeedIsRecordedVerbatim( config ) );

		if (failures.isEmpty()){
			System.out.println( "[OK]     rollout recording: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  rollout recording: " + failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	private static void check( Runnable body ){
		body.run();
	}

	private static ScriptedEpisode.Result randomEpisode( EnvConfig config ){
		return ScriptedEpisode.record( config, "", HeroClass.WARRIOR, 60, 3 );
	}

	/** An episode asked for no seed must still say which seed it ran on. */
	private static void checkRandomSeedIsRecorded( EnvConfig config ){
		ScriptedEpisode.Result episode = randomEpisode( config );

		String recorded = episode.replay.seedText;
		if ( recorded == null || recorded.trim().isEmpty() ){
			fail( "an episode asked for no seed recorded none, so verify would reset onto a different "
					+ "draw and diverge at step 0" );
			return;
		}

		//the seed the environment resolved, not the empty request it was handed
		if ( !recorded.equals( episode.seedText() )){
			fail( "the recording names seed [" + recorded + "] but the environment resolved ["
					+ episode.seedText() + "]" );
		}
	}

	/**
	 * A recording from a random episode must actually verify.
	 *
	 * <p>Round-tripped through the file rather than checked in memory, because the header is what a
	 * verifier reads, and a field that never reaches the file is indistinguishable from one that was
	 * written wrongly until someone tries to use it.
	 */
	private static void checkRandomSeedRecordingVerifies( EnvConfig config ){
		ScriptedEpisode.Result episode = randomEpisode( config );

		File file = new File( System.getProperty( "java.io.tmpdir" ), "rolloutcheck-random.replay" );
		try {
			ReplayIO.write( episode.replay, file );
		} catch (java.io.IOException e){
			fail( "could not write the fixture: " + e );
			return;
		}

		Replay read;
		try {
			read = ReplayIO.read( file );
		} catch (java.io.IOException e){
			fail( "could not read back the fixture: " + e );
			return;
		}

		if ( read.seedText.trim().isEmpty() ){
			fail( "a random-seed recording does not carry its seed through the file" );
			return;
		}

		EnvConfig replayed = ReplayIO.configFor( read );
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv verifyEnv =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv(
						replayed, HeadlessGame.install() );

		ReplayIO.Verification result = ReplayIO.verify( read, verifyEnv );

		if ( result.diverged ){
			fail( "a recording made from a random-seed episode does not verify: " + result.divergence
					+ " at step " + result.divergedAt );
		}
		if ( result.stepsVerified != read.steps.size() ){
			fail( "verification covered " + result.stepsVerified + " of " + read.steps.size()
					+ " steps; a short match is not a reproduction" );
		}
	}

	/** An explicit seed must be stored as asked, not replaced by something derived. */
	private static void checkAnExplicitSeedIsRecordedVerbatim( EnvConfig config ){
		ScriptedEpisode.Result episode =
				ScriptedEpisode.record( config, "ROLLOUTCHECK-A", HeroClass.WARRIOR, 60, 3 );

		if ( !episode.replay.seedText.equals( "ROLLOUTCHECK-A" )){
			fail( "an episode asked for seed ROLLOUTCHECK-A recorded [" + episode.replay.seedText
					+ "] instead" );
		}
	}

	private static void fail( String message ){
		failures.add( message );
	}
}