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
import java.util.ArrayList;
import java.util.List;

/**
 * Fails if the environment drops an item somewhere the game would not have pressed the cell.
 *
 * <pre>gradle :superintelligence:presscheck</pre>
 *
 * <p><b>This is {@code issues.md} 17 and the gate for it.</b> {@code Level.occupyCell} presses a cell on
 * every step, which is how the game's traps, doors, wells and plants fire - but it only presses a cell
 * somebody <em>enters</em>. {@code Level.drop} has to press the landing cell itself, and every thrown
 * and every dropped item reaches it there ({@code Item.onThrow} and {@code Item.doDrop} both call it),
 * so it is the one chokepoint for the whole item tree.
 *
 * <p>The guard it sat behind separated gameplay drops from <em>scene</em> drops: {@code AlchemyScene}
 * crafts onto the cell the hero is standing on and {@code InterlevelScene.resurrect} drops the
 * LostBackpack on the cell he died on, and neither should press. A renderer-bypassed process has no
 * scene at all, so the test was false and every thrown item landed inert - measured on four seeds, an
 * armed {@code WornDartTrap} was still armed after a {@code Food} was dropped on it in all four.
 *
 * <p>Neither scene can exist headlessly, so every drop made here is a gameplay drop, and
 * {@code GameScene.headlessGameplay} is what says so. It is false unless something sets it, which only a
 * renderer-bypassed process does, so the guard's answer in a real game is unchanged.
 *
 * <p>The second case is the half of the guard that has to survive any fix. With {@code Dungeon.level}
 * nulled - the state {@code RegularLevel.createItems} places starting items in, because
 * {@code Dungeon.newLevel()} nulls it for the duration of {@code create()} - a dropped item must leave
 * the trap armed. That is what stops the obvious fix from quietly becoming a loosening.
 *
 * <p>Third case covers the other branch of the press that a trap does not reach, and the reason
 * {@code AlchemyScene} is a special case at all: dropping an item on high grass tramples it.
 */
public class PressCheck {

	private static final int CHECKS = 3;

	private static final String[] SEEDS = {
			"PRESSCHECK-A", "PRESSCHECK-B", "PRESSCHECK-C", "PRESSCHECK-D", "PRESSCHECK-E"
	};

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-presscheck" ));
		HeadlessServices.disableSaving( true );

		checkDroppingAnItemPressesTheCell();
		checkDroppingAnItemTramplesGrass();
		checkGenerationDoesNotPressCells();

		if (failures.isEmpty()){
			System.out.println( "[OK]     cell presses: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  cell presses: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	/**
	 * An item landing on a trap fires it.
	 *
	 * <p>Asserted on the trap's own state, and driven through {@code Level.drop} - the call the item tree
	 * actually makes - rather than by calling {@code pressCell} directly, which would test the press but
	 * not the wiring that skipped it.
	 *
	 * <p>{@code Trap.trigger()} disarms, so {@code active} going false is the game having fired it and is
	 * the one observable that says so. Visibility is not part of the assertion: a hidden trap still fires
	 * on a hard press, and dropping an item is one.
	 */
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

	/**
	 * An item landing on high grass tramples it.
	 *
	 * <p>The other half of what a press does, on the one cell type that is not a trap, and the reason
	 * {@code AlchemyScene} cannot be dropped from the guard: crafting puts an item on the cell the hero
	 * is standing on, and if that pressed, every craft would flatten the grass under him.
	 *
	 * <p>Asserted on {@code level.map}, because trampling is a terrain change rather than a state flag.
	 * {@code Level.set} is the static helper {@code HighGrass.trample} uses, so this reads the map the
	 * game's own write produced.
	 */
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

	/**
	 * Placing an item during level generation must not press its cell.
	 *
	 * <p>The reason the guard exists, and the case that makes widening it safe rather than a loosening.
	 * {@code RegularLevel.createItems} calls {@code Level.drop} for every starting item, and the map is
	 * not finished - traps are placed by the painter after the items are chosen - so a press during
	 * generation can fire a trap, enter a door or fill a well on a floor nobody has entered.
	 *
	 * <p>What prevents it is not the scene test but the {@code Dungeon.level != null} half: {@code
	 * Dungeon.newLevel} sets {@code Dungeon.level = null} before calling {@code level.create()} and
	 * assigns it on return, so generation runs with no current level and the press is skipped. That is
	 * the whole of what is carrying the load, so it is asserted directly - the same drop, the same trap,
	 * with {@code Dungeon.level} nulled, must leave the trap armed.
	 */
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

	/** An armed trap on the current level, or null. */
	private static Trap armedTrap(){
		for (Trap t : Dungeon.level.traps.values()){
			if (t.active && Dungeon.level.map[ t.pos ] == Terrain.TRAP) return t;
		}
		return null;
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
