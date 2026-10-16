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

	private static void captureIsStableWhileNothingChanges(){
		String where = "capture is stable";

		String first = Quickslots.capture();
		String second = Quickslots.capture();

		if (first.equals( second )) return;
		fail( where, "two captures with no state change in between differed: \""
				+ first + "\" then \"" + second + "\"" );
	}

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

	private static String[] namesOf( ActionMapper mapper ){
		return mapper.slots().stream()
				.map( i -> i == null ? "-" : i.getClass().getSimpleName() )
				.toArray( String[]::new );
	}

	private static void fail( String where, String what ){
		failures.add( where + ": " + what );
	}

}
