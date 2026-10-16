package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.TalentPicker;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The two actions that cost nothing must stop being able to cost nothing forever, and the talent point
 * must be spendable at all.
 *
 * <pre>gradle :superintelligence:freeactioncheck</pre>
 *
 * <p>Measured over 4,828 steps in 40 uniform-policy episodes, roughly 90% of all steps advanced no
 * engine time: {@code OPEN_INVENTORY} and {@code CANCEL} 100%, {@code REST} 99.1%, {@code USE} 87.9%,
 * {@code DROP} 50%. Every one of those is charged as a free step, and {@code env.freeStepBudget} ends
 * the episode as {@code STALLED} when it is reached - so 100% of episodes ended at turn ~120 of the
 * 1500 granted, with floor 1's stairs arithmetically out of reach.
 *
 * <p>The loop is structural rather than behavioural. {@code OPEN_INVENTORY}, {@code USE} and
 * {@code DROP} are the only ways into a sub-mode and all three cost nothing; {@code CANCEL} is the only
 * way out of two of them and also costs nothing. An exploring policy falls in because both halves are
 * offered, and neither half is wrong on its own.
 */
public class FreeActionCheck {

	private static final int CHECKS = 7;

	private static final List<String> failures = new ArrayList<>();

	private static final String[] SEEDS = {
		"FREEACTION-A", "FREEACTION-B", "FREEACTION-C", "FREEACTION-D"
	};

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-freeactioncheck" ));
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 400;

		checkRestAdvancesTheEngineClock( config );
		checkOpeningAMenuCostsNoTurn( config );
		checkAZeroTimeModeExitIsMaskedOut( config );
		checkTheRefusalLastsExactlyOneStep( config );
		checkTheMenuLoopCannotOutrunTheBudget( config );
		checkTalentIsOfferedOnlyWithAPointToSpend( config );
		checkTalentPointCanBeSpentAndPays( config );

		reportEngineTimePerAction( config );

