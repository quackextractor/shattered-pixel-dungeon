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

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldDiff;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Reads two {@link WorldSnapshot} files and reports where and how they differ.
 *
 * <p>This is the reader half of the only two-sided comparison in the project. Every other check -
 * {@code verify}, {@code playbackcheck}, {@code episodediff}, {@code paritycheck} - compares a headless
 * run against another headless run, so a fault in the render loop cannot appear in any of them by
 * construction. {@code worldtrace} produces the headless side and {@code ReplayPlayer} behind
 * {@code -Dspd.worldTrace} produces the rendered side; this is what says whether they agree.
 *
 * <p>It exists as a program rather than as a paragraph in a document because the finding it exists to
 * settle was reached four times by reading the source and was wrong three times. The measurements that
 * settled it were two files and a diff, and having to reconstruct them by hand each time is what let a
 * refuted hypothesis stand.
 *
 * <pre>
 *   viewdiff &lt;headless.txt&gt; &lt;rendered.txt&gt;
 * </pre>
 */
public class WorldCompare {

	private WorldCompare(){
	}

	public static void main( String[] args ) throws IOException {
		if (args.length < 2){
			System.err.println( "[ERROR] viewdiff needs two snapshot files" );
			System.err.println( "  usage: viewdiff <headless.txt> <rendered.txt>" );
			System.exit( 1 );
			return;
		}

		Path golden = Paths.get( args[0] );
		Path rendered = Paths.get( args[1] );

		WorldSnapshot mine;
		try {
			mine = WorldSnapshot.read( rendered );
		} catch (IOException e){
			System.err.println( "[ERROR] cannot read " + rendered + " (" + e.getClass().getSimpleName() + ")" );
			System.exit( 1 );
			return;
		}

		//Steps first, because that is the comparison that has a verdict. Frames only exist on the
		//rendered side - the headless run has no render loop - so a frame question is answered by asking
		//what changed between frames, not by diffing two frame sets.
		String stepDiff = WorldDiff.compare( golden, mine );
		System.out.println( stepDiff.isEmpty()
				? "[OK]     every step record agrees"
				: "[DIFF]   " + stepDiff );

		reportFrames( mine );

		List< String > changed = WorldDiff.changedRecords( mine, "step" );
		System.out.println( "[viewdiff] " + changed.size() + " of the step records changed the world"
				+ " relative to the one before it" );

		if (!stepDiff.isEmpty()) System.exit( 1 );
	}

	/**
	 * Frames on which the world moved while no recorded step was applied.
	 *
	 * <p>The question neither side can answer alone. A headless run has no frames, so a two-sided step
	 * diff cannot see it; a rendered run has frames but no second run to compare them against, so
	 * nothing marks them. Within one file, a frame whose state differs from the previous frame's is
	 * exactly a change the recording never authorised - and every one of them is a candidate cause for
	 * whatever the step diff found.
	 */
	private static void reportFrames( WorldSnapshot snapshot ){
		Map< String, Map< String, List< String > > > parsed =
				WorldSnapshot.parse( snapshot.lines() );

		String previous = null;
		String previousLabel = null;
		int moved = 0;
		int idle = 0;
		int reported = 0;

		for (Map.Entry< String, Map< String, List< String > > > e : parsed.entrySet()){
			if (!e.getKey().startsWith( "frame:" )) continue;

			StringBuilder joined = new StringBuilder();
			for (List< String > rows : e.getValue().values()){
				for (String r : rows) joined.append( r ).append( '\n' );
			}
			String current = joined.toString();

			if (previous != null && !previous.equals( current )){
				moved++;
				if (reported < 20){
					reported++;
					System.out.println( "[viewdiff] frame " + previousLabel + " -> " + e.getKey()
							+ " changed with no recorded step" );
				}
			} else {
				idle++;
			}

			previous = current;
			previousLabel = e.getKey();
		}

		System.out.println( "[viewdiff] " + moved + " frames changed the world and " + idle
				+ " did not; a recorded step is applied on some frames and not others, and only the"
				+ " second kind can hide a turn" );
	}
}
