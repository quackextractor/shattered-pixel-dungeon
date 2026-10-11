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

/**
 * Checks the replay catalog: grouping, ranking, name resolution and header-only reads.
 *
 * <pre>gradle :superintelligence:replaycheck</pre>
 *
 * The catalog is what a person browses to find the run they want, so its failures are quiet ones: a
 * list ordered by the wrong key, or a group merged into another, still looks like a list. Nothing else
 * in the project would notice.
 *
 * Every fixture is written here rather than checked in, because the properties being asserted are
 * about <em>ordering and grouping</em> and not about any particular recording — a fixture on disk
 * would drift the moment the trainer wrote a better one, and the assertions would keep passing while
 * testing the wrong files. What is asserted is that a set of files with known scores and classes comes
 * back in the right order and the right groups, which holds for any set.
 *
 * Runs without game state, so it is fast and cannot be affected by anything the game does.
 */
public class ReplayCatalogCheck {

	private static final List< String > failures = new ArrayList<>();

	private static final int CHECKS = 14;

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
			checkSectionsSplitByProvenance();
		checkDepthIsAReadableColumn();
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

	// --------------------------------------------------------------------------- sections

	/**
	 * The listing separates the reference corpus from what the trainer wrote, and the numbers still
	 * mean what the listing says they mean.
	 *
	 * <p>The separation is the assertion: the corpus is recorded through {@code ReplayRecorder}
	 * directly and reproduces end to end, while the trainer's recordings crossed a worker pipe that was
	 * dropping five of nine step fields, so they stop at their first slot-bearing step. Interleaved in
	 * one hero-class ranking they are distinguishable only by filename, and picking one halts the
	 * viewer after a frame with nothing on screen saying why.
	 *
	 * <p>The numbering is the other half and the reason this is a check rather than a formatting
	 * change. Two sections, one sequence: a person reads a number and types it back, so every number
	 * printed has to resolve to the recording printed beside it. That holds across the section
	 * boundary, which is exactly where it would break first if {@link ReplayCatalog#inListingOrder}
	 * and {@link ReplayCatalog#render} were two traversals rather than one - and it would still look
	 * right in every section above the boundary.
	 */
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

		//provenance follows directory order, not the score: the corpus files score better than the
		//trainer ones here, and a ranking-driven split would have got this right by accident
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

		//one sequence across both sections, and every number resolves to the recording printed beside it
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

			//and the number is actually printed beside that recording, not merely implied by position.
			//A literal newline rather than %n, because render() appends its own "\n" before the row and
			//%n would be the platform separator, which is not the same thing on every machine.
			if (!listing.contains( String.format( "\n     %2d  %s", i + 1, ordered.get( i ).label() ))){
				fail( "the listing does not print number " + ( i + 1 ) + " beside "
						+ ordered.get( i ).label() + ", so the two views of the order have drifted" );
				return;
			}
		}

		//the sections are labeled, and the training one says what is wrong with it - a heading reading
		//only "trainer output" leaves a person to discover the difference by watching a playback halt
		if (!listing.contains( ReplayCatalog.SECTION_CORPUS )
				|| !listing.contains( ReplayCatalog.SECTION_TRAINER )){
			fail( "the rendered listing does not print both section headings" );
			return;
		}

		//The claim the training section has to make, per recording rather than for the whole section.
		//It used to be a blanket "NOT known to play end to end", which was the only thing standing
		//between a reader and a playback that halts at step 1 - and it was also wrong the moment a
		//trainer recording was written by the fixed wire, because it condemned a file that plays. So
		//the requirement is the stronger one: a recording written by the broken wire must say so on its
		//own line, and one carrying real health and engine time must not be condemned.
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

	/** The printed line one recording appears on, or "" when it does not appear at all. */
	private static String rowFor( String listing, String label ){
		for (String line : listing.split( "\n" )){
			if (line.contains( label )) return line;
		}
		return "";
	}

	/**
 * Depth has to be a column a reader can scan, not a value in another column's padding.
 *
 * <p>It was printed - inline as {@code d2}, inside the turns column's whitespace - and nobody could use
 * it. The listing ranks by score, so the run that actually made progress sat mid-block with its one
 * interesting fact rendered as a footnote. The report of the first depth-2 run in the project's history
 * was "I cannot see which recording got there", which is the cost of a value without a heading.
 *
 * <p>Asserts the heading exists and that a recording's own depth appears on its own row, so the column
 * cannot go back to being invisible without this failing.
 */
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

	//and it must not be the score column's, which is what made it unreadable before
	String header = listing.contains( "depth" ) ? listing : "";
	if (!header.contains( "turns" )){
		fail( "the listing has a depth heading but no turns heading, so the two columns cannot be told"
				+ " apart" );
		return;
	}

	//and a recorded-at column, because "which of these is new" is a question the listing otherwise
	//cannot answer: every recording carries a generation, and a run pointed at the same seed twice
	//overwrites, so age was the only thing distinguishing this run's output from an earlier one's.
	if (!listing.contains( "recorded" )){
		fail( "the listing has no 'recorded' heading, so a fresh training run's output cannot be told"
				+ " from yesterday's" );
		return;
	}
	//A cell lookup rather than a pattern over the line. `String.matches` anchors the whole string, and a
	//row ends in trailing padding, so a pattern with anything after the timestamp never matched a row
	//that had one - which is how this assertion first reported a timestamp it was printing.
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

	//and every row's depth must sit at the same offset, so the column scans. Compared as the position
	//of the depth *cell* rather than the first "d1"/"d3" in the line: a seed like SHALLOW contains a
	//"d" before the depth column ever does, and matching on the letter rather than the cell reported a
	//misalignment that was not there.
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

