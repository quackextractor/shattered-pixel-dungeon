package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.NPC;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;

import java.util.ArrayList;

/**
 * Turns an {@link Action} plus a slot or target index into a game action, and reports which
 * choices are legal.
 *
 * Nothing here goes through input handling. Movement is expressed as a cell and handed to
 * {@code Hero.handle}, which is what the game's own cell selector calls, so all of the game's
 * action resolution - attack versus loot versus unlock versus stairs - applies unchanged.
 *
 * Masking is how menus are handled: an illegal action gets a -inf logit, so no gradient can push
 * probability onto it and the policy cannot walk north while standing in a shop.
 */
public class ActionMapper {

	/**
	 * Candidate aim cells, as offsets from the hero, with self first.
	 *
	 * Eight neighbours is enough because shots resolve against a Ballistica collision cell, and
	 * anything further away has to be fired through one of these directions anyway. Keeping the
	 * aiming head this small is what makes it cheap to learn.
	 */
	public static final int[][] TARGET_OFFSETS = {
			{  0,  0 },
			{  0, -1 },
			{  1, -1 },
			{  1,  0 },
			{  1,  1 },
			{  0,  1 },
			{ -1,  1 },
			{ -1,  0 },
			{ -1, -1 },
	};

	public static final int TARGET_COUNT = TARGET_OFFSETS.length;

	private static final int[] LOCKED_DOOR_TERRAINS = {
			Terrain.LOCKED_DOOR,
			Terrain.LOCKED_EXIT,
			Terrain.CRYSTAL_DOOR,
			Terrain.HERO_LKD_DR,
	};

	private final EnvConfig config;

	/** Fixed-length view of the hero's inventory, refreshed once per step. */
	private final ArrayList<Item> slots = new ArrayList<>();

	public ActionMapper( EnvConfig config ){
		this.config = config;
		for (int i = 0; i < config.maxSlots; i++) slots.add( null );
	}

	private Hero hero(){
		return Dungeon.hero;
	}

	// --------------------------------------------------------------------------- slots

	/**
	 * Rebuilds the fixed inventory view.
	 *
	 * Quickslot-assigned items take their bound slot, everything else fills from the front. The
	 * mapping is stable within a step so the mask, the observation encoder and the agent's slot
	 * index all agree on what "slot 3" means.
	 */
	public void refreshSlots(){
		for (int i = 0; i < slots.size(); i++) slots.set( i, null );

		for (Item item : hero().belongings.backpack){
			int bound = Dungeon.quickslot.getSlot( item );
			if (bound >= 0 && bound < slots.size() && slots.get( bound ) == null){
				slots.set( bound, item );
				continue;
			}
			int empty = slots.indexOf( null );
			if (empty >= 0) slots.set( empty, item );
		}
	}

	public ArrayList<Item> slots(){
		return slots;
	}

	public Item slot( int index ){
		return (index >= 0 && index < slots.size()) ? slots.get( index ) : null;
	}

	// --------------------------------------------------------------------------- masks

	/**
	 * @param mask output of {@link Action#size()} floats, 1 for legal and 0 for illegal.
	 *             Reused across turns to keep rollout allocation flat.
	 */
	public void actionMask( EnvMode mode, float[] mask ){
		for (int i = 0; i < mask.length; i++) mask[i] = 0f;

		if (mode == EnvMode.MENU){
			mask[Action.CANCEL.index] = 1f;
			if (WindowBridge.open()) mask[Action.MENU_SELECT.index] = 1f;
			return;
		}

		if (mode == EnvMode.SLOT || mode == EnvMode.INVENTORY){
			//SLOT used to fall through to the WORLD mask below, which offered movement and WAIT while
			//the environment was waiting for an item choice. That is not merely a cosmetic mismatch:
			//SlotAction.execute treats every action that is neither CANCEL nor DROP as a use, so a
			//legal-looking MOVE here spent the item. The mask cannot see which slot was picked - it
			//takes no slot argument - so this mirrors INVENTORY and asks whether any usable or
			//droppable item exists.
			mask[Action.CANCEL.index] = 1f;
			if (hasUsableItem()) mask[Action.USE.index] = 1f;
			if (hasDroppableItem()) mask[Action.DROP.index] = 1f;
			return;
		}

		if (mode == EnvMode.TARGETING){
			mask[Action.CANCEL.index] = 1f;
			return;
		}

		boolean interactable = adjacentMob()
				|| adjacentHeap()
				|| adjacentDoor()
				|| adjacentTransition()
				|| adjacentNpc()
				|| adjacentAlchemy();

		for (Action a : Action.values()){
			switch (a) {
				case WAIT:
				case REST:
				case SEARCH:
					mask[a.index] = 1f;
					break;
				case USE:
					mask[a.index] = hasUsableItem() ? 1f : 0f;
					break;
				case DROP:
					mask[a.index] = hasDroppableItem() ? 1f : 0f;
					break;
				case INTERACT:
					mask[a.index] = interactable ? 1f : 0f;
					break;
				case OPEN_INVENTORY:
					//always available, like the inventory key in the real game. Without it in the
					//mask the action is unreachable, and since SPDEnv only enters INVENTORY from
					//here, EnvMode.INVENTORY could never occur and never be recorded in a replay.
					mask[a.index] = 1f;
					break;
				case MOVE_N: case MOVE_NE: case MOVE_E: case MOVE_SE:
				case MOVE_S:  case MOVE_SW: case MOVE_W:  case MOVE_NW:
					mask[a.index] = moveLegal( a ) ? 1f : 0f;
					break;
				default:
					//MENU_SELECT and CANCEL are only offered by the modes above
					break;
			}
		}

		//A fully masked turn would make the softmax normalise NaN, so guarantee one option.
		boolean any = false;
		for (float f : mask) if (f != 0f){ any = true; break; }
		if (!any) mask[Action.WAIT.index] = 1f;
	}