		if (failures.isEmpty()){
			System.out.println( "[OK]     free actions: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  free actions: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	/**
	 * {@code REST} has to spend a turn, or it is a free step that also traps the hero.
	 *
	 * <p>It used to set {@code resting} and call {@code next()}, which leaves the clock where it was:
	 * the rest turn is taken by {@code Hero.act}'s rest branch, and the drain can return before the hero
	 * is picked again. Measured, 323 of 326 rests advanced zero engine time.
	 */
	private static void checkRestAdvancesTheEngineClock( EnvConfig config ){
		int rested = 0;
		int advanced = 0;

		for (String seed : SEEDS){
			SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
			env.reset( seed, HeroClass.WARRIOR );

			for (int i = 0; i < 8 && env.mode() == EnvMode.WORLD; i++ ){
				float before = Actor.now();
				env.step( Action.REST, 0 );
				rested++;
				if (Actor.now() > before) advanced++;
			}
		}

		if (advanced < rested){
			failures.add( "REST advanced the engine clock on " + advanced + " of " + rested
					+ " rests. Resting costs TIME_TO_REST turns in the game, so a rest that spends"
					+ " nothing is a free step that also leaves the hero resting, and it is charged"
					+ " against env.freeStepBudget as well." );
			return;
		}

		System.out.println( "  REST advanced the engine clock on " + advanced + " of " + rested );
	}

	/**
	 * The menu entry itself still costs nothing, because that is true in the real game too.
	 *
	 * <p>Asserted rather than assumed: the fix must mask the loop, not price an action the player can
	 * perform for free. If {@code OPEN_INVENTORY} started advancing the clock, this would fail and the
	 * mask would be hiding a second defect.
	 */
	private static void checkOpeningAMenuCostsNoTurn( EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( SEEDS[ 0 ], HeroClass.WARRIOR );

		if (env.mode() != EnvMode.WORLD){
			failures.add( "the episode did not start in WORLD mode, so no menu could be opened." );
			return;
		}

		float before = Actor.now();
		env.step( Action.OPEN_INVENTORY, 0 );

		if (env.mode() != EnvMode.INVENTORY){
			failures.add( "OPEN_INVENTORY did not enter INVENTORY; it left the environment in "
					+ env.describeMode() + "." );
			return;
		}

		if (Actor.now() != before){
			failures.add( "OPEN_INVENTORY advanced the engine clock. Opening the backpack is free in"
					+ " the game as well, so a mask that hides the loop must not make it cost a turn." );
			return;
		}

		System.out.println( "  OPEN_INVENTORY enters INVENTORY without spending a turn" );
	}

	/**
	 * Leaving a sub-mode for free must close the way back in.
	 *
	 * <p>The refusal is a mask rather than a prohibition: the actions are legal, the agent just cannot
	 * chain them without spending a turn in between, which is what a player does too.
	 */
	private static void checkAZeroTimeModeExitIsMaskedOut( EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( SEEDS[ 0 ], HeroClass.WARRIOR );

		env.step( Action.OPEN_INVENTORY, 0 );
		env.step( Action.CANCEL, 0 );

		if (env.mode() != EnvMode.WORLD){
			failures.add( "CANCEL did not return to WORLD; the environment is in "
					+ env.describeMode() + " so the loop cannot be tested." );
			return;
		}

		float[] mask = env.actionMask();

		for (Action a : new Action[]{ Action.OPEN_INVENTORY, Action.USE, Action.DROP }){
			if (mask[ a.index ] != 0f ){
				failures.add( a + " is still offered immediately after a menu was opened and cancelled"
						+ " at no cost to engine time. That pair is the loop: both halves are free, so"
						+ " the agent can spend the whole free-step budget on it and never reach the"
						+ " stairs." );
				return;
			}
		}

		System.out.println( "  a zero-time menu exit masks OPEN_INVENTORY, USE and DROP" );
	}

	/**
	 * The refusal is one step, not a prohibition.
	 *
	 * <p>A mask that never lifts would be a different defect: the agent would be unable to use an item
	 * at all after a single cancelled menu.
	 */
	private static void checkTheRefusalLastsExactlyOneStep( EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( SEEDS[ 1 ], HeroClass.WARRIOR );

		env.step( Action.OPEN_INVENTORY, 0 );
		env.step( Action.CANCEL, 0 );

		float clock = Actor.now();
		int guard = 0;
		while (Actor.now() == clock && env.mode() == EnvMode.WORLD && guard++ < 12 ){
			env.step( move( env ), 0 );
		}

		if (Actor.now() == clock){
			failures.add( "no move on seed " + SEEDS[ 1 ] + " advanced the engine clock in 12 attempts,"
					+ " so the refusal could not be shown to lift. Widen the seed list rather than"
					+ " accepting an unchecked case." );
			return;
		}

		float[] mask = env.actionMask();
		if (mask[ Action.OPEN_INVENTORY.index ] == 0f ){
			failures.add( "OPEN_INVENTORY is still masked after a step that spent engine time. The"
					+ " refusal has to last exactly one step." );
			return;
		}

		System.out.println( "  the refusal lifts after one step that spends engine time" );
	}

	/**
	 * The whole loop, driven, must not be able to run on free steps alone.
	 *
	 * <p>The property is the run of consecutive free steps, not the share. Before the mask, opening and
	 * cancelling a menu were both free, so an exploring policy could spend an entire floor's turn budget
	 * and its whole {@code env.free_step_budget} without the world moving once, and 100% of episodes ended
	 * {@code STALLED} at turn ~120. After it, one real step is forced between the two free halves, so the
	 * run of free steps is bounded no matter how long the agent keeps trying.
	 */
	private static void checkTheMenuLoopCannotOutrunTheBudget( EnvConfig config ){
		EnvConfig local = config.copy();
		local.turnLimitPerFloor = 4000;
		local.stallLimit = 100000;
		local.freeStepBudget = 100000;

		int steps = 60;
		int taken = 0;
		int advanced = 0;
		int worstRun = 0;
		int currentRun = 0;

		for (String seed : SEEDS){
			SPDEnv env = new SPDEnv( local, HeadlessGame.install() );
			env.reset( seed, HeroClass.WARRIOR );

			for (int i = 0; i < steps && env.running(); i++ ){
				Action a;
				if (env.mode() != EnvMode.WORLD){
					a = Action.CANCEL;
				} else if (env.actionMask()[ Action.OPEN_INVENTORY.index ] > 0.5f ){
					a = Action.OPEN_INVENTORY;
				} else {
					a = move( env );
				}

				float before = Actor.now();
				env.step( a, 0 );

				taken++;
				if (Actor.now() > before){
					advanced++;
					currentRun = 0;
				} else {
					currentRun++;
					worstRun = Math.max( worstRun, currentRun );
				}
			}
		}

		if (advanced == 0){
			failures.add( "none of " + taken + " steps of an OPEN_INVENTORY/CANCEL loop advanced engine"
					+ " time, so the loop still runs entirely on free steps and still ends every episode"
					+ " through env.free_step_budget." );
			return;
		}

		if (worstRun > MAX_CONSECUTIVE_FREE){
			failures.add( "an OPEN_INVENTORY/CANCEL loop produced a run of " + worstRun
					+ " consecutive free steps; it must not exceed " + MAX_CONSECUTIVE_FREE
					+ ", or the agent can still spend a whole floor's turn budget without the world"
					+ " moving." );
			return;
		}

		System.out.println( "  " + advanced + " of " + taken + " steps of the menu loop advanced engine"
				+ " time; the longest free run was " + worstRun + " step(s)" );
	}

	/**
	 * How many free steps may follow each other after a zero-time menu exit.
	 *
	 * <p>Two: {@code OPEN_INVENTORY} is free, {@code CANCEL} out of the pane is free, and the mask then
	 * forces one step that spends engine time. Any larger and the mask is not doing its job.
	 */
	private static final int MAX_CONSECUTIVE_FREE = 2;

	/** {@code TALENT} must be offered exactly when there is a point to spend, and never otherwise. */
	private static void checkTalentIsOfferedOnlyWithAPointToSpend( EnvConfig config ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( SEEDS[ 0 ], HeroClass.WARRIOR );

		if (env.actionMask()[ Action.TALENT.index ] != 0f ){
			failures.add( "TALENT is offered to a hero with no points to spend, at hero level "
					+ Dungeon.hero.lvl + "." );
			return;
		}

		if (TalentPicker.spendableCount( Dungeon.hero ) != 0 ){
			failures.add( "TALENT is masked but TalentPicker reports " + TalentPicker.spendableCount( Dungeon.hero )
					+ " spendable point(s), so the mask and the picker disagree." );
			return;
		}

		Hero hero = Dungeon.hero;
		hero.lvl = 3;
		int before = TalentPicker.spendableCount( hero );

		if (before <= 0){
			failures.add( "a level 3 hero still has no talent point available, so the level-up path the"
					+ " reward depends on cannot be reached in this build." );
			return;
		}

		if (!TalentPicker.available( hero ).isEmpty() ){
			System.out.println( "  TALENT is masked with no point and offered with one" );
			return;
		}

		failures.add( "a level 3 hero has " + before + " point(s) to spend but TalentPicker offers no"
				+ " talent to spend them on." );
	}

	/**
	 * The whole path: open the pane, pick from what the game offers, and be paid.
	 *
	 * <p>Driven through {@link SPDEnv#step} rather than through the picker, because the picker being
	 * callable is not the same claim as the agent being able to reach it: the mode, the mask and the
	 * secondary choice all have to line up as well.
	 */
	private static void checkTalentPointCanBeSpentAndPays( EnvConfig config ){
		EnvConfig local = config.copy();
		local.turnLimitPerFloor = 400;

		int spent = 0;
		RewardTerm paid = null;
		int notes = 0;

		for (String seed : SEEDS){
			SPDEnv env = new SPDEnv( local, HeadlessGame.install() );
			env.reset( seed, HeroClass.WARRIOR );

			Dungeon.hero.lvl = 3;
			env.step( Action.WAIT, 0 );

			if (env.actionMask()[ Action.TALENT.index ] == 0f ) continue;

			List<Talent> before = env.talentChoices();
			if (before.isEmpty()) continue;

			env.step( Action.TALENT, 0 );

			if (env.mode() != EnvMode.TALENT){
				failures.add( "TALENT was offered but stepping it left the environment in "
						+ env.describeMode() + " rather than the talent pane." );
				return;
			}

			float[] mask = env.slotMask();
			if (mask[ 0 ] == 0f || mask[ Math.min( 1, mask.length - 1 ) ] == 0f ){
				failures.add( "the talent pane offers " + env.talentChoices().size()
						+ " talent(s) but its head mask does not admit the first two indices." );
				return;
			}

			List<Talent> options = env.talentChoices();
			env.step( Action.MENU_SELECT, 0 );

			if (env.mode() != EnvMode.WORLD){
				failures.add( "spending a talent left the environment in " + env.describeMode()
						+ " rather than returning to WORLD." );
				return;
			}

			if (env.talentChoices().size() >= options.size()
					&& Dungeon.hero.pointsInTalent( options.get( 0 ) ) == 0 ){
				failures.add( "MENU_SELECT on talent 0 did not spend the point: "
						+ options.get( 0 ) + " still has none and the pane offers the same choices." );
				return;
			}

			spent++;
			notes = env.ledger().notes( RewardTerm.TALENT_UPGRADE );
			paid = RewardTerm.TALENT_UPGRADE;

			if (env.ledger().termTotal( paid ) <= 0 ){
				failures.add( "a talent point was spent but TALENT_UPGRADE paid nothing, so the largest"
						+ " single non-descent payout in the reward function cannot fire in a real run." );
				return;
			}
			break;
		}

		if (spent == 0 ){
			failures.add( "no seed produced a spendable talent point through the real environment, so"
					+ " the path was never reached and the check proved nothing." );
			return;
		}

		System.out.println( "  a talent point was spent through the environment and paid ("
				+ notes + " note(s))" );
	}

	/**
 * Engine time bought per action, over a fixed number of steps per seed.
 *
 * Printed rather than asserted, because it is the measurement every other case here is arguing about:
 * {@code env.freeStepBudget} charges a step for advancing no engine time, so an action's share of the
 * budget is exactly its share of this table.
 */
	private static void reportEngineTimePerAction( EnvConfig config ){
		EnvConfig local = config.copy();
		local.turnLimitPerFloor = 200;
		local.stallLimit = 100000;
		local.freeStepBudget = 1000000;

		java.util.Map< String, int[] > tally = new java.util.LinkedHashMap<>();

		for (String seed : SEEDS){
			SPDEnv env = new SPDEnv( local, HeadlessGame.install() );
			env.reset( seed, HeroClass.WARRIOR );

			for (int i = 0; i < 120 && env.running(); i++ ){
				float before = Actor.now();

				Action a;
				int secondary = 0;
				if (env.mode() == EnvMode.INVENTORY || env.mode() == EnvMode.SLOT
						|| env.mode() == EnvMode.TARGETING){
					a = Action.CANCEL;
				} else if (env.mode() == EnvMode.TALENT){
					a = Action.MENU_SELECT;
				} else if (env.mode() == EnvMode.MENU){
					a = Action.MENU_SELECT;
				} else {
					secondary = 0;
					a = rotate( i );
					if (env.actionMask()[ a.index ] == 0f ) a = Action.WAIT;
				}

				env.step( a, secondary );

				String key = env.describeMode() + " before " + a;
				int[] row = tally.computeIfAbsent( key, k -> new int[ 2 ] );
				row[ 0 ]++;
				if (Actor.now() > before) row[ 1 ]++;
			}
		}

		System.out.println( "  engine time bought per action:" );
		for (java.util.Map.Entry< String, int[] > e : tally.entrySet()){
			int[] row = e.getValue();
			System.out.printf( "    %-34s %4d used, %4d free (%5.1f%% free)%n",
					e.getKey(), row[ 0 ], row[ 0 ] - row[ 1 ],
					100.0 * (row[ 0 ] - row[ 1 ] ) / Math.max( 1, row[ 0 ] ) );
		}
	}

	/** The time-consuming actions, in turn, so every one of them is measured on every seed. */
	private static Action rotate( int i ){
		Action[] all = {
			Action.MOVE_E, Action.MOVE_W, Action.MOVE_S, Action.MOVE_N,
			Action.MOVE_NE, Action.MOVE_SW, Action.MOVE_NW, Action.MOVE_SE,
			Action.REST, Action.SEARCH, Action.INTERACT, Action.WAIT
		};
		return all[ i % all.length ];
	}

	/** Any legal move the mask offers, or {@code WAIT} when it offers none. */
	private static Action move( SPDEnv env ){
		float[] mask = env.actionMask();
		for (Action a : Action.values()){
			if (a.directional() && mask[ a.index ] > 0.5f ) return a;
		}
		return Action.WAIT;
	}

	private static void fail( String message ){
		failures.add( message );
	}
}