/**
 * Where a row's depth cell starts, or -1 when the row has no depth to find.
 *
 * <p>Searches for the depth cell rather than the letter "d", so a recording whose seed contains a "d"
 * does not report a column position from the middle of its own name.
 */
private static int depthColumnIn( String row ){
	String[] cells = row.trim().split( "\\s+" );
	//number, label, score, then the depth cell - the fields before it are the ones that cannot be it
	if (cells.length < 4) return -1;
	String cell = cells[ 3 ];
	return cell.matches( "d\\d+" ) ? row.indexOf( cell ) : -1;
}

	private static List<ReplayCatalog.Entry> flatten( Map<String, List<ReplayCatalog.Entry>> groups ){
		List<ReplayCatalog.Entry> all = new ArrayList<>();
		for (List<ReplayCatalog.Entry> list : groups.values()) all.addAll( list );
		return all;
	}

	// --------------------------------------------------------------------------- ordering

	/**
	 * Descending by score inside one class.
	 *
	 * The reason the catalog exists. Ascending would be just as easy to write and completely useless,
	 * so the direction is asserted explicitly rather than assumed.
	 */
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

	/**
	 * One group per class, and a class's recordings never appear under another's heading.
	 *
	 * Score is not comparable across classes — a Huntress reaching depth 3 is not beaten by a Warrior
	 * reaching depth 2 — so a single global ranking answers the wrong question. This is the property
	 * that makes grouping correct rather than decorative.
	 */
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

		//the whole point of grouping: a 90.0 Huntress outranks a 50.0 Rogue, and they are listed apart
		if (groups.get( "HUNTRESS" ).get( 0 ).label().equals( "h-weak" ) == false){
			fail( "HUNTRESS group is not ranked by score internally" );
		}
	}

	/** Equal scores are ordered by depth, because two runs at one score are not equally good. */
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

	// --------------------------------------------------------------------------- damaged files

	/**
	 * A truncated recording is listed, with the shortfall named.
	 *
	 * A truncated file is exactly the one a person browsing a catalog most needs to see — it is the run
	 * that went wrong — and it will not fix itself by being hidden. `ReplayIO.read` rejects it, which is
	 * right for a determinism check; the catalog uses `readHeader` and reports the shortfall.
	 */
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

	/**
	 * An unreadable file is listed with its reason, and kept out of the ranked groups.
	 *
	 * Its class is unknowable — that is part of what failed to parse — so it goes under "?" rather than
	 * being guessed at. Two consequences worth pinning down, because both are quiet: it must not
	 * displace a readable recording from its class's group, and its score is NaN, which compares false
	 * against everything, so a naive comparator would leave it at an arbitrary position rather than
	 * last.
	 */
	private static void checkDamagedFilesSortLast(){
		File dir = dir( "unreadable" );
		write( dir, "good-one", "WARRIOR", 1.0, 1, 10 );
		write( dir, "good-two", "WARRIOR", 2.0, 1, 10 );

		//not a replay at all, so nothing about it can be known
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

		//it must not have been filed under WARRIOR on a guess
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

	// --------------------------------------------------------------------------- name resolution

	/**
	 * A bare seed resolves — the form a person reads off the screen.
	 *
	 * "YVU-UBS-QFE" is what the trainer prints and what ends up in a console log; the extension is
	 * rarely what anyone copies.
	 */
	private static void checkLookupBySeedWithoutExtension(){
		File dir = dir( "lookup-seed" );
		write( dir, "YVU-UBS-QFE", "WARRIOR", 44.0, 1, 254 );

		List<ReplayCatalog.Entry> matches = new ReplayCatalog().scan( List.of( dir ) )
				.find( "YVU-UBS-QFE" );

		if (matches.size() != 1){
			fail( "a bare seed should resolve to 1 recording, matched " + matches.size() );
		}
	}

	/** The same name with its extension, which is the form a directory listing hands you. */
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

	/** Lower case, and a full path, both resolve — a clipboard paste is neither controlled. */
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

	// --------------------------------------------------------------------------- format

	/**
	 * The header-only read agrees with a full read.
	 *
	 * The catalog reads six fields per file across every recording on disk, so a divergence between
	 * `readHeader` and `read` would put numbers in front of a person that do not describe the file they
	 * will then play. Same input, same output — asserted field by field.
	 */
	private static void checkHeaderOnlyReadMatchesAFullRead(){
		File dir = dir( "parity" );
		//declared and actual must agree here: this check reads the file twice, once each way, and
		//read() rejects a body that does not match its header. Truncation is the previous check's job.
		File file = write( dir, "parity", "DUELIST", -12.75, 3, 1234, 5, 5 );

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
					&& header.turnLimitPerFloor == full.turnLimitPerFloor
					&& header.declaredSteps == full.steps.size()){
				return;
			}

fail( "readHeader and read disagree: seed " + header.seedText + "/" + full.seedText
					+ " score " + header.score + "/" + full.score
					+ " depth " + header.depth + "/" + full.depth
					+ " turns " + header.turns + "/" + full.turns );
		} catch (IOException e){
			fail( "reading the fixture threw: " + e.getMessage() );
		}
	}

	/**
	 * The header-only read must reach the same verdict as a full read, in both directions.
	 *
	 * <p>The catalog reads headers so it can list a directory without parsing every step of every
	 * recording, and it labels each one by whether it can be watched. That verdict comes from the body,
	 * so {@link ReplayIO#readHeader} has to reach it without building a {@link Replay.Step} per line -
	 * and the check here is that the cheap scan and the real parse do not drift.
	 *
	 * <p>Both directions, and the negative fixture is the instructive one: a recording that carries no
	 * quickslots but <em>does</em> carry health, turn and inventory is <b>watchable</b>. It is a run in
	 * which the hero had nothing bound, which resolves from backpack order. Refusing it is a false
	 * positive that only showed up because {@code rolloutcheck} failed intermittently - which is the
	 * usual way a false positive of this shape arrives, and the reason this fixture is built from the
	 * signature rather than from the absence.
	 */
	private static void checkHeaderOnlyReadAgreesOnWatchability(){
		File dir = dir( "watchable" );

		//a healthy recording whose hero has nothing bound: must be watchable
		File unbound = write( dir, "UNBOUND", "DUELIST", -1.0, 1, 3 );
		Replay unboundReplay = ReplayIOReadQuietly( unbound );
		unboundReplay.steps.get( 0 ).mode = "SLOT";
		unboundReplay.steps.get( 0 ).quickslots = "";
		writeBack( unbound, unboundReplay );

		//the four-field wire: health, turn and inventory all at their sentinels, on every step
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

			//and the full read must agree with the cheap one, which is the drift this guards
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

	/**
	 * One recording per seed, however many directories hold it.
	 *
	 * The trainer's temp directory and the repo's fixture directory can both hold a run of the same
	 * seed; a catalog listing it twice under two paths is a question rather than an answer.
	 */
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

	/**
	 * A name that several directories recorded must not resolve silently.
	 *
	 * <p>Deduplication keeps the listing readable, and it used to keep the ambiguity too: typing a seed
	 * returned whichever copy the directory order reached first. Here that meant
	 * {@code TLH-MLA-DYU} handed back a 139-step recording while the person was trying to understand
	 * the 1511-step one from a different run, with nothing on screen to say there was a choice.
	 *
	 * <p>So the listing still collapses, but the collapse is reported and the name is refused. A
	 * filename is still accepted, because that does identify one file.
	 */
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

		//A filename is NOT unambiguous here either, and refusing is right: every run that used this seed
		//wrote the same filename, so two directories hold TWIN-SEED.replay. The way out is a path, which
		//is why the listing prints them.
		if (catalog.byName( "TWIN-SEED.replay" ) != null){
			fail( "a bare filename that exists in two directories resolved to one of them. Two runs"
					+ " writing the same seed produce the same filename, so the filename does not"
					+ " disambiguate and guessing here returns an arbitrary one." );
			return;
		}

		//and the listing has to print the paths, or there is no way to pick one
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

	/**
	 * A run must be able to find its own recordings afterwards.
	 *
	 * <p>The trainer writes into {@code <--out>/replays} and {@code --out} is wherever the run was
	 * pointed, while discovery searched a fixed pair of directories. On this machine that made 142 of
	 * 147 recordings unreachable: written, ranked, closed, and never found again, with no error
	 * anywhere. The index exists so "where did this run put them" is answered by the run.
	 */
	private static void checkIndexedDirectoriesAreDiscovered(){
		File dir = dir( "indexed" );
		write( dir, "INDEXED-SEED", "MAGE", 7.0, 1, 40 );

		ReplayIndex.record( dir );

		if (!ReplayIndex.read().contains( dir.getAbsoluteFile() )){
			fail( "a directory recorded in the index is not read back out of it, so a run's own"
					+ " recordings would still be unfindable." );
			return;
		}

		//recording twice must not double the entry
		ReplayIndex.record( dir );
		int count = 0;
		for (File known : ReplayIndex.read()){
			if (known.equals( dir.getAbsoluteFile() )) count++;
		}
		if (count != 1){
			fail( "a directory recorded twice appears " + count + " times in the index." );
			return;
		}

		//and it must actually be scannable once known
		ReplayCatalog catalog = new ReplayCatalog().scan( ReplayIndex.read() );
		if (catalog.byName( "INDEXED-SEED" ) == null){
			fail( "a recording in an indexed directory is still not resolvable, so indexing it changed"
					+ " nothing observable." );
		}
	}

	// --------------------------------------------------------------------------- fixtures

	private static File dir( String name ){
		File dir = new File( workDir, name );
		if (!dir.mkdirs() && !dir.isDirectory()){
			throw new IllegalStateException( "could not create " + dir );
		}
		return dir;
	}

	/**
	 * Writes a recording.
	 *
	 * @param declared steps the header claims
	 * @param actual   steps written, so a caller can produce a truncated file
	 * @return the file written
	 */
/**
	 * One recording written by hand, with every step field present.
	 *
	 * <p>Every field, deliberately. The default {@code Step} sentinel for health, engine time and
	 * inventory is "not recorded", and a file written with those three at their sentinels carries the
	 * exact signature of the four-field wire - so a fixture meant to stand for a <em>healthy</em>
	 * recording has to give it real values, or the thing it is not testing is what fails.
	 */
	private static File write( File dir, String seed, String hero, double score,
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

		for (int i = 0; i < actual; i++) lines.add( "WAIT 0 WORLD 100 0.0  20 " + i + ".0 Food:1" );

		File file = new File( dir, seed + ".replay" );
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
