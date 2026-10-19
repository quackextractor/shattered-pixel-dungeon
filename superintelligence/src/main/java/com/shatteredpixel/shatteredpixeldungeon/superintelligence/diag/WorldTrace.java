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
		File file = com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.CliOptions.resolve(
			new File( args[0] ) );
		Path out = Paths.get( args[1] );
		int limit = args.length > 2 && !args[2].startsWith( "--" )
				? Integer.parseInt( args[2] ) : Integer.MAX_VALUE;
		boolean cells = !List.of( args ).contains( "--no-cells" );
		HeadlessServices.install( Scratch.dir( "worldtrace" ));
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
