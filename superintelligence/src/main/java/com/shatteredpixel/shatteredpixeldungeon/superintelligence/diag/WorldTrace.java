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

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldDiff;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldSnapshot;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * The headless half of the world snapshot: replays a recording through the environment and writes what
 * the world contained at every step.
 *
 * <p>Written to be diffed against the same recording played in the rendered viewer, which is the only
 * comparison in the suite where the two sides do not share a code path. Everything else - verify,
 * playbackcheck, episodediff, paritycheck - compares a headless run against another headless run, so a
 * fault in the render loop cannot show up in any of them. This is the producer for the one that can.
 */
public class WorldTrace {

	private WorldTrace(){
	}

	public static void main( String[] args ){
		if (args.length == 0){
			System.err.println( "[ERROR] worldtrace needs a replay file" );
			System.err.println( "  usage: worldtrace <file.replay> <out.txt> [steps] [--no-cells]" );
			System.exit( 1 );
			return;
		}

		File file = new File( args[0] );
		Path out = Paths.get( args[1] );
		int limit = args.length > 2 && !args[2].startsWith( "--" )
				? Integer.parseInt( args[2] ) : Integer.MAX_VALUE;
		boolean cells = !List.of( args ).contains( "--no-cells" );

		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-worldtrace" ));
		HeadlessServices.disableSaving( true );

		Replay replay;
		try {
			replay = ReplayIO.read( file );
		} catch (java.io.IOException e){
			System.err.println( "[ERROR] cannot read the replay " + file
					+ " (" + e.getClass().getSimpleName() + ")" );
			System.exit( 1 );
			return;
		}

		WorldSnapshot snapshot = new WorldSnapshot().cells( cells );

		SPDEnv env = new SPDEnv( ReplayIO.configFor( replay ), HeadlessGame.install() );
		env.config().turnLimitPerFloor = replay.turnLimitPerFloor;
		env.config().maxSlots = replay.maxSlots;
		env.config().allowEquipping = replay.allowEquipping;

		HeroClass heroClass;
		try {
			heroClass = HeroClass.valueOf( replay.heroClass );
		} catch (IllegalArgumentException e){
			heroClass = HeroClass.WARRIOR;
		}
		env.reset( replay.seedText, heroClass );
		snapshot.dungeonSeed( com.shatteredpixel.shatteredpixeldungeon.Dungeon.seed );

		int replayed = 0;

		for (int i = 0; i < replay.steps.size() && i < limit; i++){
			if (!env.running()) break;
			Replay.Step step = replay.steps.get( i );
			env.step( Action.valueOf( step.action ), step.slot );
			snapshot.sample( "step", i );
			replayed++;
		}

		try {
			snapshot.writeTo( out );
		} catch (java.io.IOException e){
			System.err.println( "[ERROR] cannot write " + out + " (" + e.getClass().getSimpleName() + ")" );
			System.exit( 1 );
			return;
		}

		System.out.println( "[worldtrace] " + file.getName() + ": " + replayed + " steps -> " + out
				+ " (" + snapshot.lines().size() + " records, cells=" + cells + ")" );

		//A file of records that never change is a broken instrument, so say so rather than leaving a
		//caller to notice. Every step of a real run moves the hero, so consecutive records must differ.
		List< String > changed = WorldDiff.changedRecords( snapshot, "step" );
		if (changed.isEmpty()){
			System.out.println( "[worldtrace] WARNING no step record differs from the one before it;"
					+ " the snapshot is not distinguishing anything" );
		} else {
			System.out.println( "[worldtrace] " + changed.size() + " of " + replayed
					+ " steps changed the world relative to the previous one" );
		}
	}
}