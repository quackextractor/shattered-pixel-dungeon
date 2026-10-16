package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayCatalog;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIndex;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;


public class ReplayCatalogCheck {

	private static final List< String > failures = new ArrayList<>();

	private static final int CHECKS = 19;

	private static File workDir;

	public static void main( String[] args ){
		try {
			workDir = Files.createTempDirectory( "spd-replaycheck" ).toFile();
		} catch (IOException e){
			System.err.println( "[ERROR] could not create a scratch directory: " + e.getMessage() );
			System.exit( 2 );
			return;
		}

		try {
			checkRankedByScoreWithinAGroup();
			checkGroupedByHeroClass();
			checkDepthBreaksAScoreTie();
			checkDamagedFilesAreListedNotHidden();
			checkDamagedFilesSortLast();
			checkLookupBySeedWithoutExtension();
			checkLookupByFilenameWithExtension();
			checkLookupIsCaseInsensitiveAndAcceptsAPath();
			checkHeaderOnlyReadMatchesAFullRead();
			checkHeaderOnlyReadAgreesOnWatchability();
			checkDeduplicatesTheSameSeed();
			checkAmbiguousSeedIsRefusedNotGuessed();
			checkIndexedDirectoriesAreDiscovered();
			checkASharedSeedResolvesByFileName();
			checkTheBlurbAgreesWithItsOwnRows();
			checkTheSameDirectoryUnderTwoSpellingsIsOneDirectory();
			checkSectionsSplitByProvenance();
		checkDepthIsAReadableColumn();
		checkAGroupIsItsOwnSection();
		checkSubdirectoriesAreScannedAndRejectedIsNot();
		} finally {
			deleteTree( workDir );
		}

		if (failures.isEmpty()){
			System.out.println( "[OK]     replay catalog: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  replay catalog: " + failures.size() + " of "
					+ CHECKS + " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}


	private static void checkSectionsSplitByProvenance(){
		File corpus = dir( "sections-corpus" );
		File trainer = dir( "sections-trainer" );

		write( corpus, "CORPUS-ONE", "WARRIOR", 10.0, 1, 100, 1, 1 );
		write( corpus, "CORPUS-TWO", "MAGE", 5.0, 1, 100, 1, 1 );
		write( trainer, "TRAINER-ONE", "WARRIOR", -40.0, 2, 300, 1, 1 );
		write( trainer, "TRAINER-TWO", "CLERIC", -90.0, 1, 500, 1, 1 );

		ReplayCatalog catalog = new ReplayCatalog().scan( List.of( corpus, trainer ));

		Map<String, Map<String, List<ReplayCatalog.Entry>>> sections = catalog.sections();
		if (sections == null || !sections.containsKey( ReplayCatalog.SECTION_CORPUS )
				|| !sections.containsKey( ReplayCatalog.SECTION_TRAINER )){
			fail( "the listing does not split into a corpus section and a training section, so a trainer"
					+ " recording is indistinguishable from a fixture that reproduces." );
			return;
		}

		List<ReplayCatalog.Entry> inCorpus = flatten( sections.get( ReplayCatalog.SECTION_CORPUS ));
		List<ReplayCatalog.Entry> inTrainer = flatten( sections.get( ReplayCatalog.SECTION_TRAINER ));

		if (inCorpus.size() != 2 || inTrainer.size() != 2 ){
			fail( "expected 2 recordings in each section and found " + inCorpus.size() + " and "
					+ inTrainer.size() );
			return;
		}

		boolean corpusNamed = false, trainerNamed = false;
		for (ReplayCatalog.Entry entry : inCorpus) corpusNamed |= entry.label().startsWith( "CORPUS" );
		for (ReplayCatalog.Entry entry : inTrainer) trainerNamed |= entry.label().startsWith( "TRAINER" );
		if (!corpusNamed || !trainerNamed){
			fail( "the first --dir populates " + ReplayCatalog.SECTION_CORPUS + " and the second"
					+ " populates " + ReplayCatalog.SECTION_TRAINER + ", so a section does not describe"
					+ " where its recordings came from" );
			return;
		}

		String listing = catalog.render( true );
		List<ReplayCatalog.Entry> ordered = catalog.inListingOrder();
		if (ordered.size() != 4){
			fail( "the listing has " + ordered.size() + " entries, so the numbering cannot span"
					+ " both sections" );
			return;
		}

		for (int i = 0; i < ordered.size(); i++){
			ReplayCatalog.Entry byIndex = catalog.byNumber( i + 1 );
			if (byIndex == null || !byIndex.file.equals( ordered.get( i ).file )){
				fail( "number " + ( i + 1 ) + " does not resolve to the recording listed at that"
						+ " position, so a number read off the screen plays a different file" );
				return;
			}

			if (!listing.contains( String.format( "\n     %2d  %s", i + 1, ordered.get( i ).label() ))){
				fail( "the listing does not print number " + ( i + 1 ) + " beside "
						+ ordered.get( i ).label() + ", so the two views of the order have drifted" );
				return;
			}
		}

		if (!listing.contains( ReplayCatalog.SECTION_CORPUS )
				|| !listing.contains( ReplayCatalog.SECTION_TRAINER )){
			fail( "the rendered listing does not print both section headings" );
			return;
		}

		File healthy = write( trainer, "TRAINER-HEALTHY", "MAGE", -30.0, 1, 100, 1, 1 );
		Replay healthyReplay = ReplayIOReadQuietly( healthy );
		healthyReplay.steps.get( 0 ).mode = "SLOT";
		healthyReplay.steps.get( 0 ).quickslots = "";
		writeBack( healthy, healthyReplay );

		File dropped = write( trainer, "TRAINER-DROPPED", "MAGE", -35.0, 1, 100, 1, 1 );
		Replay droppedReplay = ReplayIOReadQuietly( dropped );
		for (Replay.Step step : droppedReplay.steps){
			step.quickslots = "";
			step.heroHp = -1;
			step.turn = -1f;
			step.inventory = "";
		}
		writeBack( dropped, droppedReplay );

		ReplayCatalog relisted = new ReplayCatalog().scan( List.of( corpus, trainer ));
		String rows = relisted.render( true );

		if (!rowFor( rows, "TRAINER-DROPPED" ).contains( "not watchable" )){
			fail( "a training recording written by the four-field wire is listed without saying so, so"
					+ " selecting it still ends in a halt with nothing on screen explaining it" );
			return;
		}
		if (rowFor( rows, "TRAINER-HEALTHY" ).contains( "not watchable" )){
			fail( "a training recording that carries health and engine time is listed as unwatchable;"
					+ " that sentence is now per file, so it is no longer merely harmless - it tells a"
					+ " reader not to watch the recordings the fixed wire produced" );
			return;
		}
	}


	private static String rowFor( String listing, String label ){
		for (String line : listing.split( "\n" )){
			if (line.contains( label )) return line;
		}
		return "";
	}


private static void checkDepthIsAReadableColumn(){
	File dir = dir( "depth-column" );
	write( dir, "SHALLOW", "WARRIOR", 1.0, 1, 40 );
	write( dir, "DEEP", "DUELIST", -2.0, 3, 40 );

	ReplayCatalog catalog = new ReplayCatalog().scan( List.of( dir ));
	String listing = catalog.render( true );

	if (!listing.contains( "depth" )){
		fail( "the listing has no 'depth' heading, so the deepest floor a run reached is a value with"
				+ " nowhere to sit. It was printed inline before and read as a footnote." );
		return;
	}

	if (!rowFor( listing, "DEEP" ).contains( "d3" )){
		fail( "the recording that reached floor 3 does not show d3 on its own row: \""
				+ rowFor( listing, "DEEP" ) + "\"" );
		return;
	}

	String header = listing.contains( "depth" ) ? listing : "";
	if (!header.contains( "turns" )){
		fail( "the listing has a depth heading but no turns heading, so the two columns cannot be told"
				+ " apart" );
		return;
	}

	if (!listing.contains( "recorded" )){
		fail( "the listing has no 'recorded' heading, so a fresh training run's output cannot be told"
				+ " from yesterday's" );
		return;
	}

	String deepRow = rowFor( listing, "DEEP" );
	if (deepRow.split( "\\s+" ).length < 6 ){
		fail( "the recording row has fewer cells than the listing has columns: \"" + deepRow
				+ "\". A column that silently loses its cell is worse than no column." );
		return;
	}
	String[] deepCells = deepRow.trim().split( "\\s+" );
	if ( !deepCells[ 4 ].matches( "\\d\\d-\\d\\dT\\d\\d:\\d\\d" ) ){
		fail( "the recorded-at cell is \"" + deepCells[ 4 ] + "\" where a MM-ddTHH:mm timestamp"
				+ " belongs: \"" + deepRow + "\". No space inside it, or it is two cells and the column"
				+ " cannot be read or asserted on as one." );
		return;
	}

	String shallow = rowFor( listing, "SHALLOW" );
	String deep = rowFor( listing, "DEEP" );
	int shallowAt = depthColumnIn( shallow );
	int deepAt = depthColumnIn( deep );
	if (shallowAt < 0 || deepAt < 0){
		fail( "could not locate the depth cell in both rows: [\"" + shallow + "\"] [\"" + deep + "\"]" );
		return;
	}
	if (shallowAt != deepAt){
		fail( "the depth cell sits at column " + shallowAt + " on one row and " + deepAt
				+ " on another, so the column does not line up: [\"" + shallow + "\"] vs [\"" + deep + "\"]" );
	}
}


private static int depthColumnIn( String row ){
	String[] cells = row.trim().split( "\\s+" );

	if (cells.length < 4) return -1;
	String cell = cells[ 3 ];
	return cell.matches( "d\\d+" ) ? row.indexOf( cell ) : -1;
}

	private static List<ReplayCatalog.Entry> flatten( Map<String, List<ReplayCatalog.Entry>> groups ){
		List<ReplayCatalog.Entry> all = new ArrayList<>();
		for (List<ReplayCatalog.Entry> list : groups.values()) all.addAll( list );
		return all;
	}


	private static void checkRankedByScoreWithinAGroup(){
		File dir = dir( "rank" );
		write( dir, "low",  "WARRIOR", -3.5, 1, 12 );
		write( dir, "high", "WARRIOR", 44.0, 2, 900 );
		write( dir, "mid",  "WARRIOR", 8.4,  1, 65 );

		List<ReplayCatalog.Entry> group = new ReplayCatalog().scan( List.of( dir ) )
				.grouped().get( "WARRIOR" );

		if (group.size() != 3){
			fail( "expected 3 WARRIOR recordings, got " + group.size() );
			return;
		}

		String[] expected = { "high", "mid", "low" };
		double[] scores = { 44.0, 8.4, -3.5 };

		for (int i = 0; i < expected.length; i++){
			if (!group.get( i ).label().equals( expected[ i ] )){
				fail( "rank order wrong at position " + i + ": got " + group.get( i ).label()
						+ ", expected " + expected[ i ] + " (scores should descend: 44.0, 8.4, -3.5)" );
				return;
			}
			if (group.get( i ).score != scores[ i ] ){
				fail( "score did not survive the header read at position " + i
						+ ": got " + group.get( i ).score + ", expected " + scores[ i ] );
				return;
			}
		}
	}


	private static void checkGroupedByHeroClass(){
		File dir = dir( "group" );
		write( dir, "w-warrior",  "WARRIOR", 10.0, 1, 100 );
		write( dir, "h-strong",   "HUNTRESS", 1.0, 1, 100 );
		write( dir, "h-weak",     "HUNTRESS", 90.0, 1, 100 );
		write( dir, "r-rogue",    "ROGUE", 50.0, 1, 100 );

		Map<String, List<ReplayCatalog.Entry>> groups = new ReplayCatalog()
				.scan( List.of( dir ) ).grouped();

		if (groups.get( "WARRIOR" ).size() != 1){
			fail( "WARRIOR should hold 1 recording, holds " + groups.get( "WARRIOR" ).size() );
		}
		if (groups.get( "HUNTRESS" ).size() != 2){
			fail( "HUNTRESS should hold 2 recordings, holds " + groups.get( "HUNTRESS" ).size() );
		}
		if (groups.get( "ROGUE" ).size() != 1){
			fail( "ROGUE should hold 1 recording, holds " + groups.get( "ROGUE" ).size() );
		}

		if (groups.get( "HUNTRESS" ).get( 0 ).label().equals( "h-weak" ) == false){
			fail( "HUNTRESS group is not ranked by score internally" );
		}
	}


	private static void checkDepthBreaksAScoreTie(){
		File dir = dir( "tie" );
		write( dir, "shallow", "WARRIOR", 10.0, 1, 50 );
		write( dir, "deep",    "WARRIOR", 10.0, 4, 300 );

		List<ReplayCatalog.Entry> group = new ReplayCatalog().scan( List.of( dir ) )
				.grouped().get( "WARRIOR" );

		if (group.size() != 2 || !group.get( 0 ).label().equals( "deep" )){
			fail( "a score tie should rank the deeper run first, got "
					+ (group.isEmpty() ? "nothing" : group.get( 0 ).label()) );
		}
	}


	private static void checkDamagedFilesAreListedNotHidden(){
		File dir = dir( "damaged" );
		write( dir, "whole",   "WARRIOR", 5.0, 1, 20, 5, 5 );
		write( dir, "partial", "WARRIOR", 6.0, 1, 200, 9, 4 );

		ReplayCatalog catalog = new ReplayCatalog().scan( List.of( dir ) );
		List<ReplayCatalog.Entry> group = catalog.grouped().get( "WARRIOR" );

		if (group.size() != 2){
			fail( "a truncated recording was dropped instead of listed: " + group.size()
					+ " of 2 present" );
			return;
		}

		ReplayCatalog.Entry partial = null;
		for (ReplayCatalog.Entry entry : group) if (entry.label().equals( "partial" )) partial = entry;

		if (partial == null){
			fail( "the truncated recording is missing from the group" );
			return;
		}
		if (partial.truncatedAt != 4){
			fail( "truncatedAt should be 4 (steps actually present), got " + partial.truncatedAt );
			return;
		}
		if (partial.steps != 9){
			fail( "the declared step count should still be reported as 9, got " + partial.steps );
			return;
		}
		if (!partial.damaged()){
			fail( "a truncated file should report itself as damaged" );
		}
	}


	private static void checkDamagedFilesSortLast(){
		File dir = dir( "unreadable" );
		write( dir, "good-one", "WARRIOR", 1.0, 1, 10 );
		write( dir, "good-two", "WARRIOR", 2.0, 1, 10 );

		try {
			Files.write( new File( dir, "junk.replay" ).toPath(),
					Arrays.asList( "not a replay", "junk" ), StandardCharsets.UTF_8 );
		} catch (IOException e){
			fail( "could not write the unreadable fixture: " + e.getMessage() );
			return;
		}

		ReplayCatalog catalog = new ReplayCatalog().scan( List.of( dir ) );
		Map<String, List<ReplayCatalog.Entry>> groups = catalog.grouped();

		if (catalog.entries().size() != 3){
			fail( "an unreadable file was dropped rather than listed: "
					+ catalog.entries().size() + " of 3 present" );
			return;
		}

		if (groups.get( "WARRIOR" ).size() != 2){
			fail( "an unreadable file was filed under WARRIOR, which is a guess: group holds "
					+ groups.get( "WARRIOR" ).size() + ", expected the 2 readable ones" );
			return;
		}

		List<ReplayCatalog.Entry> unknown = groups.get( "?" );
		if (unknown.size() != 1){
			fail( "expected 1 unreadable recording under ?, got " + unknown.size() );
			return;
		}

		ReplayCatalog.Entry bad = unknown.get( 0 );
		if (bad.error == null){
			fail( "an unreadable recording should carry its reason" );
			return;
		}
		if (!bad.label().equals( "junk" )){
			fail( "expected the unreadable file under ?, got " + bad.label() );
		}
		if (!bad.damaged()){
			fail( "an unreadable file should report itself as damaged" );
		}
	}


	private static void checkLookupBySeedWithoutExtension(){
		File dir = dir( "lookup-seed" );
		write( dir, "YVU-UBS-QFE", "WARRIOR", 44.0, 1, 254 );

		List<ReplayCatalog.Entry> matches = new ReplayCatalog().scan( List.of( dir ) )
				.find( "YVU-UBS-QFE" );

		if (matches.size() != 1){
			fail( "a bare seed should resolve to 1 recording, matched " + matches.size() );
		}
	}


	private static void checkLookupByFilenameWithExtension(){
		File dir = dir( "lookup-ext" );
		write( dir, "YVU-UBS-QFE", "WARRIOR", 44.0, 1, 254 );

		List<ReplayCatalog.Entry> matches = new ReplayCatalog().scan( List.of( dir ) )
				.find( "YVU-UBS-QFE.dat" );

		if (matches.size() != 1){
			fail( "a seed with .dat should resolve to 1 recording, matched " + matches.size() );
			return;
		}
		if (!matches.get( 0 ).file.getName().equals( "YVU-UBS-QFE.replay" )){
			fail( "resolved to the wrong file: " + matches.get( 0 ).file.getName() );
		}
	}


	private static void checkLookupIsCaseInsensitiveAndAcceptsAPath(){
		File dir = dir( "lookup-case" );
		write( dir, "YVU-UBS-QFE", "WARRIOR", 44.0, 1, 254 );

		ReplayCatalog catalog = new ReplayCatalog().scan( List.of( dir ) );

		if (catalog.find( "yvu-ubs-qfe" ).size() != 1){
			fail( "a lower-case seed should resolve" );
			return;
		}

		File absolute = new File( dir, "YVU-UBS-QFE.replay" ).getAbsoluteFile();
		if (catalog.find( absolute.getPath() ).size() != 1){
			fail( "an absolute path should resolve to the file it names" );
		}
	}


	private static void checkHeaderOnlyReadMatchesAFullRead(){
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


	private static void checkHeaderOnlyReadAgreesOnWatchability(){
		File dir = dir( "watchable" );

		File unbound = write( dir, "UNBOUND", "DUELIST", -1.0, 1, 3 );
		Replay unboundReplay = ReplayIOReadQuietly( unbound );
		unboundReplay.steps.get( 0 ).mode = "SLOT";
		unboundReplay.steps.get( 0 ).quickslots = "";
		writeBack( unbound, unboundReplay );

		File dropped = write( dir, "DROPPED", "DUELIST", -2.0, 1, 3 );
		Replay droppedReplay = ReplayIOReadQuietly( dropped );
		for (Replay.Step step : droppedReplay.steps){
			step.quickslots = "";
			step.heroHp = -1;
			step.turn = -1f;
			step.inventory = "";
		}
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


	private static void checkTheBlurbAgreesWithItsOwnRows(){
		File corpus = dir( "blurb-corpus" );
		File trainer = dir( "blurb-trainer" );
		write( corpus, "BLURB-CORPUS", "WARRIOR", 1.0, 1, 10 );

		File good = write( trainer, "BLURB-GOOD", "WARRIOR", 2.0, 1, 20 );
		File dropped = write( trainer, "BLURB-DROPPED", "WARRIOR", 3.0, 1, 30 );

		Replay replay = ReplayIOReadQuietly( dropped );
		for (Replay.Step step : replay.steps){
			step.quickslots = "";
			step.heroHp = -1;
			step.turn = -1f;
			step.inventory = "";
		}
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

	private static Replay ReplayIOReadQuietly( File file ){
		try {
			return ReplayIO.read( file );
		} catch (IOException e){
			fail( "could not read the fixture just written: " + e.getMessage() );
			return new Replay();
		}
	}

	private static void writeBack( File file, Replay replay ){
		try {
			ReplayIO.write( replay, file );
		} catch (IOException e){
			fail( "could not write back the fixture: " + e.getMessage() );
		}
	}


	private static void checkDeduplicatesTheSameSeed(){
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


	private static void checkAmbiguousSeedIsRefusedNotGuessed(){
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


	private static void checkIndexedDirectoriesAreDiscovered(){
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


	private static void checkTheSameDirectoryUnderTwoSpellingsIsOneDirectory(){
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


	private static void checkASharedSeedResolvesByFileName(){
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


	private static void checkAGroupIsItsOwnSection(){
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


	private static void checkSubdirectoriesAreScannedAndRejectedIsNot(){
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


	private static File writeAs( File dir, String seed, String fileStem, String hero,
			double score, int depth, int turns ){
		return writeFile( new File( dir, fileStem + ".replay" ), seed, "", hero, score, depth, turns, 1, 1 );
	}


	private static File writeGrouped( File dir, String group, String seed, String hero,
			double score, int depth, int turns ){
		return writeGrouped( dir, group, seed, hero, score, depth, turns, 1, 1 );
	}

	private static File writeGrouped( File dir, String group, String seed, String hero,
			double score, int depth, int turns, int declared, int actual ){
		File groupDir = new File( dir, group );
		if (!groupDir.mkdirs() && !groupDir.isDirectory()){
			throw new IllegalStateException( "could not create " + groupDir );
		}
		return writeFile( new File( groupDir, seed + ".replay" ), seed, group, hero,
				score, depth, turns, declared, actual );
	}

	private static File dir( String name ){
		File dir = new File( workDir, name );
		if (!dir.mkdirs() && !dir.isDirectory()){
			throw new IllegalStateException( "could not create " + dir );
		}
		return dir;
	}



	private static File write( File dir, String seed, String hero, double score,
			int depth, int turns, int declared, int actual ){
		return writeFile( new File( dir, seed + ".replay" ), seed, "", hero,
				score, depth, turns, declared, actual );
	}


	private static File writeFile( File file, String seed, String group, String hero, double score,
			int depth, int turns, int declared, int actual ){

		List<String> lines = new ArrayList<>( Arrays.asList(
				Replay.MAGIC,
				"version=" + Replay.VERSION,
				"seed=" + seed,
				"hero=" + hero,
				"challenges=0",
				"generation=0",
				"score=" + score,
				"depth=" + depth,
				"turns=" + turns,
				"turn_limit=" + turns,
				"max_slots=32",
				"allow_equipping=true",
				"termination=OTHER",
				"steps=" + declared ));

		if (!group.isEmpty()) lines.add( "group=" + group );
		for (int i = 0; i < actual; i++) lines.add( "WAIT 0 WORLD 100 0.0  20 " + i + ".0 Food:1" );

		try {
			Files.write( file.toPath(), lines, StandardCharsets.UTF_8 );
		} catch (IOException e){
			throw new IllegalStateException( "could not write " + file, e );
		}
		return file;
	}

	private static File write( File dir, String seed, String hero, double score,
			int depth, int turns ){
		return write( dir, seed, hero, score, depth, turns, 1, 1 );
	}

	private static void deleteTree( File file ){
		File[] children = file.listFiles();
		if (children != null) for (File child : children) deleteTree( child );
		if (!file.delete()) file.deleteOnExit();
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