	/** Target mask: index 0 is always available, the eight neighbours when on the map. */
	public boolean targetMask( int index ){
		if (index < 0 || index >= TARGET_COUNT) return false;
		if (index == 0) return true;
		return insideMap( targetCell( index ) );
	}

	/** Slot mask: 1 where a slot holds an item. */
	public void slotMask( float[] mask ){
		for (int i = 0; i < mask.length; i++){
			mask[i] = (i < slots.size() && slots.get( i ) != null) ? 1f : 0f;
		}
	}

	// --------------------------------------------------------------------------- execution

	/**
	 * Applies a WORLD or TIME action.
	 *
	 * <p><b>Returns whether the environment accepted the action</b>, which {@link SPDEnv#step} reads to
	 * decide whether to note {@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm#INVALID_ACTION}.
	 * It used to be documented as "needs a follow-up choice", and that description was load-bearing in
	 * the wrong direction: {@code WAIT}, {@code REST} and {@code SEARCH} were doing exactly what they
	 * were asked and still returned {@code false}.
	 *
	 * <p>That misclassification is not cosmetic. {@code WAIT} costs a turn, leaves the hero where he
	 * was, and changes no HP — which is precisely what {@code SPDEnv}'s stall guard tests. So
	 * {@code WAIT} × {@code stallLimit} was the cheapest route to a terminal episode, and the agent
	 * found it: a 20-generation run ended 100% of its episodes by stalling. See
	 * {@code PLAN-reward-signals.md}.
	 *
	 * <p>Follow-up is decided separately and unconditionally by the caller — {@code USE} and
	 * {@code DROP} always move to {@link EnvMode#SLOT} — so nothing here needs to signal it.
	 */
	public boolean apply( Action action, int slotOrTarget ){
		if (action == null) return false;
		Hero hero = hero();

		//Choosing any action while resting ends the rest, which is what Hero.act() does at the top
		//of its curAction branch. Without this, REST is a one-way door: Hero.rest() never sets a
		//curAction, so Hero.act() keeps taking the `curAction == null && resting` branch, which
		//spends time and calls next() but never ready(). The hero then rests forever, and
		//recoverStrandedHero() cannot help because it deliberately refuses a resting hero - the way
		//a player escapes is by choosing another action, which headless input never does.
		if (hero.resting && action != Action.REST){
			hero.resting = false;
		}

		switch (action) {
			case WAIT:
				//No curAction: Hero.act() with curAction null calls ready() and hands control back.
				//Waiting costs the hero nothing in the real game either; other actors advance time.
				//True, because it is a legal action the environment performed - not a refusal.
				hero.next();
				return true;

			case REST:
				hero.resting = true;
				hero.next();
				return true;

			case SEARCH:
				hero.search( true );
				hero.next();
				return true;

			case USE:
			case DROP:
				SlotAction.clearPendingUseItem();
				return true;

			default:
				break;
		}

		int cell = action.directional() ? offsetCell( action.dx, action.dy ) : pickInteractCell();
		return handleCell( cell );
	}

	/** Applies the agent's choice for the mode's secondary input. */
	public boolean applySecondary( EnvMode mode, Action action, int index ){
		switch (mode) {
			case TARGETING:
				return resolveTarget( index );

			case MENU:
				if (action == Action.CANCEL) return WindowBridge.close();
				return WindowBridge.selectOption( index );

			case SLOT:
			case INVENTORY:
				return SlotAction.execute( hero(), slot( index ), action, config.allowEquipping );

			default:
				return false;
		}
	}

