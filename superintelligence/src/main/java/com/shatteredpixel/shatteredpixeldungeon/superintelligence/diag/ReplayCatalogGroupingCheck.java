package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayCatalog;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIndex;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.dir;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.dropEveryStepField;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.flatten;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.readQuietly;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.write;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.writeAs;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.writeBack;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.writeGrouped;

public final class ReplayCatalogGroupingCheck {

	private ReplayCatalogGroupingCheck(){}

	public static void checkHeaderOnlyReadMatchesAFullRead(){
		File dir = dir( "parity" );

		File file = writeGrouped( dir, "parity-run", "parity", "DUELIST", -12.75, 3, 1234, 5, 5 );

		try {
			Replay full = ReplayIO.read( file );
			Replay header = ReplayIO.readHeader( file );

			if (full.steps.size() != 5){
				fail( "the fixture should have 5 steps, has " + full.steps.size() );
				return;
			}
			if (header.seedText.equals( full.seedText )
					&& header.heroClass.equals( full.heroClass )
					&& header.score == full.score
					&& header.depth == full.depth
					&& header.turns == full.turns
					&& header.generation == full.generation
					&& header.group.equals( full.group )
					&& header.turnLimitPerFloor == full.turnLimitPerFloor
					&& header.declaredSteps == full.steps.size()){
				return;
			}

			fail( "readHeader and read disagree: seed " + header.seedText + "/" + full.seedText
					+ " score " + header.score + "/" + full.score
					+ " depth " + header.depth + "/" + full.depth
					+ " group '" + header.group + "'/" + full.group
					+ " turns " + header.turns + "/" + full.turns );
		} catch (IOException e){
			fail( "reading the fixture threw: " + e.getMessage() );
		}
	}


	public static void checkHeaderOnlyReadAgreesOnWatchability(){
		File dir = dir( "watchable" );

		File unbound = write( dir, "UNBOUND", "DUELIST", -1.0, 1, 3 );
		Replay unboundReplay = readQuietly( unbound );
		unboundReplay.steps.get( 0 ).mode = "SLOT";
		unboundReplay.steps.get( 0 ).quickslots = "";
		writeBack( unbound, unboundReplay );

File dropped = write( dir, "DROPPED", "DUELIST", -2.0, 1, 3 );
	Replay droppedReplay = readQuietly( dropped );
	dropEveryStepField( droppedReplay );
	writeBack( dropped, droppedReplay );

		try {
			if (ReplayIO.readHeader( unbound ).wireDroppedStepFields()){
				fail( "readHeader calls a recording with real health and engine time unwatchable. A hero"
						+ " with nothing bound is a state, not a gap - and refusing one is a false positive"
						+ " that only appears on the runs where the hero happens to carry nothing." );
				return;
			}
			if (!ReplayIO.readHeader( dropped ).wireDroppedStepFields()){
				fail( "readHeader does not recognise a recording whose health, turn and inventory are all"
						+ " at their sentinels. That is the four-field wire, and it is the signature the nine"
						+ " unplayable trainer recordings carry." );
				return;
			}

			if (ReplayIO.read( unbound ).wireDroppedStepFields()
					!= ReplayIO.readHeader( unbound ).wireDroppedStepFields()
					|| ReplayIO.read( dropped ).wireDroppedStepFields()
					!= ReplayIO.readHeader( dropped ).wireDroppedStepFields()){
				fail( "the header-only scan and the full parse disagree about watchability" );
			}
		} catch (IOException e){
			fail( "reading the watchability fixtures threw: " + e.getMessage() );
		}
	}


