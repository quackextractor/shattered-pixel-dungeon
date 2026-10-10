package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardLedger;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Fails if descending ends the episode, or is worth nothing.
 *
 * <pre>gradle :superintelligence:transitioncheck</pre>
 *
 * <p>Written for {@code issues.md} 9, which reported a recording in which the hero walked down the
 * stairs at full health and the recording ended {@code STALLED} on the spot.
 *
 * <p><b>What it was.</b> {@code Game.switchScene(...)} sets a flag on the game object and returns;
 * the flag is cleared and turned into "a scene wants to take over" by {@code Game.step()}, which a
 * real game reaches from {@code Game.render()}. A headless rollout never renders - that is what
 * {@code HeadlessGame.render()} being empty means - so the second signal was never raised.
 * {@code LevelPipeline.runToHeroReady} polls it to decide whether the game is handing off to a level
 * transition, and so never saw the hand-off. {@code Actor.headlessStep()} already honoured the
 * <i>first</i> signal, so the scheduler stopped dead while the pipeline kept waiting for an
 * acknowledgement that could not arrive; the drain ran out its budget and reported
 * {@code STEP_LIMIT}, which {@code SPDEnv} maps to {@code STALLED}.
 *
 * <p><b>Why nothing caught it.</b> Every other gate in the suite drives one of the five action modes
 * or plays a floor, and no policy in this project had ever reached a set of stairs - so the path was
 * unreachable rather than untested, and it was unreachable <i>because</i> of this fault. That is the
 * shape {@code TODO.md} 1.4 describes: {@code bestDepth} is 1 in every generation of every run and
 * nothing has ever been recorded above depth 1, and the cause was the environment killing the episode
 * on the one action that would have moved it.
 *
 * <p><b>Why this drives {@code Level.activateTransition} rather than walking.</b> That call is
 * {@code Hero.actTransition}'s own body for the case where the hero is standing on the stairs, and it
 * is what sets the request the fault was about. The half of the descent before it - {@code Hero.handle}
 * choosing {@code LvlTransition}, {@code ActionMapper} requiring adjacency - is the game's own action
 * resolution, and it is exercised on every descent by the rendered viewer, so it is not what this gate
 * is for. What is untested anywhere else is the half after it: whether the environment notices.
 */
public class TransitionCheck {

	private static final int CHECKS = 9;

	private static final String SEED = "TRANSITION";

	/**
	 * Seeds searched for a floor the hero arrives on with an enemy in sight. See
	 * {@link #arrivalRun()} for why the search happens at run time rather than being frozen here.
	 */
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

