package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.items.Recipe;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.food.MysteryMeat;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Pasty;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.AlchemyAction;
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

public class ModeCoverageCheck {

	private static final String SEED = "MODE-CHECK";

	private SPDEnv env;
	private final Set< EnvMode > seen = EnumSet.noneOf( EnvMode.class );
	private final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "modecheck" ) );
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 2000;

		ModeCoverageCheck check = new ModeCoverageCheck();
		check.env = new SPDEnv( config, HeadlessGame.install() );
		check.run();

		check.report();
	}

	private void checkInventoryEntryDoesNotTouchTheWorld(){
		env.reset( SEED, HeroClass.WARRIOR );

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

		for (int i = 0; i < 8 && env.running() && env.mode() == EnvMode.WORLD; i++ ){
			env.step( Action.OPEN_INVENTORY, 0 );
			if (env.running() && env.mode() == EnvMode.INVENTORY ) env.step( Action.CANCEL, 0 );
		}

		if (!env.running() ){
			failures.add( "eight OPEN_INVENTORY/CANCEL pairs ended the episode (" + env.endReason()
					+ "), so this case never checked what it was written to check. The heap and the"
					+ " hero's position are the assertions; running out of episode first is a failure of"
					+ " the fixture, not a pass." );
			return;
		}

		if (env.heroPosition() != heroPos ){
			failures.add( "OPEN_INVENTORY moved the hero from cell " + heroPos + " to " + env.heroPosition()
					+ ", with an item heap adjacent the whole time. It falls through to the same cell"
					+ " handling INTERACT uses, so opening the inventory also acted on a neighbouring"
					+ " cell - a move, an attack, a pickup, a door or a transition. This reached"
					+ " training: the agent was credited for two things at once." );
			return;
		}

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

		env.reset( SEED, HeroClass.WARRIOR );
		int before = env.turnsTotal();
		int decisions = 0;
		for (int i = 0; i < 8 && env.running() && env.mode() == EnvMode.WORLD; i++ ){
			env.step( Action.OPEN_INVENTORY, 0 );
			decisions++;
			if (env.running() && env.mode() == EnvMode.INVENTORY ){
				env.step( Action.CANCEL, 0 );
				decisions++;
			}
		}
		int spent = env.turnsTotal() - before;

		if (!env.running() ){
			failures.add( "eight inventory round trips ended the episode before the turn accounting could"
					+ " be compared (" + env.endReason() + "). The assertion here is that every decision"
					+ " costs exactly one turn; running out of episode first means it was never asked." );
			return;
		}

		if (spent != decisions ){
			failures.add( decisions + " decisions cost " + spent + " turns. Each step the agent takes must"
					+ " cost one turn, so a turn charged or refunded here is a term in the reward the"
					+ " agent never chose." );
		}
	}

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

		checkInventoryEntryDoesNotTouchTheWorld();
		checkInteractCanAimAtACell();
		checkCancelDoesNotTouchTheWorld();

		env.reset( SEED, HeroClass.WARRIOR );

		int stone = slotOfTargetingItem();
		if (stone < 0){
			failures.add( "seed " + SEED + " gives the warrior no item whose use needs an aim, so the "
					+ "targeting path cannot be reached at all" );
			return;
		}

		int stones = quantityAt( stone );

		step( Action.USE, stone, EnvMode.SLOT, "use starts a slot choice" );

		step( Action.USE, stone, EnvMode.TARGETING, "using a missile asks for a target" );

		step( Action.USE, 0, EnvMode.WORLD, "resolving an aim throws the item" );
		if (quantityAt( stone ) >= stones){
			failures.add( "the aim was resolved but the stone count did not fall from " + stones + " to "
					+ quantityAt( stone ) + ", so no throw happened" );
		}

		step( Action.OPEN_INVENTORY, 0, EnvMode.INVENTORY, "opening the inventory" );

		int other = slotOfNonTargetingItem();
		step( Action.USE, other, EnvMode.WORLD, "using from the inventory" );

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

		GameScene.show( new WndOptions( "modecheck", "deliberate dialog", "first", "second" ) );
		if (!WindowBridge.open()){
			failures.add( "GameScene.show did not leave a dialog for WindowBridge to find: "
					+ WindowBridge.describe() + ", scene is "
					+ ( com.watabou.noosa.Game.scene() == null ? "null" : "present" ) );
		}
		step( Action.WAIT, 0, EnvMode.MENU, "an open dialog takes over the turn" );

		step( Action.MENU_SELECT, 0, EnvMode.WORLD, "selecting an option closes the dialog" );
		if (WindowBridge.open()) failures.add( "the dialog was still open after MENU_SELECT" );

		Dungeon.hero.lvl = 3;
		env.step( Action.WAIT, 0 );

		step( Action.TALENT, 0, EnvMode.TALENT, "spending a talent point" );

		step( Action.MENU_SELECT, 0, EnvMode.WORLD, "choosing a talent spends the point" );
		if (env.talentChoices().isEmpty()){
			failures.add( "the talent pane closed but offers nothing, so either nothing was spent or"
					+ " the picker is reading a hero that has no talents" );
		}

		checkTheAlchemyPotIsReachable();

		if (!env.running()){
			failures.add( "the episode ended early with " + env.endReason()
					+ "; a mode check must not depend on the run surviving" );
		}
	}

	private void checkTheAlchemyPotIsReachable(){
		env.reset( SEED + "-ALCHEMY", HeroClass.WARRIOR );

		int pot = plantAlchemyPotBeside( Dungeon.hero );
		if (pot < 0){
			failures.add( "no cell beside the hero could be turned into an alchemy pot on "
					+ ( SEED + "-ALCHEMY" ) + ", so the pot path could not be reached at all" );
			return;
		}

		giveIngredients();
		Dungeon.energy = 20;
		env.step( Action.WAIT, 0 );

		if (env.actionMask()[ Action.ALCHEMY.index ] == 0f ){
			failures.add( "the hero is beside an alchemy pot carrying a Pasty, a plain Food and a"
					+ " MysteryMeat, with 20 energy, and ALCHEMY is still masked. The pot is"
					+ " unreachable from the world mask, so CRAFTED cannot fire in a real run." );
			return;
		}

		int craftedBefore = Statistics.itemsCrafted;
		int energyBefore = Dungeon.energy;

		List< Recipe > available = AlchemyAction.recipes( Dungeon.hero );
		int index = -1;
		for (int i = 0; i < available.size(); i++){
			if (AlchemyAction.isMeatPie( available.get( i ) )){ index = i; break; }
		}

		if (index < 0){
			failures.add( "the fixture carries a Pasty, a plain Food and a MysteryMeat, which is"
					+ " exactly the meat pie recipe, and the pot offers " + available.size()
					+ " recipe(s) without it. The case that would prove CRAFTED_MEAT_PIE fires proved"
					+ " nothing." );
			return;
		}

		step( Action.ALCHEMY, 0, EnvMode.ALCHEMY, "standing at the pot" );
		step( Action.MENU_SELECT, index, EnvMode.WORLD, "brewing the meat pie" );

		if (Statistics.itemsCrafted <= craftedBefore ){
			failures.add( "the meat pie recipe was chosen and nothing was brewed" );
			return;
		}
		if (Dungeon.energy >= energyBefore ){
			failures.add( "the pot brewed an item without spending energy ("
					+ energyBefore + " -> " + Dungeon.energy + ")" );
			return;
		}
		if (env.ledger().termTotal( RewardTerm.CRAFTED ) <= 0 ){
			failures.add( "an item was brewed and CRAFTED paid nothing, so the term docs.md asks for"
					+ " cannot fire in a real run" );
			return;
		}
		if (env.ledger().termTotal( RewardTerm.CRAFTED_MEAT_PIE ) <= 0 ){
			failures.add( "the meat pie was brewed and CRAFTED_MEAT_PIE paid nothing on top of"
					+ " CRAFTED. The game has no counter for a meat pie specifically, so this term is"
					+ " the only place that can say one was made, and docs.md asks for it by name." );
			return;
		}

		System.out.println( "  a meat pie is brewed at the pot: energy " + energyBefore + " -> "
				+ Dungeon.energy + ", CRAFTED paid "
				+ env.ledger().termTotal( RewardTerm.CRAFTED )
				+ ", CRAFTED_MEAT_PIE paid "
				+ env.ledger().termTotal( RewardTerm.CRAFTED_MEAT_PIE ) );
	}

	private static int plantAlchemyPotBeside( Hero hero ){
		int[][] offsets = { { 0, 1 }, { 1, 0 }, { 0, -1 }, { -1, 0 }, { 1, 1 }, { -1, -1 } };

		for (int[] o : offsets){
			int cell = hero.pos + o[ 0 ] + o[ 1 ] * Dungeon.level.width();
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			if (!Dungeon.level.passable[ cell ]) continue;
			Dungeon.level.map[ cell ] = com.shatteredpixel.shatteredpixeldungeon.levels.Terrain.ALCHEMY;
			Dungeon.level.discover( cell );
			return cell;
		}
		return -1;
	}

	private static void giveIngredients(){
		Dungeon.hero.belongings.backpack.items.clear();
		Dungeon.hero.belongings.backpack.items.add( new Pasty() );
		Dungeon.hero.belongings.backpack.items.add( new Food() );
		Dungeon.hero.belongings.backpack.items.add( new MysteryMeat() );
	}

	private void checkInteractCanAimAtACell(){
		env.reset( SEED, HeroClass.WARRIOR );

		int heroPos = env.heroPosition();
		int heapCell = adjacentPassableCell( heroPos );
		if (heapCell < 0){
			failures.add( "no passable cell adjacent to the hero at " + heroPos + ", so the aimed"
					+ " INTERACT cannot be tested." );
			return;
		}

		Dungeon.level.drop( new Gold( 3 ), heapCell );
		if (Dungeon.level.heaps.get( heapCell ) == null ){
			failures.add( "could not place an item heap on cell " + heapCell + ", so the aimed"
					+ " INTERACT cannot be tested." );
			return;
		}

		int direction = directionOf( heroPos, heapCell );
		if (direction < 0){
			failures.add( "cell " + heapCell + " is not one of the eight neighbours of " + heroPos
					+ ", so it cannot be named as an aim direction." );
			return;
		}

		env.step( Action.WAIT, 0 );

		float[] mask = env.targetMask();
		if (mask[ 0 ] == 0f ){
			failures.add( "index 0 is not admitted by the aim mask, so \"let the environment choose\""
					+ " is not expressible and every recording that names it would be refused." );
			return;
		}
		if (mask[ direction ] == 0f ){
			failures.add( "a heap sits on cell " + heapCell + ", which is direction " + direction
					+ " from the hero, and the aim mask does not admit it. The agent is told to aim"
					+ " somewhere and then cannot." );
			return;
		}

		int gold = Dungeon.gold;
		env.step( Action.INTERACT, direction );

		if (Dungeon.level.heaps.get( heapCell ) != null ){
			failures.add( "INTERACT aimed at the heap's own direction left the heap on cell "
					+ heapCell + ". The direction is ignored and the heuristic ran instead." );
			return;
		}

		if (Dungeon.gold <= gold ){
			failures.add( "INTERACT aimed at a heap of 3 gold collected nothing: gold went from "
					+ gold + " to " + Dungeon.gold + "." );
			return;
		}

		System.out.println( "  INTERACT acts on the cell it is aimed at, and index 0 still means"
				+ " \"let the environment choose\"" );
	}

	private int directionOf( int from, int to ){
		int width = Dungeon.level.width();
		int dx = ( to % width ) - ( from % width );
		int dy = ( to / width ) - ( from / width );

		int[][] offsets = com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper
				.TARGET_OFFSETS;

		for (int i = 1; i < offsets.length; i++){
			if (offsets[ i ][ 0 ] == dx && offsets[ i ][ 1 ] == dy ) return i;
		}
		return -1;
	}

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

	private int quantityAt( int slot ){
		Item item = env.mapper().slot( slot );
		return (item != null) ? item.quantity() : 0;
	}

	private static boolean legal( float[] mask, int index ){
		return index >= 0 && index < mask.length && mask[ index ] > 0.5f;
	}
}
