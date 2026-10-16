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

	private static void checkRandomSeedIsRecorded( EnvConfig config ){
		ScriptedEpisode.Result episode = randomEpisode( config );

		String recorded = episode.replay.seedText;
		if ( recorded == null || recorded.trim().isEmpty() ){
			fail( "an episode asked for no seed recorded none, so verify would reset onto a different "
					+ "draw and diverge at step 0" );
			return;
		}

		if ( !recorded.equals( episode.seedText() )){
			fail( "the recording names seed [" + recorded + "] but the environment resolved ["
					+ episode.seedText() + "]" );
		}
	}

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