	public static void checkTheBlurbAgreesWithItsOwnRows(){
		File corpus = dir( "blurb-corpus" );
		File trainer = dir( "blurb-trainer" );
		write( corpus, "BLURB-CORPUS", "WARRIOR", 1.0, 1, 10 );

		File good = write( trainer, "BLURB-GOOD", "WARRIOR", 2.0, 1, 20 );
		File dropped = write( trainer, "BLURB-DROPPED", "WARRIOR", 3.0, 1, 30 );

		Replay replay = readQuietly( dropped );
		dropEveryStepField( replay );
		writeBack( dropped, replay );
		if (good == null || dropped == null) return;

		String listing = new ReplayCatalog().scan( List.of( corpus, trainer )).render( true );

		if (listing.contains( "every one of them is watchable" )){
			fail( "the trainer section's header still claims every recording is watchable while the same"
					+ " section lists recordings marked 'not watchable'. The header is the part a skimming"
					+ " reader takes away, so it is the half that has to be true." );
			return;
		}
		if (!listing.contains( "1 of 2, marked 'not watchable'" )){
			fail( "the trainer section's header does not count the recordings it cannot play, so it says"
					+ " nothing about them. Expected it to report '1 of 2'." );
			return;
		}

		int marked = 0;
		for (String line : listing.split( "\n" )){
			String trimmed = line.trim();
			if (trimmed.isEmpty() || !trimmed.contains( "not watchable" )) continue;
			if (trimmed.split( "\\s+" )[ 0 ].matches( "\\d+" )) marked++;
		}
		if (marked != 1){
			fail( "the fixture marks one recording unwatchable and the list marks " + marked
					+ ", so the header's count and the rows disagree about the same section." );
		}
	}


	public static void checkDeduplicatesTheSameSeed(){
		File first = dir( "dedupe-a" );
		File second = dir( "dedupe-b" );
		write( first,  "SHARED-SEED", "WARRIOR", 1.0, 1, 10 );
		write( second, "SHARED-SEED", "WARRIOR", 2.0, 1, 20 );

		ReplayCatalog catalog = new ReplayCatalog().scan( List.of( first, second ) );

		if (catalog.entries().size() != 1){
			fail( "the same seed in two directories should list once, listed "
					+ catalog.entries().size() );
			return;
		}
		if (catalog.entries().get( 0 ).score != 1.0){
			fail( "the earlier directory should win, got score " + catalog.entries().get( 0 ).score );
		}
	}


	public static void checkAmbiguousSeedIsRefusedNotGuessed(){
		File first = dir( "ambig-a" );
		File second = dir( "ambig-b" );
		write( first,  "TWIN-SEED", "WARRIOR", 1.0, 1, 10 );
		write( second, "TWIN-SEED", "WARRIOR", 2.0, 1, 900 );

		ReplayCatalog catalog = new ReplayCatalog().scan( List.of( first, second ));

		if (catalog.ambiguous( "TWIN-SEED" ).size() != 2){
			fail( "a seed recorded in two directories should be reported as ambiguous, reported "
					+ catalog.ambiguous( "TWIN-SEED" ).size() );
			return;
		}

		if (catalog.byName( "TWIN-SEED" ) != null){
			fail( "a bare seed name that matches " + catalog.ambiguous( "TWIN-SEED" ).size()
					+ " recordings resolved to one of them. Guessing here hands back a different"
					+ " recording than the one being asked about." );
			return;
		}

		if (catalog.byName( "TWIN-SEED.replay" ) != null){
			fail( "a bare filename that exists in two directories resolved to one of them. Two runs"
					+ " writing the same seed produce the same filename, so the filename does not"
					+ " disambiguate and guessing here returns an arbitrary one." );
			return;
		}

		String listing = catalog.render( true );
		if (!listing.contains( "share this seed" )){
			fail( "the listing does not flag a seed recorded more than once, so refusing to resolve it"
					+ " looks arbitrary rather than informative." );
			return;
		}
		if (!listing.contains( "ambig-a" ) || !listing.contains( "ambig-b" )){
			fail( "the listing does not say which directories hold the copies, so a person told the name"
					+ " is ambiguous has no way to act on it." );
		}
	}


	public static void checkIndexedDirectoriesAreDiscovered(){
		File dir = dir( "indexed" );
		write( dir, "INDEXED-SEED", "MAGE", 7.0, 1, 40 );

		ReplayIndex.record( dir );

		if (!ReplayIndex.read().contains( dir.getAbsoluteFile() )){
			fail( "a directory recorded in the index is not read back out of it, so a run's own"
					+ " recordings would still be unfindable." );
			return;
		}

		ReplayIndex.record( dir );
		int count = 0;
		for (File known : ReplayIndex.read()){
			if (known.equals( dir.getAbsoluteFile() )) count++;
		}
		if (count != 1){
			fail( "a directory recorded twice appears " + count + " times in the index." );
			return;
		}

		ReplayCatalog catalog = new ReplayCatalog().scan( ReplayIndex.read() );
		if (catalog.byName( "INDEXED-SEED" ) == null){
			fail( "a recording in an indexed directory is still not resolvable, so indexing it changed"
					+ " nothing observable." );
		}
	}


