package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Fails when the environment cannot reach one of its own modes.
 *
 * Six real bugs shipped through this module while every recording was WORLD-only:
 * {@code EnvMode.INVENTORY} was never assigned anywhere, {@code OPEN_INVENTORY} was missing from
 * the action mask, throwing stones were equipped instead of thrown, a "needs an aim" answer was
 * fabricated from an "equipped OK" boolean, a refused equip never released the hero so the episode
 * stalled, and the pending use item was cleared on the very step that needed it. Every one of them
 * was invisible to a suite that only ever walked around, because none of them touch movement.
 *
 * A replay fixture alone does not prevent that. Nothing failed when a mode quietly stopped being
 * reachable - the fixture would just quietly stop covering it. This asserts reachability directly, so
 * a regression is a red build rather than a gap discovered much later.
 *
 * Written as an explicit script rather than a policy loop. The point is to visit each mode once and
 * say precisely which hop failed; a policy that wanders would report "INVENTORY missing" when the real
 * fault was three steps earlier. It also covers the two things a mode merely appearing does not
 * prove: a real throw through {@code TARGETING}, and a drop, which is legal for an agent to choose
 * and had never been executed.
 *
 * Run with {@code gradle :superintelligence:modecheck}.
 */
public class ModeCoverageCheck {

	/** Chosen because the warrior's starting kit includes a throwing stone, so an aim is reachable. */
	private static final String SEED = "MODE-CHECK";

	private SPDEnv env;
	private final Set< EnvMode > seen = EnumSet.noneOf( EnvMode.class );
	private final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-modecheck" ) );
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 2000;

		ModeCoverageCheck check = new ModeCoverageCheck();
		check.env = new SPDEnv( config, HeadlessGame.install() );
		check.run();

