package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.journal.Document;
import com.shatteredpixel.shatteredpixeldungeon.utils.Holiday;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;


public class ResetCheck {

	private static final int CHECKS = 9;

	private static final List< String > failures = new ArrayList<>();


	private static final int TRACE_STEPS = 320;

	private static final String[] SEEDS = {
		"RESETCHECK-A", "RESETCHECK-B", "RESETCHECK-C", "RESETCHECK-D"
	};


	private static final String DYING_SEED = "PARITY-ALPHA";

	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "resetcheck" ));
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 2000;
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );

		checkResetClearsAnArmedAim( env, config );
		checkResetClearsAnOpenDialog( env );
		checkARunDoesNotInheritRemains();
		checkEveryEpisodeStartsInWorld( env, config );
		checkTrajectoriesDoNotDependOnOrder( env, config );
		checkSpecialRoomPoolsDoNotDependOnOrder( env, config );
		checkGuidePagesDoNotSurviveAReset( env, config );
		checkTheCalendarDoesNotReachTheGame( env );
		checkUnsavedSavesStayInMemory();

		if (failures.isEmpty()){
			System.out.println( "[OK]     reset isolation: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  reset isolation: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}


	private static void checkUnsavedSavesStayInMemory(){
		String name = "game9/memorycheck.dat";
		try {
			for (int v = 1; v <= 2; v++){
				com.watabou.utils.Bundle b = new com.watabou.utils.Bundle();
				b.put( "value", v );
				com.watabou.utils.FileUtils.bundleToFile( name, b );
			}
			int read = com.watabou.utils.FileUtils.bundleFromFile( name ).getInt( "value" );
			boolean listed = com.watabou.utils.FileUtils.getFileHandle( "game9" ).list().length == 1;
			boolean onDisk = com.badlogic.gdx.Gdx.files.local( name ).file().exists();
			com.watabou.utils.FileUtils.deleteFile( name );
			boolean gone = !com.watabou.utils.FileUtils.fileExists( name );
			if (read != 2 || !listed || onDisk || !gone){
				failures.add( "an unsaved save did not round-trip in memory: read " + read + ", listed " + listed
						+ ", on disk " + onDisk + ", deleted " + gone );
				return;
			}
		} catch (java.io.IOException e){
			failures.add( "an unsaved save threw " + e );
			return;
		}
		System.out.println( "  with saving disabled a save is rewritten through its temp file, read back, listed and"
				+ " deleted in memory, and never reaches the disk" );
	}

	private static void checkResetClearsAnArmedAim( SPDEnv env, EnvConfig config ){
		env.reset( SEEDS[ 0 ], HeroClass.WARRIOR );

		GameScene.selectCell( new CellSelector.Listener(){
			@Override public void onSelect( Integer cell ){}
			@Override public String prompt(){ return "test"; }
		} );

		if (GameScene.pendingCellListener() == null){
			fail( "GameScene.selectCell did not arm a pending listener, so the case cannot test whether"
					+ " reset disarms one." );
			return;
		}

		env.reset( SEEDS[ 1 ], HeroClass.WARRIOR );

		if (env.mode() != EnvMode.WORLD){
			fail( "an episode began in " + env.mode() + " instead of WORLD while an aim request from the"
					+ " previous episode was still outstanding. settle() checks the pending listener before"
					+ " running any turn, so the episode starts by reading the agent's first action as a"
					+ " target choice; the hero never moves and every recording made in that state"
					+ " diverges at step 0. Measured before the fix: 4 of 10 recorded runs diverged when"
					+ " verified in sequence, and one verified clean alone and diverged second." );
			return;
		}

		System.out.println( "  an outstanding aim request does not carry into the next episode, which"
				+ " starts in WORLD" );
	}


	private static void checkResetClearsAnOpenDialog( SPDEnv env ){
		env.reset( SEEDS[ 1 ], HeroClass.WARRIOR );

		GameScene.show( new WndOptions( "RESETCHECK", "dialog left open by the previous episode",
				"Yes", "No" ) );

		if (!WindowBridge.open()){
			fail( "GameScene.show did not park an answerable window, so the case cannot test whether"
					+ " reset clears one." );
			return;
		}

		env.reset( SEEDS[ 2 ], HeroClass.WARRIOR );

		if (GameScene.headlessWindow() != null){
			fail( "a dialog left open by the previous episode was still parked after reset ("
					+ GameScene.headlessWindow().getClass().getSimpleName() + "). settle() tests for it"
					+ " before running any turn, so the episode starts by reading the agent's first"
					+ " action as a menu choice." );
			return;
		}

		play( env, 40 );

		if (WindowBridge.open()){
			fail( "an answerable dialog from the previous episode was still parked after 40 steps of the"
					+ " new one (" + GameScene.headlessWindow().getClass().getSimpleName() + "). settle()"
					+ " hands the turn to MENU for as long as one is open, so the episode can never start."
					+ " The parked *slot* is asserted above, immediately after reset; a bare window here"
					+ " could be an informational one the run itself opened, which is not a leak." );
			return;
		}

		System.out.println( "  an outstanding dialog does not carry into the next episode, which starts"
				+ " in WORLD and leaves nothing answerable parked" );
	}


	private static void checkARunDoesNotInheritRemains(){
		EnvConfig config = new EnvConfig();

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode.Result first =
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode.record(
						config, DYING_SEED, HeroClass.ROGUE, 0, 1 );

		if (first.env.endReason() != com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward
				.RewardModel.TerminateReason.DEATH){
			fail( "the first episode ended " + first.env.endReason() + " after "
					+ first.env.turnsTotal() + " turns rather than dying, so it left no remains and this"
					+ " case cannot test whether a run inherits them. Pick a seed that dies." );
			return;
		}

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ScriptedEpisode.record(
				config, SEEDS[ 3 ], HeroClass.WARRIOR, 0, 2 );

		List<String> inherited = new ArrayList<>();
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			Heap heap = Dungeon.level.heaps.get( cell );
			if (heap == null || heap.type != Heap.Type.REMAINS) continue;
			for (com.shatteredpixel.shatteredpixeldungeon.items.Item item : heap.items){
				if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.remains.RemainsItem){
					inherited.add( item.getClass().getSimpleName() + " at " + cell );
				}
			}
		}

		if (!inherited.isEmpty()){
			fail( "the second run's first floor carries a REMAINS heap belonging to the ROGUE that died in"
					+ " the first run: " + String.join( ", ", inherited ) + ". Remains are process-spanning"
					+ " game state, so a run in this process is not a function of its seed. Every recording"
					+ " made after a death describes a floor the run that replays it will never generate." );
			return;
		}

		System.out.println( "  a run following a hero's death inherits none of that hero's remains" );
	}


	private static void checkEveryEpisodeStartsInWorld( SPDEnv env, EnvConfig config ){

		for (int i = 0; i < 4; i++){
			env.reset( SEEDS[ i % SEEDS.length ], HeroClass.WARRIOR );

			if (env.mode() != EnvMode.WORLD){
				fail( "episode " + i + " of four consecutive resets started in " + env.mode()
						+ " instead of WORLD. A worker runs thousands of episodes in one process, so"
						+ " whatever the previous episode left behind is reaching this one." );
				return;
			}

			play( env, 60 );
		}

		System.out.println( "  four consecutive episodes all started in WORLD" );
	}


	private static void checkTrajectoriesDoNotDependOnOrder( SPDEnv env, EnvConfig config ){
		String[] first = new String[ SEEDS.length ];
		for (int i = 0; i < SEEDS.length; i++) first[ i ] = trace( env, config, SEEDS[ i ] );

		for (int i = 0; i < SEEDS.length; i++){
			String seed = SEEDS[ i ];
			String second = trace( env, config, seed );

			if (!first[ i ].equals( second )){
				fail( "seed " + seed + " produced a different trajectory the second time in the same"
						+ " process. The environment is not a function of its reset arguments, so any"
						+ " recording made here is unreproducible and every locked-seed comparison built"
						+ " on it is meaningless." + firstDifference( first[ i ], second ) );
				return;
			}
		}

		System.out.println( "  " + SEEDS.length + " seeds reproduce identically after running the"
				+ " others in between (" + TRACE_STEPS + " steps each)" );
	}


	private static void checkSpecialRoomPoolsDoNotDependOnOrder( SPDEnv env, EnvConfig config ){
		String[] first = new String[ SEEDS.length ];
		for (int i = 0; i < SEEDS.length; i++) first[ i ] = pools( env, config, SEEDS[ i ] );

		for (int i = 0; i < SEEDS.length; i++){
			String seed = SEEDS[ i ];
			String second = pools( env, config, seed );

			if (!first[ i ].equals( second )){
				fail( "seed " + seed + " produced a different special-room order the second time in the"
						+ " same process. The pools decide which room every floor gets, so floor 2 of one"
						+ " run is not floor 2 of another - and a recording made here replays into a"
						+ " different dungeon. First difference: \"" + first[ i ] + "\" vs \"" + second + "\"" );
				return;
			}
		}

		System.out.println( "  the special-room order is a function of the seed for " + SEEDS.length
				+ " seeds run around each other" );
	}


	private static String pools( SPDEnv env, EnvConfig config, String seed ){
		env.config().turnLimitPerFloor = config.turnLimitPerFloor;
		env.reset( seed, HeroClass.WARRIOR );

		StringBuilder sb = new StringBuilder();
		sb.append( "run=" );
		for (Object room : com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SpecialRoom
				.runSpecials){
			sb.append( nameOf( room )).append( ',' );
		}
		sb.append( "\nfloor=" );
		for (Object room : com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SpecialRoom
				.floorSpecials){
			sb.append( nameOf( room )).append( ',' );
		}
		sb.append( "\npitNeededDepth=" )
				.append( pitNeededDepth() );
		return sb.toString();
	}


	private static String pitNeededDepth(){
		try {
			java.lang.reflect.Field f = com.shatteredpixel.shatteredpixeldungeon.levels.rooms
					.special.SpecialRoom.class.getDeclaredField( "pitNeededDepth" );
			f.setAccessible( true );
			return String.valueOf( f.get( null ));
		} catch (ReflectiveOperationException e){
			return "?";
		}
	}

	private static String nameOf( Object room ){
		return room instanceof Class ? ((Class<?>) room).getSimpleName() : String.valueOf( room );
	}


	private static void play( SPDEnv env, int steps ){
		Random rng = new Random( 4242L );
		Action[] all = Action.values();

		for (int i = 0; i < steps && env.running(); i++ ){
			Action a = env.mode() == EnvMode.WORLD ? all[ rng.nextInt( all.length ) ] : Action.CANCEL;
			env.step( a, rng.nextInt( 8 ) );
		}
	}


	private static String trace( SPDEnv env, EnvConfig config, String seed ){
		env.config().turnLimitPerFloor = config.turnLimitPerFloor;
		env.reset( seed, HeroClass.WARRIOR );

		StringBuilder sb = new StringBuilder();
		sb.append( "start mode=" ).append( env.mode() ).append( " pos=" ).append( env.heroPosition() );

		Random rng = new Random( 4242L );
		Action[] all = Action.values();

		for (int i = 0; i < TRACE_STEPS && env.running(); i++ ){
			Action a = env.mode() == EnvMode.WORLD ? all[ rng.nextInt( all.length ) ] : Action.CANCEL;
			int slot = rng.nextInt( 8 );
			env.step( a, slot );

			sb.append( '\n' ).append( i ).append( ' ' ).append( a ).append( '/' ).append( slot )
					.append( " pos=" ).append( env.heroPosition() )
					.append( " mode=" ).append( env.mode() )
					.append( " depth=" ).append( env.depth() );
		}

		sb.append( "\nend=" ).append( env.endReason() )
				.append( " turns=" ).append( env.turnsTotal() )
				.append( " score=" ).append( env.ledger().total() );

		return sb.toString();
	}

	private static String firstDifference( String a, String b ){
		String[] x = a.split( "\n" ), y = b.split( "\n" );
		for (int i = 0; i < Math.max( x.length, y.length ); i++ ){
			String p = i < x.length ? x[ i ] : "<none>";
			String q = i < y.length ? y[ i ] : "<none>";
			if (!p.equals( q )) return " First difference: \"" + p + "\" vs \"" + q + "\".";
		}
		return "";
	}

	private static void checkGuidePagesDoNotSurviveAReset( SPDEnv env, EnvConfig config ){
		String clean = floorTwo( env, config, SEEDS[ 0 ] );

		if (clean.isEmpty()){
			fail( "floor 2 could not be generated for " + SEEDS[ 0 ]
					+ ", so this case cannot say whether the reset forgot the guide pages." );
			return;
		}

		Document.ADVENTURERS_GUIDE.findPage( Document.GUIDE_SEARCHING );
		Document.ADVENTURERS_GUIDE.findPage( Document.GUIDE_ALCHEMY );
		Document.ADVENTURERS_GUIDE.findPage( Document.GUIDE_FOOD );

		String afterCollection = floorTwo( env, config, SEEDS[ 0 ] );

		if (!clean.equals( afterCollection )){
			fail( "floor 2 of " + SEEDS[ 0 ] + " is a different dungeon once three guide pages have been"
					+ " collected, and a reset did not put them back. Level generation asks the"
					+ " process-wide Document which pages are still missing - RegularLevel.createItems"
					+ " drops one from that list and EntranceRoom drops floor 2's outright - so every"
					+ " episode after the first in this process builds a different dungeon, and a"
					+ " recording made in one of them will not replay in any other."
					+ firstDifference( clean, afterCollection ) );
			return;
		}

		System.out.println( "  three collected guide pages leave the next floor 2 identical ("
				+ clean + ")" );
	}


	private static void checkTheCalendarDoesNotReachTheGame( SPDEnv env ){
		Holiday.pin( Holiday.HALLOWEEN );
		env.reset( SEEDS[ 0 ], HeroClass.WARRIOR );
		Holiday read = Holiday.getCurrentHoliday();
		Holiday.clearCachedHoliday();
		env.reset( SEEDS[ 0 ], HeroClass.WARRIOR );
		Holiday dated = Holiday.getCurrentHoliday();
		if (read != Holiday.NONE || dated != Holiday.NONE){
			fail( "a reset left the holiday at " + read + " after HALLOWEEN, and at " + dated + " read from today's"
					+ " date; Pasty and RatKing read it, so a recording made on one date diverges on another" );
			return;
		}
		System.out.println( "  a reset pins the holiday to NONE, whatever the date or the previous run" );
	}


	private static String floorTwo( SPDEnv env, EnvConfig config, String seed ){
		env.config().turnLimitPerFloor = config.turnLimitPerFloor;
		env.reset( seed, HeroClass.WARRIOR );

		Dungeon.depth = 2;
		Dungeon.branch = 0;
		Dungeon.generatedLevels.clear();
		Level level = Dungeon.newLevel();
		if (level == null || level.length() == 0) return "";

		StringBuilder sb = new StringBuilder();
		int plants = 0, heaps = 0, traps = 0;
		for (int i = 0; i < level.length(); i++){
			if (level.plants.get( i ) != null) plants++;
			if (level.heaps.get( i ) != null) heaps++;
			if (level.traps.get( i ) != null) traps++;
		}
		sb.append( level.width() ).append( 'x' ).append( level.height() )
				.append( " map=" ).append( java.util.Arrays.hashCode( level.map ))
				.append( " walls=" ).append( java.util.Arrays.hashCode( level.customWalls.toArray() ))
				.append( " heaps=" ).append( heaps )
				.append( " traps=" ).append( traps )
				.append( " mobs=" ).append( level.mobs.size() )
				.append( " plants=" ).append( plants );
		return sb.toString();
	}


	private static void fail( String message ){
		failures.add( message );
	}
}