	public static void checkTheSameDirectoryUnderTwoSpellingsIsOneDirectory(){
		File dir = dir( "spelled" );
		write( dir, "SPELLED-SEED", "ROGUE", 5.0, 1, 60 );

		List< File > both = new ArrayList<>();
		both.add( dir.getAbsoluteFile() );
		both.add( new File( dir.getPath() + File.separator + "." ).getAbsoluteFile() );

		ReplayCatalog catalog = new ReplayCatalog().scan( both );

		List< ReplayCatalog.Entry > copies = catalog.copies().get( "SPELLED-SEED" );
		int found = copies == null ? 0 : copies.size();
		if (found != 1){
			fail( "the same directory passed under two spellings produced " + found + " entries for one"
					+ " recording instead of 1, so every file in it is listed twice and every seed reports"
					+ " that it was recorded N times. The trainer's default destination resolved to"
					+ " 'superintelligence/./trainer-replays' while the catalogue's own default spelled it"
					+ " without the '.', and the deduplication compared paths as strings." );
			return;
		}

		ReplayCatalog.Entry entry = catalog.byName( "SPELLED-SEED" );
		if (entry == null || !entry.watchable){
			fail( "the surviving entry for a duplicated directory is not the watchable one, so which"
					+ " spelling came first decides whether the listing calls a recording watchable." );
		}
	}


	public static void checkASharedSeedResolvesByFileName(){
		File dir = dir( "shared" );

		File first  = write( dir, "SHARED-SEED", "WARRIOR", 1.0, 1, 10 );
		File second = writeAs( dir, "SHARED-SEED", "SHARED-SEED-r2", "WARRIOR", 2.0, 1, 20 );
		File third  = writeAs( dir, "SHARED-SEED", "SHARED-SEED-r3", "WARRIOR", 3.0, 1, 30 );
		if (first == null || second == null || third == null) return;

		ReplayCatalog catalog = new ReplayCatalog().scan( List.of( dir ));

		if (catalog.byName( "SHARED-SEED" ) != null){
			fail( "a bare seed that three files carry resolved to one of them, so it still guesses."
					+ " Deduping the listing must not dedupe the resolver into a confident answer." );
			return;
		}

		for (String name : new String[]{ "SHARED-SEED-r2.replay", "SHARED-SEED-r3.replay", "SHARED-SEED-r2" }){
			ReplayCatalog.Entry entry = catalog.byName( name );
			if (entry == null){
				fail( "'" + name + "' does not resolve, and the listing told the reader to type the file"
						+ " name to pick one of several recordings. A file name names exactly one file;"
						+ " it fails only because byName searched the seed-deduplicated list." );
				return;
			}
			if (!entry.file.getName().equalsIgnoreCase( name.endsWith( ".replay" ) ? name : name + ".replay" )){
				fail( "'" + name + "' resolved to " + entry.file.getName() + " rather than to itself." );
				return;
			}
		}

		String listing = catalog.render( true );
		if (listing.contains( "pass a full path" )){
			fail( "the listing still tells the reader to pass a full path, which is not what byName"
					+ " takes and is far more than picking one recording out of a list requires." );
		}
		if (!listing.contains( "Type the file name" )){
			fail( "the listing does not say that a file name is what to type, so a reader told a seed is"
					+ " ambiguous has no instruction to follow." );
		}
	}