		if (failures.isEmpty()){
			System.out.println( "[OK]     floor transitions: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  floor transitions: " + failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	// --------------------------------------------------------------------------- the descent

	/**
	 * Walks the hero onto the exit and asks the level to do what {@code Hero.actTransition} does.
	 *
	 * <p>The hero's readiness is cleared as well, and that is load-bearing rather than tidiness.
	 * {@code Hero.act()} clears {@code ready} at the top of every turn, and {@code actTransition} then
	 * clears {@code curAction} without ever calling {@code ready()} - so a real descent leaves the hero
	 * holding no action and <i>not</i> waiting for input, which is the state the drain has to resolve
	 * by noticing the switch. Leaving the hero ready makes the very first scheduler step return
	 * {@code READY}, the pipeline never consults the switch at all, and the case passes with the fault
	 * reinstated: measured, and the reason this one took two attempts.
	 *
	 * @return false if the floor has no exit to use, which the caller must treat as a failure rather
	 *         than as a reason to pass: every standard floor has one, and a check that quietly skips
	 *         is a check that can pass without having measured anything.
	 */
	private static boolean descend( SPDEnv env ){
		LevelTransition exit = Dungeon.level.getTransition( LevelTransition.Type.REGULAR_EXIT );
		if (exit == null) return false;

		Hero hero = Dungeon.hero;
		hero.pos = exit.cell();
		boolean accepted = Dungeon.level.activateTransition( hero, exit );
		hero.ready = false;
		return accepted;
	}

	/**
	 * Walks the hero onto the entrance - the stairs a hero who is on floor N and wants floor N-1 uses -
	 * and asks the level to do the same thing.
	 *
	 * <p>The up stairs of a floor are its {@code REGULAR_ENTRANCE}, which is the naming the game uses
	 * throughout: an entrance is where a hero arrives, an exit is where a hero leaves, and the two are
	 * the same tile seen from either direction. Asking for an {@code UP} type would return the entrance
	 * anyway - {@code getTransition} falls back - so the point of naming it here is that a reader can see
	 * which tile this is.
	 *
	 * <p>{@code issues.md} 10. The whole of the gate was written for a descent, which left the other
	 * direction untested by construction: an ascend is the one transition that reads a floor back
	 * ({@code InterlevelScene.ascend()} calls {@code Dungeon.loadLevel} whenever the depth is in
	 * {@code generatedLevels}, and descending first puts it there), so it is the direction with a second
	 * engine behaviour attached to it, and it was the one nothing exercised.
	 */
	private static boolean ascend( SPDEnv env ){
		LevelTransition entrance = Dungeon.level.getTransition( LevelTransition.Type.REGULAR_ENTRANCE );
		if (entrance == null) return false;

		Hero hero = Dungeon.hero;
		hero.pos = entrance.cell();
		boolean accepted = Dungeon.level.activateTransition( hero, entrance );
		hero.ready = false;
		return accepted;
	}

	/** A fresh run, with a few turns taken on floor 1 so the first floor has a scored history. */
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

	// --------------------------------------------------------------------------- checks

	/**
	 * A pending transition has to be noticed and serviced.
	 *
	 * <p>The assertion is on the world rather than on a flag, because the flag is the thing that was
	 * broken: {@code switchRequested()} is what the pipeline reads, and a check that asserted on it
	 * would pass while the pipeline spun. Depth, and the hero standing on the new floor, are what the
	 * fault actually cost.
	 */
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

	/**
	 * The symptom {@code issues.md} 9 reported, asserted directly.
	 *
	 * <p>A recording is a sequence of steps and a declared reason, and this pair is what a descended
	 * run used to produce: a hero at full health on the stairs, and {@code termination=STALLED}.
	 * {@code duelist-mid} is the one that was reported - its last step is {@code INTERACT} onto the
	 * exit at cell 338 and its header says {@code STALLED}.
	 */
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

	/**
	 * The switch request has to be cleared, or the second descent is the only one that works.
	 *
	 * <p>A latch rather than a request would be a different fault with the same symptom on the second
	 * descent, and it is one line to get wrong: the pipeline acknowledges the switch by calling
	 * {@code clearSwitchRequest()}, which resets both halves. This drives two descents in one episode
	 * so the second is the one that fails if the acknowledgement is missing.
	 */
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

	/**
	 * Descending has to be worth {@code depthReward}.
	 *
	 * <p>{@code DEPTH_ADVANCE} is the primary goal term - the one {@code research.md} says survives
	 * into the sparse phase - and it could not fire. {@code SPDEnv.onFloorTransition} called
	 * {@code reward.resetSnapshot()} <i>after</i> the depth had already changed, so the next
	 * {@code reward.step} re-took its snapshot at the new depth, compared it against itself, and the
	 * {@code depth != prevDepth} branch was never taken.
	 *
	 * <p>It was dead because nothing ever descended, which is the fault above; the two are one bug
	 * seen from two ends, and fixing only the first leaves an agent that descends for free.
	 */
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

	/**
	 * The floor that was left is the one marked cleared.
	 *
	 * <p>{@code FloorTotals.cleared} is documented as "this floor was left by descending rather than by
	 * dying or timing out", and {@code diag.RunReport} renders it. {@code DEPTH_ADVANCE} is scored on
	 * the new floor's first turn - that is the only turn on which the depth change is observable - so
	 * passing {@code clearedByAdvancing} to it marked the floor the hero had just arrived on, and
	 * every floor in a descending run would read as cleared.
	 *
	 * <p>Worth its own case because the two assignments are 30 lines apart in different classes and
	 * nothing else in the suite looks at the flag.
	 */
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

	/**
	 * A descent has to leave a floor the environment can keep playing on.
	 *
	 * <p>"The hero is on depth 2" is not the same as "the episode continues", and the difference is
	 * where the next fault would live: a transition that left the hero mid-action, resting, or short
	 * of a sprite would terminate the following turn rather than this one, which is a different
	 * symptom pointing at the same place.
	 */
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

	/**
	 * A floor change restarts the engine clock, and the environment must say so at the same moment.
	 *
	 * <p>{@code Dungeon.newLevel()} calls {@code Actor.clear()}, which zeroes {@code Actor.now} - in the
	 * game and here alike. What differs is when the hero is handed back. The game gives the player input
	 * the moment the new floor is built, so the clock a player sees on arrival is 0. This environment used
	 * to drain instead, which meant working the new floor's sleeping mobs up to the hero's stale clock
	 * before the hero could be picked again.
	 *
	 * <p>Measured, and it is the whole of a real divergence: on {@code duelist-mid} the trainer recorded
	 * engine time <b>129.0</b> on the descent step, and {@code gradle :desktop:viewcheck} - replaying
	 * that recording in the real rendered game - reported <b>0.0</b> and halted
	 * {@code DIVERGED at step 158 - engine time is 0.0, recording says 129.0}. Every other recording in
	 * the corpus played clean, so nothing else in the suite could see it.
	 *
	 * <p>Asserted on the clock rather than on the wall, because the clock is what a recording carries and
	 * what the viewer compares.
	 */
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

	/**
	 * The hero gets his turn on the new floor before the agent does.
	 *
	 * <p><b>This is {@code issues.md} 10, and it is invisible from the recording.</b> Every other case in
	 * this gate looks at the floor the hero lands on and the clock he lands with. Nothing looks at
	 * whether he <i>acted</i> on arrival, and a recording cannot: a step whose action was thrown away
	 * still records a step, and the recording of a step the hero did not perform is well-formed.
	 *
	 * <p><b>What the game does.</b> The hero arrives on the new floor and is the earliest actor on it -
	 * {@code Dungeon.newLevel} zeroes the clock, so everything on the floor is at time 0 - so the game's
	 * own scheduler picks him for exactly one act before the player can act at all. That act is not a
	 * formality: {@code Hero.act()} runs {@code checkVisibleMobs()}, which is where the new floor's
	 * sleeping mobs notice a hero standing among them, and a mob seeing him for the first time calls
	 * {@code Hero.interrupt()}, which discards whatever action is pending.
	 *
	 * <p><b>What this environment used to do.</b> It returned from the transition without giving the hero
	 * that act, so the agent's action was injected first and the hero's very first act on the new floor
	 * interrupted it. The agent's first move on every new floor was spent being noticed by a rat. On
	 * {@code duelist-mid} the recorded {@code MOVE_SE} at step 160 left the hero on cell 282 with the
	 * clock still at 0.0 - a turn that moved nobody - and the rendered viewer, replaying the same
	 * recording in the real game, moved to 317 and reported the trainer as diverged.
	 *
	 * <p><b>Why this gate did not have it.</b> Nothing in the suite injected an action on the frame a
	 * transition completed; every case here spent a {@code WAIT}, which a noticing mob cannot interrupt
	 * into anything different, and then looked at the floor. So the case has to end with a real action
	 * and has to assert the hero moved - asserting only that the hero is waiting for input would be
	 * asserting the mechanism, and the mechanism is the part that is allowed to change.
	 *
	 * <p>Mutation-tested by deleting the landing act: the first action is then eaten and the case fails
	 * with "the agent's first action on depth 1 moved nobody".
	 */
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

	/**
	 * A run that arrives back on floor 1 with an enemy in the hero's field of view.
	 *
	 * <p>Descending is not enough to find one. A hero arrives on a new floor at its entrance, and
	 * level generation keeps the entrance clear, so the arrival FOV of every one of nine seeds was empty
	 * of enemies - and a case built on an empty FOV cannot be interrupted, so it passes whether the bug
	 * is present or not. {@code issues.md} 10 diverged on the *ascent*, where the hero comes back to the
	 * stairs of a floor he has already played, and those sit in an ordinary room. So the fixture is the
	 * whole journey: down, then up, then look.
	 *
	 * <p>The search happens at run time rather than being frozen into a seed, because a level-generation
	 * change would silently turn a hard-coded fixture into a no-op and nothing would report it.
	 *
	 * @return an environment parked on arrival, or null if no seed qualified
	 */
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

	/**
	 * Descending and then climbing back up is one journey, and it is the one {@code issues.md} 10 filed.
	 *
	 * <p>Three things can go wrong on the way back up and each has its own shape. {@code InterlevelScene
	 * .ascend()} asks {@code Dungeon.levelHasBeenGenerated(depth, branch)} and reads the floor off disk
	 * when the answer is yes - and descending first puts that depth in the set, so an ascent follows a
	 * descent into a read of a {@code depth<n>.dat} a viewer run never wrote, which stopped on screen with
	 * "Cannot read save file". {@code LevelPipeline} clears the set before generating, and the viewer now
	 * does the same; this case is what says so.
	 *
	 * <p>Second, the hero has to arrive at all: an ascent that ends the episode is {@code issues.md} 9's
	 * symptom on the other side. Third, the engine clock restarts again, because the upstairs floor is a
	 * fresh build.
	 *
	 * <p>The direction matters for a reason worth stating, because it is not symmetric in the game: the
	 * trainer always regenerates and a real playthrough loads, so an ascent is the only transition with a
	 * second engine behaviour hanging off it - and it was the one no gate drove.
	 */
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

	/**
	 * The first action the mask offers that walks the hero into a cell nothing is standing in.
	 *
	 * <p>A cell with a mob in it is excluded because the move would become an attack and the case would
	 * be asserting about combat; one with a heap on it because the move would become a pickup. Either
	 * would still move the hero, but a case about being interrupted should not also be a case about
	 * resolving something else first.
	 */
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

	/**
	 * Enemies inside the hero's field of view, which is the set
	 * {@code Hero.checkVisibleMobs()} walks and the set whose first sighting calls
	 * {@code Hero.interrupt()}.
	 *
	 * <p>{@code heroFOV} rather than a mob's own field of view, because that is the array the hero's own
	 * code reads: a mob can see the hero without the hero being able to see the mob, and it is the
	 * latter that cancels the action.
	 */
	private static int visibleMobs(){
		Hero hero = Dungeon.hero;
		int seen = 0;
		for (Mob mob : Dungeon.level.mobs){
			if (mob.pos >= 0 && mob.pos < Dungeon.level.heroFOV.length
					&& Dungeon.level.heroFOV[ mob.pos ]) seen++;
		}
		return seen;
	}

	/**
	 * Walks until the engine clock has actually moved.
	 *
	 * <p>Needed because {@code WAIT} and {@code REST} cost the hero no time - {@code Hero.next()} without
	 * a {@code spend} - so a floor spent entirely on them leaves {@code Actor.now()} at 0 and a case about
	 * the clock resetting has nothing to observe.
	 */
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
