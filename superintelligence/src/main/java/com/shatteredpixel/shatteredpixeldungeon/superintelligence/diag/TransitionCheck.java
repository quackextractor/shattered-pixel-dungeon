package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardLedger;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.StairPotential;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.ascend;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.descend;


public class TransitionCheck {

	private static final int CHECKS = 12;

	private static final String SEED = "TRANSITION";


	private static final String[] ARRIVAL_SEEDS = {
		"ARRIVAL-A", "ARRIVAL-B", "ARRIVAL-C", "ARRIVAL-D", "ARRIVAL-E", "ARRIVAL-F",
		"ARRIVAL-G", "ARRIVAL-H", SEED
	};

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-transitioncheck" ));
		HeadlessServices.disableSaving( true );

		checkDescentIsServiced();
		checkDescentDoesNotEndTheEpisode();
		checkDescentIsRepeatable();
		checkDepthIsPaidFor();
		checkTheFloorLeftBehindIsTheOneMarkedCleared();
		checkTheNewFloorIsPlayable();
		checkTheEngineClockRestartsWithTheFloor();
		checkTheHeroActsOnArrivalBeforeTheAgentDoes();
		checkAscendingAfterDescendingIsServiced();
		checkTheStairsAreNotShapedAcrossAFloorChange();
		checkClimbingBackDoesNotRefillTheFloorsTurns();
		checkAVisitedFloorIsTheSameFloor();