	public static void checkAGroupIsItsOwnSection(){
		File corpus = dir( "group-corpus" );
		File trainer = dir( "group-trainer" );

		write( corpus, "GROUP-CORPUS", "WARRIOR", 3.0, 1, 40 );
		write( trainer, "UNGROUPED", "WARRIOR", -10.0, 1, 60 );
		writeGrouped( trainer, "run-a-60", "RUN-A-ONE", "MAGE", -20.0, 2, 80 );
		writeGrouped( trainer, "run-a-60", "RUN-A-TWO", "ROGUE", -40.0, 1, 70 );
		writeGrouped( trainer, "run-b-60", "RUN-B-ONE", "MAGE", -30.0, 1, 90 );

		ReplayCatalog catalog = new ReplayCatalog().scan( List.of( corpus, trainer ));
		Map<String, Map<String, List<ReplayCatalog.Entry>>> sections = catalog.sections();

		String runA = ReplayCatalog.GROUP_PREFIX + "RUN-A-60";
		if (!sections.containsKey( runA ) || !sections.containsKey( ReplayCatalog.GROUP_PREFIX + "RUN-B-60" )){
			fail( "the listing has no section per group, so two training runs are interleaved in one"
					+ " ranking and \"what did this run do\" is answered with every run on the machine."
					+ " Sections present: " + sections.keySet() );
			return;
		}

		List<ReplayCatalog.Entry> inRunA = flatten( sections.get( runA ));
		if (inRunA.size() != 2){
			fail( "group run-a-60 holds " + inRunA.size() + " recordings and its two fixtures wrote 2, so"
					+ " a group's own rows are landing in another section" );
			return;
		}

		for (ReplayCatalog.Entry entry : inRunA){
			if (!"run-a-60".equals( entry.group )){
				fail( entry.label() + " is listed under group run-a-60 but carries '"
						+ entry.group + "', so the section is not reading the header" );
				return;
			}
		}

		if (flatten( sections.get( ReplayCatalog.SECTION_TRAINER )).size() != 1){
			fail( "the ungrouped training section does not hold exactly the one ungrouped fixture, so a"
					+ " grouped recording is being listed as ungrouped as well" );
			return;
		}
		if (flatten( sections.get( ReplayCatalog.SECTION_CORPUS )).size() != 1){
			fail( "the reference corpus section holds more than its own fixture, so a trainer recording"
					+ " is being listed as a fixture that reproduces" );
			return;
		}

		List<String> order = new ArrayList<>( sections.keySet() );
		List<String> expected = List.of( ReplayCatalog.SECTION_CORPUS, ReplayCatalog.SECTION_TRAINER,
				runA, ReplayCatalog.GROUP_PREFIX + "RUN-B-60" );
		if (!order.equals( expected )){
			fail( "sections are ordered " + order + " and the order has to be corpus, ungrouped, then"
					+ " groups by name" );
			return;
		}

		String listing = catalog.render( true );
		if (!listing.contains( "\n  " + runA + " " ) || !listing.contains( "group run-a-60" )){
			fail( "the listing does not print a heading and a blurb for group run-a-60, so the rows are"
					+ " there without saying which run they belong to" );
			return;
		}

		List<ReplayCatalog.Entry> ordered = catalog.inListingOrder();
		for (int i = 0; i < ordered.size(); i++){
			ReplayCatalog.Entry byIndex = catalog.byNumber( i + 1 );
			if (byIndex == null || !byIndex.file.equals( ordered.get( i ).file )){
				fail( "number " + ( i + 1 ) + " does not resolve to the recording listed at that"
						+ " position once a group exists, so a number read off the screen plays a"
						+ " different file" );
				return;
			}
		}
	}


	public static void checkSubdirectoriesAreScannedAndRejectedIsNot(){
		File dir = dir( "nested" );
		write( dir, "TOP-LEVEL", "WARRIOR", 1.0, 1, 10 );
		writeGrouped( dir, "run-a", "NESTED-ONE", "MAGE", 2.0, 1, 20 );
		writeGrouped( dir, "run-a", "NESTED-TWO", "MAGE", 3.0, 1, 30 );
		write( dir( "nested/rejected" ), "REJECTED-ONE", "MAGE", 4.0, 1, 40 );

		ReplayCatalog catalog = new ReplayCatalog().scan( List.of( dir ));

		if (catalog.byName( "NESTED-ONE" ) == null || catalog.byName( "NESTED-TWO" ) == null){
			fail( "a recording written into a group's subdirectory is not listed, so --replay-group files"
					+ " runs into a directory the catalog does not look in and the picker cannot see any of"
					+ " them" );
			return;
		}

		if (catalog.byName( "REJECTED-ONE" ) != null){
			fail( "a recording in rejected/ is listed as a run to watch. That directory is where a corpus"
					+ " rebuild parks what it could not reproduce, and offering it is a dead end" );
			return;
		}

		if (catalog.entries().size() != 3){
			fail( "expected the three listed recordings and found " + catalog.entries().size()
					+ ", so the subdirectory scan is picking up something else or missing something" );
		}
	}
}