		check.report();
	}

	/**
	 * Opening the inventory must not also act on the world.
	 *
	 * <p>{@code OPEN_INVENTORY} and {@code CANCEL} were not listed in {@code ActionMapper.apply}'s
	 * switch, so both fell through to {@code pickInteractCell()} and {@code handleCell()} - the path
	 * {@code INTERACT} takes. Choosing to look in your backpack therefore also moved the hero, attacked
	 * an adjacent mob, picked up a heap, opened a locked door or took a transition.
	 *
	 * <p><b>This went into training.</b> Every episode that opened its inventory did something else as
	 * well, and the recorded runs show it plainly: on one {@code OPEN_INVENTORY} step the hero moved a
	 * cell, and on the next it moved back. The bug was documented in place as a "no-op", which is how
	 * it survived - {@code pickInteractCell} finds something and {@code handleCell} acts on it, so the
	 * fall-through was never nothing.
	 *
	 * <p>Checked over several consecutive steps rather than one, because the damage is intermittent by
	 * nature: it only shows when something interesting happens to be adjacent, which on a given seed
	 * may be the fortieth step and not the first.
	 */
	private void checkInventoryEntryDoesNotTouchTheWorld(){
		env.reset( SEED, HeroClass.WARRIOR );

		//Put something worth interacting with on a known adjacent cell first.
		//
		//Without this the case passes vacuously. pickInteractCell only returns a cell when a mob, a
		//heap, a locked door or a transition happens to be adjacent, so on a quiet seed the fall-through
		//has nothing to act on and the bug is invisible - which is exactly what happened: restoring the
		//fall-through left this gate green. A regression test that cannot fail on the thing it is
		//testing is worse than no test, because it reports a property the code does not have.
		int heroPos = env.heroPosition();
		int heapCell = adjacentPassableCell( heroPos );
		if (heapCell < 0){
			failures.add( "no passable cell adjacent to the hero at " + heroPos + ", so the"
					+ " OPEN_INVENTORY case cannot be tested: with nothing to interact with, the"
					+ " fall-through has nothing to act on and the bug hides." );
			return;
		}

		Dungeon.level.drop( new Gold( 1 ), heapCell );
		if (Dungeon.level.heaps.get( heapCell ) == null ){
			failures.add( "could not place an item heap on cell " + heapCell + ", so the OPEN_INVENTORY"
					+ " case cannot be tested." );
			return;
		}

		int depth = env.depth();

		for (int i = 0; i < 24 && env.mode() == EnvMode.WORLD; i++ ){
			env.step( Action.OPEN_INVENTORY, 0 );
			if (env.mode() == EnvMode.INVENTORY ) env.step( Action.CANCEL, 0 );
		}

		if (env.heroPosition() != heroPos ){
			failures.add( "OPEN_INVENTORY moved the hero from cell " + heroPos + " to " + env.heroPosition()
					+ ", with an item heap adjacent the whole time. It falls through to the same cell"
					+ " handling INTERACT uses, so opening the inventory also acted on a neighbouring"
					+ " cell - a move, an attack, a pickup, a door or a transition. This reached"
					+ " training: the agent was credited for two things at once." );
			return;
		}

		//the heap must still be there. Under the fall-through the first OPEN_INVENTORY picked it up,
		//because pickInteractCell prefers a heap over anything else - so its *absence* is the failure.
		if (Dungeon.level.heaps.get( heapCell ) == null ){
			failures.add( "the item heap on cell " + heapCell + " is gone after 24 OPEN_INVENTORY steps."
					+ " Under the fall-through it was picked up on the first one, because"
					+ " pickInteractCell prefers a heap over anything else - so opening the inventory"
					+ " collected the gold as well." );
			return;
		}

		if (env.depth() != depth ){
			failures.add( "OPEN_INVENTORY changed depth from " + depth + " to " + env.depth()
					+ ". pickInteractCell returns a transition cell when one is adjacent, so opening the"
					+ " inventory could take a floor transition." );
			return;
		}

		//Every decision costs exactly one turn. Not "the same as WAIT": an inventory round trip is two
		//decisions - open, then close - and each legitimately costs a turn. What must not happen is a
		//turn charged or refunded for something the agent did not choose, which is what the old
		//fall-through did on top of the action.
		env.reset( SEED, HeroClass.WARRIOR );
		int before = env.turnsTotal();
		int decisions = 0;
		for (int i = 0; i < 24 && env.mode() == EnvMode.WORLD; i++ ){
			env.step( Action.OPEN_INVENTORY, 0 );
			decisions++;
			if (env.mode() == EnvMode.INVENTORY ){
				env.step( Action.CANCEL, 0 );
				decisions++;
			}
		}
		int spent = env.turnsTotal() - before;

		if (spent != decisions ){
			failures.add( decisions + " decisions cost " + spent + " turns. Each step the agent takes must"
					+ " cost one turn, so a turn charged or refunded here is a term in the reward the"
					+ " agent never chose." );
		}
	}

	/** The first passable cell among the eight neighbours of {@code pos}, or -1. */
	private int adjacentPassableCell( int pos ){
		int w = Dungeon.level.width();
		int[] offsets = { 1, w, -1, -w, w + 1, w - 1, -w + 1, -w - 1 };

		for (int offset : offsets ){
			int cell = pos + offset;
			if (cell < 0 || cell >= Dungeon.level.length() ) continue;
			if (!Dungeon.level.insideMap( cell )) continue;
			if (!Dungeon.level.passable[ cell ]) continue;
			if (Dungeon.level.heaps.get( cell ) != null ) continue;
			return cell;
		}
		return -1;
	}

	/**
	 * {@code CANCEL} with nothing to cancel is an answer, not a refusal and not an action.
	 *
	 * <p>It shares the fall-through with {@code OPEN_INVENTORY}, so it acted on an adjacent cell too.
	 * And returning false would be wrong in the other direction: {@code SPDEnv} counts a false as
	 * {@code INVALID_ACTION}, which tells the agent it chose badly when it chose correctly.
	 */
	private void checkCancelDoesNotTouchTheWorld(){
		env.reset( SEED, HeroClass.WARRIOR );

		int start = env.heroPosition();

		for (int i = 0; i < 12 && env.mode() == EnvMode.WORLD; i++ ){
			env.step( Action.CANCEL, 0 );

			if (env.ledger().notes( RewardTerm.INVALID_ACTION ) > 0 ){
				failures.add( "CANCEL in WORLD was recorded as INVALID_ACTION. There is nothing to cancel,"
						+ " but the action is legal and choosing it is not a mistake." );
				return;
			}
		}

		if (env.heroPosition() != start ){
			failures.add( "CANCEL with nothing to cancel moved the hero from " + start + " to "
					+ env.heroPosition() + ". It reaches the same cell handling INTERACT does." );
		}
	}

	private void run(){
		//Before anything else, because a mode entry that also acts on the world makes every later
		//phase start from the wrong cell.
		checkInventoryEntryDoesNotTouchTheWorld();
		checkCancelDoesNotTouchTheWorld();

		env.reset( SEED, HeroClass.WARRIOR );

		int stone = slotOfTargetingItem();
		if (stone < 0){
			failures.add( "seed " + SEED + " gives the warrior no item whose use needs an aim, so the "
					+ "targeting path cannot be reached at all" );
			return;
		}

		int stones = quantityAt( stone );

		//WORLD -> SLOT: starting a use is the only route into SLOT
		step( Action.USE, stone, EnvMode.SLOT, "use starts a slot choice" );

		//SLOT -> TARGETING: using the stone needs an aim
		step( Action.USE, stone, EnvMode.TARGETING, "using a missile asks for a target" );

		//TARGETING -> WORLD: resolving the aim, not cancelling it. This is what actually executes
		//resolveTarget, which reaching the mode on its own never proved.
		step( Action.USE, 0, EnvMode.WORLD, "resolving an aim throws the item" );
		if (quantityAt( stone ) >= stones){
			failures.add( "the aim was resolved but the stone count did not fall from " + stones + " to "
					+ quantityAt( stone ) + ", so no throw happened" );
		}

		//WORLD -> INVENTORY, the mode that used to be unreachable
		step( Action.OPEN_INVENTORY, 0, EnvMode.INVENTORY, "opening the inventory" );

		//INVENTORY -> WORLD, driving the branch the same way SLOT does
		int other = slotOfNonTargetingItem();
		step( Action.USE, other, EnvMode.WORLD, "using from the inventory" );

		//SLOT -> WORLD through DROP. Worth its own hop because drop releases the hero through the
		//game's own doDrop rather than through SlotAction, and a mismatch there strands the actor
		//pipeline exactly as a refused equip did.
		int droppable = slotOfDroppableItem();
		if (droppable < 0){
			failures.add( "no droppable item in the starting kit, so the drop path was not executed" );
		} else {
			int before = quantityAt( droppable );
			step( Action.USE, droppable, EnvMode.SLOT, "starting a use to reach SLOT again" );
			step( Action.DROP, droppable, EnvMode.WORLD, "dropping releases the hero's turn" );
			if (quantityAt( droppable ) >= before && before > 0){
				failures.add( "DROP was accepted but the item count did not fall" );
			}
		}

		//WORLD -> MENU: a real dialog, opened the way the game opens one. Headless the scene is null,
		//so GameScene.show parks it where WindowBridge can find it.
		GameScene.show( new WndOptions( "modecheck", "deliberate dialog", "first", "second" ) );
		if (!WindowBridge.open()){
			failures.add( "GameScene.show did not leave a dialog for WindowBridge to find: "
					+ WindowBridge.describe() + ", scene is "
					+ ( com.watabou.noosa.Game.scene() == null ? "null" : "present" ) );
		}
		step( Action.WAIT, 0, EnvMode.MENU, "an open dialog takes over the turn" );

		//MENU -> WORLD: answering it, which is the only thing that ever closes a dialog
		step( Action.MENU_SELECT, 0, EnvMode.WORLD, "selecting an option closes the dialog" );
		if (WindowBridge.open()) failures.add( "the dialog was still open after MENU_SELECT" );

		if (!env.running()){
			failures.add( "the episode ended early with " + env.endReason()
					+ "; a mode check must not depend on the run surviving" );
		}
	}

	/**
	 * Applies one action and asserts the mode it leads to.
	 *
	 * The mode is sampled before stepping as well, because that is the mode a recording stores for
	 * this step.
	 */
	private void step( Action action, int slot, EnvMode next, String what ){
		EnvMode from = env.mode();
		seen.add( from );

		env.step( action, slot );

		if (!env.running()){
			failures.add( "the episode ended with " + env.endReason() + " during: " + what );
			return;
		}

		EnvMode now = env.mode();
		if (now != next){
			failures.add( "expected " + what + " to lead to " + next + ", but " + from + " led to " + now );
		}
	}

	private void report(){
		StringBuilder sb = new StringBuilder( "modes reached: " );
		for (EnvMode mode : EnvMode.values()){
			sb.append( mode ).append( seen.contains( mode ) ? " " : "(MISSING) " );
		}

		for (EnvMode mode : EnvMode.values()){
			if (!seen.contains( mode )) failures.add( "mode never reached: " + mode );
		}

		if (failures.isEmpty()){
			System.out.println( "[OK]     " + sb );
			return;
		}

		System.out.println( "[FAIL]   " + sb );
		for (String failure : failures) System.out.println( "         - " + failure );

		System.exit( 1 );
	}

	private int slotOfTargetingItem(){
		float[] mask = env.slotMask();
		for (int i = 0; i < mask.length; i++){
			Item item = legal( mask, i ) ? env.mapper().slot( i ) : null;
			if (item != null && item.usesTargeting) return i;
		}
		return -1;
	}

	private int slotOfNonTargetingItem(){
		float[] mask = env.slotMask();
		for (int i = 0; i < mask.length; i++){
			Item item = legal( mask, i ) ? env.mapper().slot( i ) : null;
			if (item != null && !item.usesTargeting) return i;
		}
		return 0;
	}

	private int slotOfDroppableItem(){
		float[] mask = env.slotMask();
		for (int i = 0; i < mask.length; i++){
			Item item = legal( mask, i ) ? env.mapper().slot( i ) : null;
			if (item != null && !item.isEquipped( com.shatteredpixel.shatteredpixeldungeon.Dungeon.hero )) return i;
		}
		return -1;
	}

	/** Stack size of whatever occupies a slot, which is how a throw is detected. */
	private int quantityAt( int slot ){
		Item item = env.mapper().slot( slot );
		return (item != null) ? item.quantity() : 0;
	}

	private static boolean legal( float[] mask, int index ){
		return index >= 0 && index < mask.length && mask[ index ] > 0.5f;
	}
}
