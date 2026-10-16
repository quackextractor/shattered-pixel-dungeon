package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.NPC;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;

import java.util.ArrayList;
import java.util.List;


public class ActionMapper {


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


	private final ArrayList<Item> slots = new ArrayList<>();


	private boolean zeroTimeModeExit;

	public ActionMapper( EnvConfig config ){
		this.config = config;
		for (int i = 0; i < config.maxSlots; i++) slots.add( null );
	}

	private Hero hero(){
		return Dungeon.hero;
	}


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


	public void noteZeroTimeModeExit(){
		zeroTimeModeExit = true;
	}


	public void clearZeroTimeModeExit(){
		zeroTimeModeExit = false;
	}

	public Item slot( int index ){
		return (index >= 0 && index < slots.size()) ? slots.get( index ) : null;
	}


	public void actionMask( EnvMode mode, float[] mask ){
		for (int i = 0; i < mask.length; i++) mask[i] = 0f;

		if (mode == EnvMode.MENU){
			mask[Action.CANCEL.index] = 1f;
			if (WindowBridge.open()) mask[Action.MENU_SELECT.index] = 1f;
			return;
		}

		if (mode == EnvMode.SLOT || mode == EnvMode.INVENTORY){

			mask[Action.CANCEL.index] = 1f;
			if (hasUsableItem()) mask[Action.USE.index] = 1f;
			if (hasDroppableItem()) mask[Action.DROP.index] = 1f;
			return;
		}

		if (mode == EnvMode.TALENT){
			mask[Action.CANCEL.index] = 1f;
			if (!talentChoices().isEmpty()) mask[Action.MENU_SELECT.index] = 1f;
			return;
		}

		if (mode == EnvMode.TARGETING){
			mask[Action.CANCEL.index] = 1f;
			return;
		}

		boolean progressUse = hasProgressItem();
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
					mask[a.index] = progressUse ? 1f : 0f;
					break;
				case DROP:
					mask[a.index] = hasDroppableItem() ? 1f : 0f;
					break;
				case INTERACT:
					mask[a.index] = interactable ? 1f : 0f;
					break;
				case OPEN_INVENTORY:

					mask[a.index] = (config.subModeProgressOnly && !progressUse) ? 0f : 1f;
					break;
				case TALENT:
					mask[a.index] = TalentPicker.spendableCount( hero() ) > 0 ? 1f : 0f;
					break;
				case MOVE_N: case MOVE_NE: case MOVE_E: case MOVE_SE:
				case MOVE_S:  case MOVE_SW: case MOVE_W:  case MOVE_NW:
					mask[a.index] = moveLegal( a ) ? 1f : 0f;
					break;
				default:

					break;
			}
		}

		boolean any = false;
		for (float f : mask) if (f != 0f){ any = true; break; }
		if (!any) mask[Action.WAIT.index] = 1f;

		if (zeroTimeModeExit){
			zeroTimeModeExit = false;
			mask[Action.OPEN_INVENTORY.index] = 0f;
			mask[Action.USE.index] = 0f;
			mask[Action.DROP.index] = 0f;
			if (!any){
				mask[Action.OPEN_INVENTORY.index] = 1f;
				mask[Action.USE.index] = 1f;
				mask[Action.DROP.index] = 1f;
			}
		}
	}


	public boolean targetMask( int index ){
		if (index < 0 || index >= TARGET_COUNT) return false;
		if (index == 0) return true;
		return insideMap( targetCell( index ) );
	}


	public void slotMask( float[] mask ){
		for (int i = 0; i < mask.length; i++){
			mask[i] = (i < slots.size() && slots.get( i ) != null) ? 1f : 0f;
		}
	}


	public void slotMask( EnvMode mode, float[] mask ){
		if (mode != EnvMode.TALENT){
			slotMask( mask );
			return;
		}

		for (int i = 0; i < mask.length; i++) mask[ i ] = 0f;
		List<Talent> options = talentChoices();
		for (int i = 0; i < options.size() && i < mask.length; i++) mask[ i ] = 1f;
	}


	public List<Talent> talentChoices(){
		return TalentPicker.available( hero() );
	}


	public boolean apply( Action action, int slotOrTarget ){
		if (action == null) return false;
		Hero hero = hero();

		if (hero.resting && action != Action.REST){
			hero.resting = false;
		}

		switch (action) {
			case WAIT:

				hero.spendAndNext( 1f );
				return true;

			case REST:
				hero.resting = true;
				hero.spendAndNext( Actor.TICK );
				hero.resting = false;
				return true;

			case SEARCH:
				hero.search( true );
				hero.next();
				return true;

			case USE:
			case DROP:

				SlotAction.clearPendingUseItem();
				return false;

			case OPEN_INVENTORY:

				return true;

			case CANCEL:

				return true;

			case INTERACT:
				return interact( slotOrTarget );

			default:
				break;
		}

		return handleCell( offsetCell( action.dx, action.dy ));
	}


	public boolean applySecondary( EnvMode mode, Action action, int index ){
		switch (mode) {
			case TARGETING:

				if (action == Action.CANCEL){
					return SlotAction.cancelPendingUse( hero() );
				}
				return resolveTarget( index );

			case MENU:
				if (action == Action.CANCEL) return WindowBridge.close();
				return WindowBridge.selectOption( index );

			case TALENT:
				return action == Action.MENU_SELECT && TalentPicker.spend( hero(), index );

			case SLOT:
			case INVENTORY:
				if (PICKUP_TRACE && action == Action.DROP) traceDrop( hero(), this, index );
				return SlotAction.execute( hero(), slot( index ), action, config.allowEquipping );

			default:
				return false;
		}
	}


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


	private boolean handleCell( int cell ){
		if (!insideMap( cell )) return false;

		Hero hero = hero();

		if (!Dungeon.level.adjacent( hero.pos, cell )) return false;

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



	private boolean interact( int direction ){
		if (direction > 0 && direction < TARGET_COUNT){
			int cell = targetCell( direction );
			if (interactive( cell )) return handleCell( cell );
		}
		return handleCell( pickInteractCell() );
	}


	private boolean interactive( int cell ){
		if (!insideMap( cell )) return false;
		if (cell == hero().pos) return false;

		Char ch = Actor.findChar( cell );
		if (ch instanceof Mob) return hero().canAttack( (Mob)ch );
		if (ch instanceof NPC) return true;
		if (Dungeon.level.heaps.get( cell ) != null) return true;
		if (Dungeon.level.getTransition( cell ) != null) return true;

		int terrain = Dungeon.level.map[ cell ];
		for (int t : LOCKED_DOOR_TERRAINS) if (terrain == t) return true;
		return terrain == Terrain.ALCHEMY;
	}


	public void interactMask( float[] mask ){
		for (int i = 0; i < mask.length; i++) mask[ i ] = 0f;
		if (mask.length == 0) return;

		mask[ 0 ] = 1f;
		for (int o = 1; o < TARGET_COUNT && o < mask.length; o++){
			if (interactive( targetCell( o ) )) mask[ o ] = 1f;
		}
	}

	private int pickInteractCell(){
		StringBuilder dbg = PICKUP_TRACE ? new StringBuilder() : null;

		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (!insideMap( cell )) continue;
			Char ch = Actor.findChar( cell );
			if (dbg != null){
				dbg.append( " [o=" ).append( o ).append( " cell=" ).append( cell )
						.append( " ch=" ).append( ch == null ? "none" : ch.getClass().getSimpleName() )
						.append( " mob=" ).append( (ch instanceof Mob) )
						.append( " canAtk=" ).append( (ch instanceof Mob) && hero().canAttack( (Mob)ch ) )
						.append( " heap=" ).append( (Dungeon.level.heaps.get( cell ) != null)
								? (Dungeon.level.heaps.get( cell ).size() + "x" + Dungeon.level.heaps.get( cell ).type) : "no" )
						.append( " trans=" ).append( Dungeon.level.getTransition( cell ) != null )
						.append( " ]" );
			}
			if (ch instanceof Mob && hero().canAttack( (Mob)ch )) {
				if (dbg != null) System.err.println( "[interact] picked mob at " + cell + dbg );
				return cell;
			}
		}
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			Heap h = insideMap( cell ) ? Dungeon.level.heaps.get( cell ) : null;
			if (h != null) {
				if (PICKUP_TRACE) tracePickup( cell, h );
				if (dbg != null){
					System.err.println( "[interact] picked heap at " + cell + " size=" + h.size()
							+ " type=" + h.type + " peek=" + (h.peek() == null ? "null" : h.peek().getClass().getSimpleName())
							+ " seen=" + h.seen + dbg );
				}
				return cell;
			}
		}
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (!insideMap( cell )) continue;
			int terrain = Dungeon.level.map[ cell ];
			for (int t : LOCKED_DOOR_TERRAINS) if (terrain == t) {
				if (dbg != null) System.err.println( "[interact] picked door at " + cell + dbg );
				return cell;
			}
		}
		for (int o = 1; o < TARGET_COUNT; o++){
			int cell = targetCell( o );
			if (insideMap( cell ) && Dungeon.level.getTransition( cell ) != null ) {
				if (dbg != null) System.err.println( "[interact] picked transition at " + cell + dbg );
				return cell;
			}
		}
		if (dbg != null) System.err.println( "[interact] found nothing" + dbg );
		return hero().pos;
	}


	private static final boolean PICKUP_TRACE = System.getProperty( "spd.pickupTrace" ) != null;


	private static String quickslotState( Hero hero ){
		StringBuilder sb = new StringBuilder();
		for (Item it : hero.belongings.backpack){
			int slot = Dungeon.quickslot.getSlot( it );
			sb.append( it.getClass().getSimpleName() ).append( "->" ).append( slot ).append( " " );
		}
		return sb.toString().trim();
	}


	private static void traceDrop( Hero hero, ActionMapper mapper, int slot ){
		ArrayList<Item> slots = mapper.slots();
		Item inSlot = slot >= 0 && slot < slots.size() ? slots.get( slot ) : null;
		StringBuilder order = new StringBuilder();
		for (Item it : hero.belongings.backpack){
			order.append( it.getClass().getSimpleName() )
					.append( "(cat=" ).append( com.shatteredpixel.shatteredpixeldungeon.items.Generator.Category.order( it ) )
					.append( ",img=" ).append( it.image() ).append( ") " );
		}
		System.err.println( "[drop] slot=" + slot
				+ " item=" + (inSlot == null ? "null" : inSlot.getClass().getSimpleName())
				+ " equipped=" + (inSlot != null && inSlot.isEquipped( hero ))
				+ " bagRoom=" + bagRoom( hero.belongings.backpack )
				+ " order=" + order
				+ " qs=" + quickslotState( hero )
				+ " bags=" + bagList() );
	}

	private static void tracePickup( int cell, Heap h ){
		StringBuilder items = new StringBuilder();
		h.items.forEach( i -> items.append( i.getClass().getSimpleName() ).append( " " ) );
		Hero hero = Dungeon.hero;
		System.err.println( "[pickup] cell=" + cell + " size=" + h.size()
				+ " backpack=" + bagRoom( hero.belongings.backpack )
				+ " bags=" + bagList()
				+ " heroClass=" + hero.heroClass
				+ " challenges=" + Dungeon.challenges
				+ " velvetDropped=" + Dungeon.LimitedDrops.VELVET_POUCH.dropped()
				+ " heroIdentity=" + System.identityHashCode( hero )
				+ " hero.belongings=" + System.identityHashCode( hero.belongings )
				+ " backpack=" + System.identityHashCode( hero.belongings.backpack )
				+ " pouch=" + System.identityHashCode( pouchOf( hero ) )
				+ " heapPouchIsSame=" + (heapPouch( h ) == pouchOf( hero ))
				+ " [" + items.toString().trim() + "]" );
	}

	private static Object heapPouch( Heap h ){
		for (Item i : h.items){
			if (i.getClass().getSimpleName().equals( "VelvetPouch" )) return i;
		}
		return null;
	}

	private static Object pouchOf( Hero hero ){
		for (com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag b : hero.belongings.getBags()){
			if (b.getClass().getSimpleName().equals( "VelvetPouch" )) return b;
		}
		return null;
	}


	private static String bagRoom( com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag bag ){
		int used = 0;
		for (Item i : bag) used++;
		return bag.getClass().getSimpleName() + " " + used + "/" + bag.capacity();
	}


	private static String bagList(){
		StringBuilder sb = new StringBuilder( "[" );
		for (com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag bag : Dungeon.hero.belongings.getBags()){
			sb.append( bag.getClass().getSimpleName() ).append( "{" );
			for (Item i : bag) sb.append( i.getClass().getSimpleName() ).append( "," );
			sb.append( "} " );
		}
		return sb.append( "]" ).toString();
	}

	private boolean moveLegal( Action action ){
		int cell = offsetCell( action.dx, action.dy );
		if (!insideMap( cell )) return false;

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


	private boolean hasProgressItem(){
		if (!config.subModeProgressOnly) return hasUsableItem();

		Hero hero = hero();
		for (Item item : slots){
			if (item == null) continue;
			if (SlotAction.isBareEquipToggle( hero, item, config.allowEquipping )) continue;
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
