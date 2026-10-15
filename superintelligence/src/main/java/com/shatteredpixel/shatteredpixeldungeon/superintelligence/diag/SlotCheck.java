package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Quickslots;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A recorded slot index must resolve to the same item it was recorded against.
 *
 * <pre>gradle :superintelligence:slotcheck</pre>
 *
 * <p>A slot index in a recording means nothing on its own. {@code ActionMapper.refreshSlots} puts every
 * quickslot-bound item into the slot it is bound to, then fills the remaining slots from the backpack in
 * order, so which item a given index names depends entirely on the bindings in force when the index was
 * chosen.
 *
 * <p>That made a recorded {@code DROP} ambiguous between two environments that agreed on everything else.
 * On KXY-JHB-LXK the trainer had the Waterskin bound to quickslot 1 and the viewer had no bindings at
 * all, so slot 0 named the VelvetPouch in one and the Waterskin in the other. The viewer dropped the bag,
 * which took {@code Backpack.capacity} from 21 to 20, and every later {@code INTERACT} then resolved
 * against a different inventory. The run diverged at step 46, and nothing about the two worlds differed
 * except that one binding.
 *
 * <p>Checks that capture and restore reproduce slot resolution exactly, including after bindings change
 * mid-run, and that a capture with nothing bound restores to nothing rather than leaving a stale binding
 * in place - a stale binding is what made slot 0 resolve differently in the first place.
 */
public class SlotCheck {

