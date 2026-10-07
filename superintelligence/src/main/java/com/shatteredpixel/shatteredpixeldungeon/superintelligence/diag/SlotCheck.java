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

	private static final int CHECKS = 5;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-slotcheck" ));
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( "SLOTCHECK-A", HeroClass.WARRIOR );

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

	// --------------------------------------------------------------------------- helpers

	/**
	 * Bindings must survive being written to a replay file and read back.
	 *
	 * <p>Checked because the capture is the fix and the file is the transport: a capture that survives in
	 * memory but is dropped or mangled by the reader leaves the divergence exactly where it was, and
	 * nothing else here would notice.
	 */
	private static void bindingsSurviveARoundTripThroughTheFileFormat(){
		String where = "bindings survive a replay round trip";

		//Fixed rather than taken from the live hero, so the check still holds if the hero happens to
		//carry nothing bound. Two entries, since a single one would not exercise the separator.
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