	/**
	 * Feeds the chosen aim cell to whatever the game asked to be aimed at.
	 *
	 * The game stashes its aiming listener in GameScene.pendingCellListener - see Wand.execute,
	 * Item.doThrow and the armor abilities. Invoking that listener means ballistics, charge
	 * consumption and collision handling remain the game's own code.
	 */
	private boolean resolveTarget( int index ){
		CellSelector.Listener aim = GameScene.pendingCellListener();
		GameScene.clearPendingCellListener();

		if (aim != null ){
			aim.onSelect( targetCell( index ) );
			return true;
		}

		Item pending = SlotAction.pendingUseItem();
		if (pending != null && pending.usesTargeting){
			SlotAction.castAt( hero(), pending, targetCell( index ) );
			return true;
		}
		return false;
	}

	// --------------------------------------------------------------------------- geometry

	private boolean handleCell( int cell ){
		if (!insideMap( cell )) return false;
		Hero hero = hero();
		if (hero.handle( cell )) {
			hero.next();
			return true;
		}
		return false;
	}

	public int offsetCell( int dx, int dy ){
		return hero().pos + dx + dy * Dungeon.level.width();
	}

	public int targetCell( int index ){
		if (index < 0) index = 0;
		if (index >= TARGET_COUNT) index = TARGET_COUNT - 1;
		int[] o = TARGET_OFFSETS[ index ];
		return offsetCell( o[0], o[1] );
	}

	private boolean insideMap( int cell ){
		return cell >= 0
				&& cell < Dungeon.level.length()
				&& Dungeon.level.insideMap( cell );
	}

	/**
	 * Chooses the cell for {@link Action#INTERACT}: adjacent mob first, then heap, then locked
	 * door, then stairs.
	 *
	 * Which one is a heuristic, so the agent does not have to. What it does decide is whether to
	 * interact at all, which is the decision worth learning.
	 */
	private int pickInteractCell(){
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (!insideMap( cell )) continue;
			Char ch = Actor.findChar( cell );
			if (ch instanceof Mob && hero().canAttack( (Mob)ch )) return cell;
		}
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (insideMap( cell ) && Dungeon.level.heaps.get( cell ) != null ) return cell;
		}
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (!insideMap( cell )) continue;
			int terrain = Dungeon.level.map[ cell ];
			for (int t : LOCKED_DOOR_TERRAINS) if (terrain == t) return cell;
		}
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (insideMap( cell ) && Dungeon.level.getTransition( cell ) != null ) return cell;
		}
		return hero().pos;
	}

	// --------------------------------------------------------------------------- adjacency

	private boolean moveLegal( Action action ){
		int cell = offsetCell( action.dx, action.dy );
		if (!insideMap( cell )) return false;

		//Walking into something is legal when the cell selector would act on it rather than
		//entering it: attacking an adjacent mob, opening a chest, looting a heap.
		Char ch = Actor.findChar( cell );
		if (ch instanceof Mob) return hero().canAttack( (Mob)ch );
		if (Dungeon.level.heaps.get( cell ) != null) return true;
		return Dungeon.level.passable[ cell ];
	}

	private boolean adjacentMob(){
		Hero hero = hero();
		for (Mob mob : hero.getVisibleEnemies()){
			if (mob.isAlive() && Dungeon.level.adjacent( hero.pos, mob.pos )) return true;
		}
		return false;
	}

	private boolean adjacentHeap(){
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (insideMap( cell ) && Dungeon.level.heaps.get( cell ) != null ) return true;
		}
		return false;
	}

	private boolean adjacentDoor(){
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (!insideMap( cell )) continue;
			int terrain = Dungeon.level.map[ cell ];
			for (int t : LOCKED_DOOR_TERRAINS) if (terrain == t) return true;
		}
		return false;
	}

	private boolean adjacentTransition(){
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (insideMap( cell ) && Dungeon.level.getTransition( cell ) != null ) return true;
		}
		return false;
	}

	private boolean adjacentNpc(){
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (!insideMap( cell )) continue;
			if (Actor.findChar( cell ) instanceof NPC ) return true;
		}
		return false;
	}

	private boolean adjacentAlchemy(){
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (insideMap( cell ) && Dungeon.level.map[ cell ] == Terrain.ALCHEMY ) return true;
		}
		return false;
	}

	// --------------------------------------------------------------------------- item checks

	private boolean hasUsableItem(){
		for (Item item : slots){
			if (item == null) continue;
			if (item instanceof Wand) return true;
			String action = item.defaultAction();
			if (action != null
					&& !action.equals( Item.AC_DROP )
					&& !action.equals( Item.AC_THROW )) return true;
		}
		return false;
	}

	private boolean hasDroppableItem(){
		Hero hero = hero();
		for (Item item : slots){
			if (item != null && !item.isEquipped( hero )) return true;
		}
		return false;
	}

	/** True when a shop-like heap is adjacent, which is what makes trading reachable. */
	public boolean adjacentForSale(){
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (!insideMap( cell )) continue;
			Heap heap = Dungeon.level.heaps.get( cell );
			if (heap != null && heap.type == Heap.Type.FOR_SALE ) return true;
		}
		return false;
	}
}