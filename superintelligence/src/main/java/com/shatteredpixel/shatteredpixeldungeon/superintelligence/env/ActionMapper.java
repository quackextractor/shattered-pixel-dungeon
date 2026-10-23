package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;

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

	private final EnvConfig config;


	private final ArrayList<Item> slots = new ArrayList<>();


	private boolean zeroTimeModeExit;

	private boolean craftedMeatPie;

	public boolean craftedMeatPie(){
		return craftedMeatPie;
	}

	public void clearCraftedMeatPie(){
		craftedMeatPie = false;
	}

	public ActionMapper( EnvConfig config ){
		this.config = config;
		for (int i = 0; i < config.maxSlots; i++) slots.add( null );
	}

	private Hero hero(){
		return Dungeon.hero;
	}


	public void refreshSlots(){
		for (int i = 0; i < slots.size(); i++) slots.set( i, null );

		if (GameScene.answerableSelector() != null){
			for (Item worn : worn()){
				int empty = slots.indexOf( null );
				if (empty >= 0) slots.set( empty, worn );
			}
		}

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

	private List<Item> worn(){
		ArrayList<Item> worn = new ArrayList<>();
		for (Item item : new Item[]{ hero().belongings.weapon, hero().belongings.armor, hero().belongings.artifact,
				hero().belongings.misc, hero().belongings.ring }){
			if (item != null) worn.add( item );
		}
		return worn;
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

	public String describeSlot( int index ){
		StringBuilder sb = new StringBuilder();
		Item item = slot( index );
		sb.append( "slot " ).append( index ).append( " = " )
				.append( item == null ? "nothing" : item.getClass().getSimpleName() );
		int bound = item == null ? -1 : Dungeon.quickslot.getSlot( item );
		sb.append( ", quickslot " ).append( bound );
		sb.append( ", backpack " );
		for (int i = 0; i < Math.min( slots.size(), 8 ); i++){
			Item carried = slots.get( i );
			sb.append( i ).append( '=' )
					.append( carried == null ? "-" : carried.getClass().getSimpleName() ).append( ' ' );
		}
		return sb.toString();
	}


	public void actionMask( EnvMode mode, float[] mask ){
		for (int i = 0; i < mask.length; i++) mask[i] = 0f;

		if (mode == EnvMode.MENU){
			mask[Action.CANCEL.index] = 1f;
			if (WindowBridge.open()) mask[Action.MENU_SELECT.index] = 1f;
			return;
		}

		if (mode == EnvMode.INVENTORY && GameScene.answerableSelector() != null){
			mask[Action.CANCEL.index] = 1f;
			float[] pickable = new float[ slots.size() ];
			slotMask( pickable );
			for (float p : pickable) if (p > 0.5f) mask[Action.USE.index] = 1f;
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

		if (mode == EnvMode.ALCHEMY){
			mask[Action.CANCEL.index] = 1f;
			if (!alchemyChoices().isEmpty()) mask[Action.MENU_SELECT.index] = 1f;
			return;
		}

		if (mode == EnvMode.TARGETING){
			mask[Action.CANCEL.index] = 1f;
			return;
		}

		boolean progressUse = hasProgressItem();
		boolean interactable = Surroundings.adjacentMob()
				|| Surroundings.adjacentHeap()
				|| Surroundings.adjacentDoor()
				|| Surroundings.adjacentTransition()
				|| Surroundings.adjacentNpc()
				|| Surroundings.adjacentAlchemy();

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
				case ALCHEMY:
					mask[a.index] = (config.allowAlchemy && !alchemyChoices().isEmpty()) ? 1f : 0f;
					break;
				case MOVE_N: case MOVE_NE: case MOVE_E: case MOVE_SE:
				case MOVE_S:  case MOVE_SW: case MOVE_W:  case MOVE_NW:
					mask[a.index] = Surroundings.moveLegal( a ) ? 1f : 0f;
					break;
				case TRAVEL_STAIRS:
				case TRAVEL_EXPLORE:
					mask[a.index] = Travel.step( a, false ) >= 0 ? 1f : 0f;
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
		return Surroundings.insideMap( Surroundings.targetCell( index ) );
	}


	public void slotMask( float[] mask ){
		WndBag.ItemSelector picking = GameScene.answerableSelector();
		for (int i = 0; i < mask.length; i++){
			Item item = i < slots.size() ? slots.get( i ) : null;
			mask[i] = item != null && (picking == null || picking.itemSelectable( item )) ? 1f : 0f;
		}
	}


	public void slotMask( EnvMode mode, float[] mask ){
		if (mode == EnvMode.ALCHEMY ){
			for (int i = 0; i < mask.length; i++) mask[ i ] = 0f;
			int options = alchemyChoices().size();
			for (int i = 0; i < options && i < mask.length; i++) mask[ i ] = 1f;
			return;
		}

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


	public List<com.shatteredpixel.shatteredpixeldungeon.items.Recipe> alchemyChoices(){
		if (!config.allowAlchemy) return java.util.List.of();
		if (AlchemyAction.adjacentPot( hero(), ActionMapper.TARGET_COUNT ) < 0) return java.util.List.of();
		return AlchemyAction.recipes( hero() );
	}


	public boolean apply( Action action, int slotOrTarget ){
		if (action == null) return false;
		Hero hero = hero();
		Travel.remember();

		if (hero.resting && action != Action.REST){
			hero.resting = false;
		}

		switch (action) {
			case WAIT:
				hero.rest( false );
				return true;

			case REST:
				hero.rest( true );
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

			case TRAVEL_STAIRS:
			case TRAVEL_EXPLORE:
				int step = Travel.step( action, true );
				return step >= 0 && handleCell( step );

			default:
				break;
		}

		return handleCell( Surroundings.offsetCell( action.dx, action.dy ));
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

			case ALCHEMY:
				if (action != Action.MENU_SELECT) return false;
				if (!AlchemyAction.adjacent( hero(), AlchemyAction.adjacentPot( hero(), TARGET_COUNT ))) {
					return false;
				}
				boolean meatPie = AlchemyAction.isMeatPie(
						alchemyChoices().get( Math.min( index, alchemyChoices().size() - 1 )));
				boolean brewed = AlchemyAction.craft( hero(), index );
				if (brewed && meatPie) craftedMeatPie = true;
				return brewed;

			case SLOT:
			case INVENTORY:
				if (mode == EnvMode.INVENTORY && GameScene.answerableSelector() != null){
					return SlotAction.pick( action == Action.USE ? slot( index ) : null );
				}
				if (PickupTrace.enabled() && action == Action.DROP) PickupTrace.drop( hero(), this, index );
				return SlotAction.execute( hero(), slot( index ), action, config.allowEquipping );

			default:
				return false;
		}
	}


	private boolean resolveTarget( int index ){
		CellSelector.Listener aim = GameScene.pendingCellListener();
		GameScene.clearPendingCellListener();

		if (aim != null ){
			aim.onSelect( Surroundings.targetCell( index ) );
			return true;
		}

		Item pending = SlotAction.pendingUseItem();
		if (pending != null && pending.usesTargeting){
			SlotAction.castAt( hero(), pending, Surroundings.targetCell( index ) );
			return true;
		}
		return false;
	}


	private boolean handleCell( int cell ){
		if (!Surroundings.insideMap( cell )) return false;

		Hero hero = hero();

		if (cell != hero.pos && !Dungeon.level.adjacent( hero.pos, cell )) return false;

		if (hero.handle( cell )) {
			hero.next();
			return true;
		}
		return false;
	}

	private boolean interact( int direction ){
		if (direction > 0 && direction < TARGET_COUNT){
			int cell = Surroundings.targetCell( direction );
			if (Surroundings.interactive( cell )) return handleCell( cell );
		}
		return handleCell( Surroundings.pickInteractCell() );
	}


	public void interactMask( float[] mask ){
		for (int i = 0; i < mask.length; i++) mask[ i ] = 0f;
		if (mask.length == 0) return;

		mask[ 0 ] = 1f;
		for (int o = 1; o < TARGET_COUNT && o < mask.length; o++){
			if (Surroundings.interactive( Surroundings.targetCell( o ) )) mask[ o ] = 1f;
		}
	}

	private boolean hasUsableItem(){
		for (Item item : slots){
			if (usable( item )) return true;
		}
		return false;
	}


	private boolean usable( Item item ){
		if (item == null) return false;
		String action = SlotAction.resolveAction( hero(), item );
		return action != null && !action.equals( Item.AC_THROW );
	}


	private boolean hasProgressItem(){
		if (!config.subModeProgressOnly) return hasUsableItem();

		Hero hero = hero();
		for (Item item : slots){
			if (SlotAction.isBareEquipToggle( hero, item, config.allowEquipping )) continue;
			if (usable( item )) return true;
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

	public int offsetCell( int dx, int dy ){
		return Surroundings.offsetCell( dx, dy );
	}

	public int targetCell( int index ){
		return Surroundings.targetCell( index );
	}

	public boolean adjacentForSale(){
		return Surroundings.adjacentForSale();
	}
}
