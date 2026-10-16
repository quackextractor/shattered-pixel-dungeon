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

	private static final int MAX_CONSECUTIVE_FREE = 2;

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

	private static Action rotate( int i ){
		Action[] all = {
			Action.MOVE_E, Action.MOVE_W, Action.MOVE_S, Action.MOVE_N,
			Action.MOVE_NE, Action.MOVE_SW, Action.MOVE_NW, Action.MOVE_SE,
			Action.REST, Action.SEARCH, Action.INTERACT, Action.WAIT
		};
		return all[ i % all.length ];
	}

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