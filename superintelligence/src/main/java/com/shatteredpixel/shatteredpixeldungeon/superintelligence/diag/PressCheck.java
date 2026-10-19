package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;

import java.io.File;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GateReport.fail;

public class PressCheck {

	private static final int CHECKS = 3;

	private static final String[] SEEDS = {
			"PRESSCHECK-A", "PRESSCHECK-B", "PRESSCHECK-C", "PRESSCHECK-D", "PRESSCHECK-E"
	};

	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "presscheck" ));
		HeadlessServices.disableSaving( true );

		checkDroppingAnItemPressesTheCell();
		checkDroppingAnItemTramplesGrass();
		checkGenerationDoesNotPressCells();

		System.exit( GateReport.report( "cell presses", CHECKS ));
	}


	private static void checkDroppingAnItemPressesTheCell(){
		SPDEnv env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );

		for (String seed : SEEDS){
			env.reset( seed, HeroClass.WARRIOR );

			Trap trap = armedTrap();
			if (trap == null ) continue;

			Dungeon.level.drop( new Food(), trap.pos );

			if (trap.active){
				fail( seed + ": a Food was dropped on an armed "
						+ trap.getClass().getSimpleName() + " at cell " + trap.pos + " and the trap is"
						+ " still armed. Level.drop presses the landing cell so a thrown item can trigger"
						+ " what it lands on, but it only does so when a gameplay scene is up, which a"
						+ " renderer-bypassed process does not have. Measured before the fix on four seeds:"
						+ " armed after the drop in all four. Throwing a stone at a trap does nothing"
						+ " here." );
				return;
			}

			System.out.println( "  an item dropped on a trap fires it: " + seed + ", "
					+ trap.getClass().getSimpleName() + " at " + trap.pos + " disarmed" );
			return;
		}

		fail( "no seed generated an armed trap on floor 1, so this case measured nothing." );
	}


	private static void checkDroppingAnItemTramplesGrass(){
		SPDEnv env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );

		for (String seed : SEEDS){
			env.reset( seed, HeroClass.WARRIOR );

			int grass = -1;
			for (int cell = 0; cell < Dungeon.level.map.length; cell++ ){
				if (Dungeon.level.map[cell] == Terrain.HIGH_GRASS && !Dungeon.level.pit[cell]
						&& !Dungeon.level.heaps.containsKey(cell)){
					grass = cell;
					break;
				}
			}
			if (grass < 0 ) continue;

			Dungeon.level.drop( new Food(), grass );

			if (Dungeon.level.map[grass] == Terrain.HIGH_GRASS){
				fail( seed + ": a Food was dropped on high grass at cell " + grass + " and the grass is"
						+ " still standing. Level.drop presses the landing cell, which tramples grass - the"
						+ " same press that fires the trap in the first case, on the branch a trap does not"
						+ " reach." );
				return;
			}

			System.out.println( "  an item dropped on high grass tramples it: " + seed + ", cell "
					+ grass + " now " + Dungeon.level.map[grass] );
			return;
		}

		fail( "no seed generated high grass on floor 1, so this case measured nothing." );
	}


	private static void checkGenerationDoesNotPressCells(){
		SPDEnv env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );

		for (String seed : SEEDS){
			env.reset( seed, HeroClass.WARRIOR );

			Trap trap = armedTrap();
			if (trap == null ) continue;

			com.shatteredpixel.shatteredpixeldungeon.levels.Level playing = Dungeon.level;
			Dungeon.level = null;
			try {
				playing.drop( new Food(), trap.pos );
			} finally {
				Dungeon.level = playing;
			}

			if (!trap.active){

				trap.active = true;
				fail( seed + ": with Dungeon.level null - the state RegularLevel.createItems runs in -"
						+ " dropping an item still pressed cell " + trap.pos + " and fired the "
						+ trap.getClass().getSimpleName() + ". Level generation calls Level.drop for every"
						+ " starting item, so a press there fires traps, opens doors and fills wells on a"
						+ " floor nobody has entered. Only the Dungeon.level != null half of the guard"
						+ " stands between generation and that." );
				return;
			}

			System.out.println( "  generating a floor presses nothing: with Dungeon.level null, an item"
					+ " dropped on cell " + trap.pos + " leaves the trap armed" );
			return;
		}

		fail( "no seed generated an armed trap on floor 1, so this case measured nothing." );
	}


	private static Trap armedTrap(){
		for (Trap t : Dungeon.level.traps.values()){
			if (t.active && Dungeon.level.map[ t.pos ] == Terrain.TRAP) return t;
		}
		return null;
	}
}