		if (failures.isEmpty()){
			System.out.println( "[OK]     floor transitions: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  floor transitions: " + failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}


	private static SPDEnv freshRun(){
		return freshRun( SEED );
	}

	private static SPDEnv freshRun( String seed ){
		EnvConfig config = new EnvConfig();
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( seed, HeroClass.WARRIOR );
		for (int i = 0; i < 3; i++){
			if (!env.running()) break;
			env.step( Action.WAIT, 0 );
		}
		return env;
	}


	private static void checkDescentIsServiced(){
		SPDEnv env = freshRun();
		int before = Dungeon.depth;

		if (!descend( env )){
			fail( "floor " + before + " has no REGULAR_EXIT to descend from, so this case measured"
					+ " nothing. Every standard floor generates one." );
			return;
		}

		env.step( Action.WAIT, 0 );

		if (Dungeon.depth != before + 1){
			fail( "the hero asked to descend and the environment is still on depth " + Dungeon.depth
					+ ". The game raises a scene-switch request, and nothing headless was ever reading it:"
					+ " HeadlessGame.switchRequested() reported only the acknowledgement, which is raised"
					+ " in Game.step() - reached from Game.render(), which never runs headlessly." );
			return;
		}

		if (Dungeon.hero == null || !Dungeon.hero.isAlive()){
			fail( "the descent left no living hero on depth " + Dungeon.depth + "." );
			return;
		}

		System.out.println( "  a descent is serviced: depth " + before + " -> " + Dungeon.depth
				+ ", hero alive at " + Dungeon.hero.pos );
	}


	private static void checkDescentDoesNotEndTheEpisode(){
		SPDEnv env = freshRun();

		if (!descend( env )){
			fail( "no exit to descend from, so this case measured nothing." );
			return;
		}

		env.step( Action.WAIT, 0 );

		if (!env.running()){
			fail( "the step that descended ended the episode as " + env.endReason() + " after "
					+ env.turnsTotal() + " turns, with the hero on " + Dungeon.hero.HP + "/"
					+ Dungeon.hero.HT + " health. That is issues.md 9: the hero walks down the stairs at"
					+ " full health and the recording ends STALLED on the spot." );
			return;
		}

		if (env.endReason() == RewardModel.TerminateReason.STALLED ){
			fail( "the descent left endReason=STALLED while the episode is still running, so the"
					+ " reason is already wrong." );
			return;
		}

		System.out.println( "  descending does not end the episode; reason after the descent is "
				+ env.endReason() );
	}


	private static void checkDescentIsRepeatable(){
		SPDEnv env = freshRun();
		int start = Dungeon.depth;

		for (int floor = 0; floor < 2; floor++){
			if (!env.running()){
				fail( "the episode ended as " + env.endReason() + " before descent " + (floor + 1)
						+ ", so this case measured nothing." );
				return;
			}
			if (!descend( env )){
				fail( "depth " + Dungeon.depth + " has no exit, so descent " + (floor + 1)
						+ " was not attempted." );
				return;
			}
			env.step( Action.WAIT, 0 );

			if (Dungeon.depth != start + floor + 1){
				fail( "descent " + ( floor + 1 ) + " did not take: depth is " + Dungeon.depth
						+ ", expected " + ( start + floor + 1 ) + ". The first one working and the second"
						+ " not is the signature of a request that was never acknowledged and cleared." );
				return;
			}
		}

		System.out.println( "  two descents in one episode, depth " + start + " -> " + Dungeon.depth );
	}


	private static void checkDepthIsPaidFor(){
		EnvConfig config = new EnvConfig();
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( SEED, HeroClass.WARRIOR );
		for (int i = 0; i < 3; i++) env.step( Action.WAIT, 0 );

		if (!descend( env )){
			fail( "no exit to descend from, so this case measured nothing." );
			return;
		}

		env.step( Action.WAIT, 0 );

		double paid = env.ledger().termTotal( RewardTerm.DEPTH_ADVANCE );

		if (paid < config.depthReward - 1e-3 ){
			fail( "descending paid " + paid + " of DEPTH_ADVANCE, expected " + config.depthReward
					+ ". The term is the primary goal - the one research.md says survives into the sparse"
					+ " phase - and it could not fire: SPDEnv.onFloorTransition reset the reward snapshot"
					+ " after the depth had changed, so the next step re-took it at the new depth and"
					+ " compared it against itself." );
			return;
		}

		System.out.println( "  descending pays " + paid + " in DEPTH_ADVANCE (depthReward is "
				+ config.depthReward + ")" );
	}


	private static void checkTheFloorLeftBehindIsTheOneMarkedCleared(){
		SPDEnv env = freshRun();
		if (!descend( env )){
			fail( "no exit to descend from, so this case measured nothing." );
			return;
		}
		env.step( Action.WAIT, 0 );

		java.util.ArrayList< RewardLedger.FloorTotals > floors = env.ledger().floors();
		if (floors.size() < 2 ){
			fail( "the ledger has " + floors.size() + " floor row(s) after a descent, expected 2. The"
					+ " per-floor report is the thing this asserts on, so one row means it measured"
					+ " nothing." );
			return;
		}

		RewardLedger.FloorTotals left = floors.get( floors.size() - 2 );
		RewardLedger.FloorTotals arrived = floors.get( floors.size() - 1 );

		if (!left.cleared){
			fail( "the floor the hero left is not marked cleared. FloorTotals.cleared is what the"
					+ " per-floor report renders as 'left by descending'." );
			return;
		}

		if (arrived.cleared){
			fail( "the floor the hero just arrived on is marked cleared. DEPTH_ADVANCE is scored on the"
					+ " new floor's first turn, so marking there attributes the descent to the wrong"
					+ " floor and every floor of a descending run reads as cleared." );
			return;
		}

		System.out.println( "  floor " + left.depth + " is marked cleared and floor " + arrived.depth
				+ " is not" );
	}


	private static void checkTheNewFloorIsPlayable(){
		SPDEnv env = freshRun();
		if (!descend( env )){
			fail( "no exit to descend from, so this case measured nothing." );
			return;
		}
		env.step( Action.WAIT, 0 );

		if (Dungeon.level == null){
			fail( "the descent left Dungeon.level null." );
			return;
		}
		if (!(Dungeon.hero.sprite instanceof com.shatteredpixel.shatteredpixeldungeon.superintelligence
				.headless.HeadlessSprite)){
			fail( "the hero arrived on depth " + Dungeon.depth + " without a headless sprite, and the"
					+ " scheduler dereferences it on the next turn." );
			return;
		}

		int before = env.turnsTotal();
		for (int i = 0; i < 5; i++){
			if (!env.running()) break;
			env.step( Action.WAIT, 0 );
		}

		if (!env.running()){
			fail( "the episode ended as " + env.endReason() + " within 5 turns of arriving on depth "
					+ Dungeon.depth + ". The transition itself succeeded; what follows it does not." );
			return;
		}

		if (env.turnsTotal() <= before){
			fail( "5 waits on depth " + Dungeon.depth + " advanced the turn count from " + before
					+ " to " + env.turnsTotal() + ", so the hero is not taking turns where it arrived." );
			return;
		}

		System.out.println( "  the new floor is playable: 5 turns taken, mode " + env.describeMode()
				+ ", reason " + env.endReason() );
	}


	private static void checkTheEngineClockRestartsWithTheFloor(){
		SPDEnv env = freshRun();
		advanceClock( env );
		float before = Actor.now();
		if (before <= 1f ){
			fail( "the clock is " + before + " after walking on floor 1, so a reset to zero cannot be told"
					+ " from never having started. This case measured nothing." );
			return;
		}
		if (!descend( env )){
			fail( "no exit to descend from, so this case measured nothing." );
			return;
		}
		env.step( Action.WAIT, 0 );

		if (Actor.now() > 1f ){
			fail( "engine time is " + Actor.now() + " on arrival at depth " + Dungeon.depth + ", having"
					+ " been " + before + " on the floor above. Dungeon.newLevel() calls Actor.clear(),"
					+ " which zeroes the clock, and the game hands the player back input as soon as the new"
					+ " floor is built - so the clock on arrival is 0. Burning the new floor's mobs up to the"
					+ " hero's stale clock is what produced 129.0 here, and :desktop:viewcheck reported the"
					+ " trainer as diverged from a recording that the real game replays exactly." );
			return;
		}

		System.out.println( "  the engine clock restarts with the floor: " + before + " -> "
				+ Actor.now() + " on depth " + Dungeon.depth );
	}


	private static void checkTheHeroActsOnArrivalBeforeTheAgentDoes(){
		SPDEnv env = arrivalRun();
		if (env == null ){
			fail( "no seed on the list arrived on a floor with an enemy in the hero's field of view, so"
					+ " there was no action that could have been interrupted. This case measured nothing." );
			return;
		}

		int depth = Dungeon.depth;
		Action move = firstFreeStep( env );
		if (move == null ){
			fail( "the hero arrived on depth " + depth + " with no free neighbouring cell, so there is"
					+ " no action this case could have had interrupted. It measured nothing." );
			return;
		}

		int before = Dungeon.hero.pos;
		env.step( move, 0 );

		if (Dungeon.hero.pos == before ){
			fail( "the agent's first action on depth " + depth + " moved nobody: " + move
					+ " into an empty passable cell left the hero on " + before + " at engine time "
					+ Actor.now() + ". The game gives the hero one act on arrival before the player can"
					+ " act, and that act is what wakes the sleeping mobs; without it the mob's first look"
					+ " at the hero calls Hero.interrupt() and throws the action away. This is issues.md 10:"
					+ " a recording in which the hero descends and climbs back up diverges from itself,"
					+ " and the step that first disagrees is one that moves nobody." );
			return;
		}

		System.out.println( "  the hero acts on arrival: first " + move + " on depth " + depth
				+ " moved " + before + " -> " + Dungeon.hero.pos );
	}


	private static SPDEnv arrivalRun(){
		for (String seed : ARRIVAL_SEEDS){
			SPDEnv env = freshRun( seed );
			if (!descend( env )) continue;
			env.step( Action.WAIT, 0 );
			if (!env.running()) continue;
			if (!ascend( env )) continue;
			env.step( Action.WAIT, 0 );
			if (!env.running()) continue;
			if (visibleMobs() > 0 ) return env;
		}
		return null;
	}


	private static void checkAscendingAfterDescendingIsServiced(){
		SPDEnv env = freshRun();
		int start = Dungeon.depth;

		if (!descend( env )){
			fail( "no exit to descend from, so this case measured nothing." );
			return;
		}
		env.step( Action.WAIT, 0 );

		if (Dungeon.depth != start + 1 ){
			fail( "the descent did not happen (depth is " + Dungeon.depth + ", expected " + ( start + 1 )
					+ "), so the ascent this case is about was never attempted." );
			return;
		}

		if (!env.running()){
			fail( "the episode ended as " + env.endReason() + " on arrival at depth " + Dungeon.depth
					+ ", so the ascent was never attempted." );
			return;
		}

		if (!ascend( env )){
			fail( "depth " + Dungeon.depth + " has no REGULAR_ENTRANCE to climb back up from, so this"
					+ " case measured nothing. Every standard floor generates one." );
			return;
		}
		env.step( Action.WAIT, 0 );

		if (Dungeon.depth != start ){
			fail( "the hero climbed back up and is on depth " + Dungeon.depth + ", expected " + start
					+ ". InterlevelScene.ascend() reads the floor off disk whenever the depth is in"
					+ " Dungeon.generatedLevels, and descending first puts it there, so an ascent that does"
					+ " not clear the set tries to read a depth<n>.dat no headless run ever wrote. That is"
					+ " how a viewer run stopped on 'Cannot read save file' with nothing to show for it." );
			return;
		}

		if (Dungeon.hero == null || !Dungeon.hero.isAlive()){
			fail( "the ascent left no living hero on depth " + Dungeon.depth + "." );
			return;
		}

		if (!env.running()){
			fail( "climbing back up ended the episode as " + env.endReason() + ", at "
					+ Dungeon.hero.HP + "/" + Dungeon.hero.HT + " health." );
			return;
		}

		if (Actor.now() > 1f ){
			fail( "engine time is " + Actor.now() + " on arrival back at depth " + start + ", so the"
					+ " upstairs floor did not restart the clock." );
			return;
		}

		System.out.println( "  descend then ascend: depth " + start + " -> " + ( start + 1 ) + " -> "
				+ Dungeon.depth + ", hero alive on " + Dungeon.hero.pos + " at engine time " + Actor.now() );
	}


	private static void checkTheStairsAreNotShapedAcrossAFloorChange(){
		SPDEnv env = freshRun();
		env.config().stairPotentialWeight = 2f;

		StairPotential upstairs = new StairPotential();
		boolean[] reachableAbove = new boolean[ Dungeon.level.length() ];
		for (int c = 0; c < reachableAbove.length; c++){
			reachableAbove[ c ] = upstairs.distanceFrom( c ) != StairPotential.UNREACHABLE;
		}

		if (!descend( env )){
			fail( "no exit to descend from, so this case measured nothing." );
			return;
		}
		env.step( Action.WAIT, 0 );

		int shared = -1;
		for (int c = 0; c < Dungeon.level.length() && c < reachableAbove.length; c++){
			if (reachableAbove[ c ] && Dungeon.level.passable[ c ] && Actor.findChar( c ) == null){
				shared = c;
				break;
			}
		}
		if (shared < 0){
			fail( "no cell of depth 2 is open and also on depth 1's path to the stairs, so this case"
					+ " measured nothing." );
			return;
		}
		Dungeon.hero.pos = shared;
		env.step( Action.WAIT, 0 );

		double before = env.ledger().termTotal( RewardTerm.STAIR_POTENTIAL );
		if (!ascend( env )){
			fail( "depth 2 has no entrance to climb, so this case measured nothing." );
			return;
		}
		env.step( Action.WAIT, 0 );
		double paid = env.ledger().termTotal( RewardTerm.STAIR_POTENTIAL ) - before;

		if (Dungeon.depth != 1 || paid != 0){
			fail( "the step that climbed from cell " + shared + " of depth 2 to depth " + Dungeon.depth
					+ " paid " + paid + " of STAIR_POTENTIAL. The previous cell belongs to the floor below,"
					+ " and its distance on the new floor's field is a number about a different map." );
			return;
		}

		System.out.println( "  a floor change pays no stair shaping: climbing from cell " + shared
				+ ", open on both floors, paid 0" );
	}


	private static void checkClimbingBackDoesNotRefillTheFloorsTurns(){
		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 12;
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( SEED, HeroClass.WARRIOR );

		for (int i = 0; i < 8 && env.running(); i++) env.step( Action.WAIT, 0 );
		if (!descend( env )){
			fail( "no exit to descend from, so this case measured nothing." );
			return;
		}
		env.step( Action.WAIT, 0 );
		if (!ascend( env )){
			fail( "depth 2 has no entrance to climb, so this case measured nothing." );
			return;
		}
		env.step( Action.WAIT, 0 );
		int back = 0;
		while (env.running() && back < 12){
			env.step( Action.WAIT, 0 );
			back++;
		}

		if (env.running() || env.endReason() != RewardModel.TerminateReason.TURN_LIMIT || back > 4){
			fail( "with a 12-turn floor limit the hero spent 8 turns on depth 1, went down and came back,"
					+ " and then waited " + back + " more turns there (episode "
					+ ( env.running() ? "still running" : "ended " + env.endReason() ) + "). A floor's"
					+ " budget restarting on every return lets a stair dance outlive any per-floor limit." );
			return;
		}

		System.out.println( "  climbing back resumes the floor's turn budget: TURN_LIMIT after " + back
				+ " more turns on depth 1" );
	}


	private static void checkAVisitedFloorIsTheSameFloor(){
		SPDEnv env = freshRun();

		int marker = -1;
		for (int c = 0; c < Dungeon.level.length() && marker < 0; c++){
			if (Dungeon.level.passable[ c ] && !Dungeon.level.pit[ c ] && c != Dungeon.hero.pos
					&& Dungeon.level.heaps.get( c ) == null && Actor.findChar( c ) == null) marker = c;
		}
		Dungeon.level.drop( new com.shatteredpixel.shatteredpixeldungeon.items.Gold( 777 ), marker );
		String before = floorState();

		if (!descend( env )){
			fail( "no exit to descend from, so this case measured nothing." );
			return;
		}
		env.step( Action.WAIT, 0 );
		if (!ascend( env )){
			fail( "depth " + Dungeon.depth + " has no entrance to climb, so this case measured nothing." );
			return;
		}
		env.step( Action.WAIT, 0 );
		String after = floorState();

		if (Dungeon.depth != 1 || !before.equals( after )){
			fail( "depth 1 left as [" + before + "] came back as [" + after + "] on depth " + Dungeon.depth
					+ ". InterlevelScene saves the floor it leaves and loads it again on return; building"
					+ " it afresh refills its items and mobs and loses what the hero dropped there." );
			return;
		}

		System.out.println( "  a floor climbed back to is the floor that was left: " + after );
	}


	private static String floorState(){
		com.shatteredpixel.shatteredpixeldungeon.levels.Level level = Dungeon.level;
		StringBuilder heaps = new StringBuilder();
		for (int c = 0; c < level.length(); c++){
			com.shatteredpixel.shatteredpixeldungeon.items.Heap heap = level.heaps.get( c );
			if (heap != null) heaps.append( c ).append( ':' ).append( heap.peek().name() )
					.append( 'x' ).append( heap.peek().quantity() ).append( ',' );
		}
		return "map " + java.util.Arrays.hashCode( level.map ) + " mobs " + level.mobs.size()
				+ " heaps " + level.heaps.valueList().size() + " " + Integer.toHexString( heaps.toString().hashCode() );
	}


	private static Action firstFreeStep( SPDEnv env ){
		for (Action a : Action.values()){
			if (!a.directional()) continue;
			if (env.actionMask()[ a.index ] == 0f ) continue;

			int cell = env.mapper().offsetCell( a.dx, a.dy );
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			if (!Dungeon.level.insideMap( cell )) continue;
			if (!Dungeon.level.passable[ cell ]) continue;
			if (Actor.findChar( cell ) != null) continue;
			if (Dungeon.level.heaps.get( cell ) != null) continue;

			return a;
		}
		return null;
	}


	private static int visibleMobs(){
		Hero hero = Dungeon.hero;
		int seen = 0;
		for (Mob mob : Dungeon.level.mobs){
			if (mob.pos >= 0 && mob.pos < Dungeon.level.heroFOV.length
					&& Dungeon.level.heroFOV[ mob.pos ]) seen++;
		}
		return seen;
	}


	private static void advanceClock( SPDEnv env ){
		for (int i = 0; i < 12; i++){
			if (!env.running() || Actor.now() > 1f) return;
			Action move = null;
			float[] mask = env.actionMask();
			for (Action a : Action.values()){
				if (a.directional() && mask[ a.index ] != 0f){ move = a; break; }
			}
			env.step( move == null ? Action.WAIT : move, 0 );
		}
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
