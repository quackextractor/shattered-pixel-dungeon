package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldDiff;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.WorldSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
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