	private static final int CHECKS = 6;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-slotcheck" ));
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );


		env.reset( "SLOTCHECK-A", HeroClass.DUELIST );



		restoreLeavesAloneABindingNoSlotIndexCanName();
		capturedBindingsResolveTheSameSlotsAfterRestore();
		restoreClearsBindingsTheRecordingDidNotMention();
		captureIsStableWhileNothingChanges();
		restoreAppliesBindingsThatDifferFromTheLiveOnes();
		bindingsSurviveARoundTripThroughTheFileFormat();

		if (failures.isEmpty()){
			System.out.println( "[OK]     slot resolution: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  slot resolution: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	/** Binds an item, captures, clears every binding, restores, and requires the same slots. */
	private static void capturedBindingsResolveTheSameSlotsAfterRestore(){
		String where = "restore reproduces captured bindings";

		Item bound = firstItem();
		if (bound == null){
			fail( where, "the hero carries nothing to bind" );
			return;
		}

		Dungeon.quickslot.setSlot( 1, bound );
		ActionMapper mapper = new ActionMapper( new EnvConfig() );
		mapper.refreshSlots();

		String[] before = namesOf( mapper );
		String captured = Quickslots.capture();

		Dungeon.quickslot.reset();
		mapper.refreshSlots();
		Quickslots.restore( captured );
		mapper.refreshSlots();
		String[] after = namesOf( mapper );

		if (java.util.Arrays.equals( before, after )) return;

		fail( where, "slot resolution changed across capture and restore: before ["
				+ String.join( " ", before ) + "], after [" + String.join( " ", after ) + "]" );
	}

	/**
	 * Restore clears first, so an item bound in the live game but absent from the recording stops
	 * claiming a slot. Left in place, it takes the slot the recording meant for something else.
	 */
	private static void restoreClearsBindingsTheRecordingDidNotMention(){
		String where = "restore clears bindings the recording omits";

		Item bound = firstItem();
		if (bound == null){
			fail( where, "the hero carries nothing to bind" );
			return;
		}

		Dungeon.quickslot.setSlot( 1, bound );
		Quickslots.restore( "" );

		if (Dungeon.quickslot.getSlot( bound ) == -1) return;
		fail( where, bound.getClass().getSimpleName()
				+ " kept quickslot " + Dungeon.quickslot.getSlot( bound )
				+ " after restoring a capture that named no bindings" );
	}

	/** Two captures with nothing changed between them must be identical. */
	private static void captureIsStableWhileNothingChanges(){
		String where = "capture is stable";

		String first = Quickslots.capture();
		String second = Quickslots.capture();

		if (first.equals( second )) return;
		fail( where, "two captures with no state change in between differed: \""
				+ first + "\" then \"" + second + "\"" );
	}

	/**
	 * Restoring must actually apply the capture, not merely accept it.
	 *
	 * <p>Applied in the opposite direction from the live state, so a restore that quietly did nothing
	 * would leave the original bindings showing through.
	 */
	private static void restoreAppliesBindingsThatDifferFromTheLiveOnes(){
		String where = "restore applies bindings that differ from the live ones";

		Item bound = firstItem();
		if (bound == null){
			fail( where, "the hero carries nothing to bind" );
			return;
		}

		Dungeon.quickslot.reset();
		String captured = Quickslots.capture();

		Dungeon.quickslot.setSlot( 4, bound );
		if (Dungeon.quickslot.getSlot( bound ) != 4){
			fail( where, "could not set up the differing live binding" );
			return;
		}

		Quickslots.restore( captured );
		int slot = Dungeon.quickslot.getSlot( bound );

		if (captured.isEmpty()){
			if (slot == -1) return;
			fail( where, "restoring an empty capture left " + bound.getClass().getSimpleName()
					+ " bound to " + slot );
			return;
		}

		if (slot == -1){
			fail( where, "restoring \"" + captured + "\" left " + bound.getClass().getSimpleName()
					+ " unbound, though the capture names it" );
		}
	}



	/**
	 * A binding on an equipped item is the game's to keep, not the recording's to overwrite.
	 *
	 * <p>{@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Quickslots#capture}
	 * walks the backpack, because that is the set {@code ActionMapper.refreshSlots} walks - so a quickslot
	 * bound to something <em>equipped</em> is not in the recording and cannot be. Four of the five hero
	 * classes bind their starting weapon that way: a DUELIST's {@code Rapier}, a MAGE's
	 * {@code MagesStaff}, a ROGUE's cloak, a CLERIC's tome.
	 *
	 * <p>{@code restore} used to clear every binding, which quietly wiped those as well. It stayed
	 * harmless for exactly as long as the hero never took the item off: unequip it and it lands in the
	 * backpack <em>still bound</em> in the trainer's world and unbound in the viewer's, and from that
	 * moment every slot index names a different item. So the rule is that restore clears the bindings a
	 * slot index can name and leaves the rest alone.
	 *
	 * <p><b>An earlier version of this case asserted the wrong thing and the measurement said so.</b> It
	 * wiped the whole quickslot and required the slots to resolve identically, reasoning that an
	 * equipped item's binding cannot matter because {@code refreshSlots} never reads it. It does matter,
	 * indirectly: a bound item is placed in its slot before the unbound ones fill from the front, so
	 * which item lands where depends on every binding, including ones on items no index can name.
	 *
	 * <p>   slots with bindings  [Food ThrowingSpike Waterskin VelvetPouch]
	 * <p>   slots without        [ThrowingSpike Food Waterskin VelvetPouch]
	 *
	 * <p>So the property is asserted directly instead: restoring a capture that names nothing must still
	 * leave an equipped item's binding alone while clearing a carried item's.
	 */
	private static void restoreLeavesAloneABindingNoSlotIndexCanName(){
		String where = "restore leaves alone a binding no slot index can name";

		Item equipped = Dungeon.hero.belongings.weapon();
		if (equipped == null || Dungeon.quickslot.getSlot( equipped ) < 0){
			fail( where, "the hero carries no equipped weapon bound to a quickslot, so there is no"
					+ " unaddressable binding to test against. A case that cannot reach its own case is"
					+ " not a check." );
			return;
		}

		Item carried = firstItem();
		if (carried == null){
			fail( where, "the hero carries nothing in the backpack, so there is no addressable binding"
					+ " to test the other half of the rule against" );
			return;
		}
		Dungeon.quickslot.setSlot( 4, carried );
		int equippedSlot = Dungeon.quickslot.getSlot( equipped );
		int carriedSlot = Dungeon.quickslot.getSlot( carried );


		Quickslots.restore( "" );

		if (Dungeon.quickslot.getSlot( carried ) != -1){
			fail( where, "restoring a capture that named no bindings left the carried "
					+ carried.getClass().getSimpleName() + " bound to quickslot "
					+ Dungeon.quickslot.getSlot( carried ) + ". A stale binding takes the slot the"
					+ " recording meant for something else." );
			return;
		}

		if (Dungeon.quickslot.getSlot( equipped ) != equippedSlot){
			fail( where, "restoring a capture that named no bindings changed the equipped "
					+ equipped.getClass().getSimpleName() + " from quickslot " + equippedSlot + " to "
					+ Dungeon.quickslot.getSlot( equipped ) + ". Nothing in a recording describes it -"
					+ " capture walks the backpack - so wiping it is a loss with no way to repair it,"
					+ " and unequipping the item afterwards would make every slot index name something"
					+ " else." );
			return;
		}


		Dungeon.quickslot.setSlot( carriedSlot, carried );

		System.out.println( "  restore leaves the equipped " + equipped.getClass().getSimpleName()
				+ "'s binding to quickslot " + equippedSlot + " alone while clearing a carried one" );
	}

	/**
	 * Bindings must survive being written to a replay file and read back.
	 *
	 * <p>Checked because the capture is the fix and the file is the transport: a capture that survives in
	 * memory but is dropped or mangled by the reader leaves the divergence exactly where it was, and
	 * nothing else here would notice.
	 */
	private static void bindingsSurviveARoundTripThroughTheFileFormat(){
		String where = "bindings survive a replay round trip";



		String captured = "Waterskin:1,VelvetPouch:0";

		String read;
		try {
			read = roundTrip( captured ).steps.get( 0 ).quickslots;
		} catch (IOException e){
			fail( where, "round trip threw " + e );
			return;
		}

		if (captured.equals( read )) return;
		fail( where, "wrote \"" + captured + "\" and read back \"" + read + "\"" );
	}

	/** Round-trips one capture through the writer and reader, for the file-format check. */
	private static Replay roundTrip( String captured ) throws IOException {
		Replay replay = new Replay();
		replay.add( "DROP", 0, EnvMode.SLOT.name(), 100, 0 );
		replay.steps.get( 0 ).quickslots = captured;

		File file = File.createTempFile( "slotcheck", ".dat" );
		file.deleteOnExit();
		ReplayIO.write( replay, file );

		Replay read = ReplayIO.read( file );
		file.delete();
		return read;
	}

	private static Item firstItem(){
		for (Item item : Dungeon.hero.belongings.backpack){
			return item;
		}
		return null;
	}

	/** The item class each slot resolves to, or "-" for an empty slot. */
	private static String[] namesOf( ActionMapper mapper ){
		return mapper.slots().stream()
				.map( i -> i == null ? "-" : i.getClass().getSimpleName() )
				.toArray( String[]::new );
	}

	private static void fail( String where, String what ){
		failures.add( where + ": " + what );
	}

}
