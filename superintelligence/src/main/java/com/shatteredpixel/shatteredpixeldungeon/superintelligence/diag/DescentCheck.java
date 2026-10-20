package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardLedger;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.descend;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.TransitionCheck.SEED;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.TransitionCheck.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.TransitionCheck.freshRun;

final class DescentCheck {

	private DescentCheck(){}


	static void checkDescentIsServiced(){
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


	static void checkDescentDoesNotEndTheEpisode(){
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


	static void checkDescentIsRepeatable(){
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


	static void checkDepthIsPaidFor(){
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


	static void checkTheFloorLeftBehindIsTheOneMarkedCleared(){
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


	static void checkTheNewFloorIsPlayable(){
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


	static void checkTheEngineClockRestartsWithTheFloor(){
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
}
