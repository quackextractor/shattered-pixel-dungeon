package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayCatalog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.depthColumnIn;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.dir;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.flatten;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.readQuietly;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.rowFor;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.write;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.writeBack;

public final class ReplayCatalogListingCheck {

	private ReplayCatalogListingCheck(){}

	public static void checkSectionsSplitByProvenance(){
		File corpus = dir( "sections-corpus" );
		File trainer = dir( "sections-trainer" );

		write( corpus, "CORPUS-ONE", "WARRIOR", 10.0, 1, 100, 1, 1 );
		write( corpus, "CORPUS-TWO", "MAGE", 5.0, 1, 100, 1, 1 );
		write( trainer, "TRAINER-ONE", "WARRIOR", -40.0, 2, 300, 1, 1 );
		write( trainer, "TRAINER-TWO", "CLERIC", -90.0, 1, 500, 1, 1 );

		ReplayCatalog catalog = new ReplayCatalog().corpus( corpus ).scan( List.of( corpus, trainer ));

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
		Replay healthyReplay = readQuietly( healthy );
		healthyReplay.steps.get( 0 ).mode = "SLOT";
		healthyReplay.steps.get( 0 ).quickslots = "";
		writeBack( healthy, healthyReplay );

		File dropped = write( trainer, "TRAINER-DROPPED", "MAGE", -35.0, 1, 100, 1, 1 );
		Replay droppedReplay = readQuietly( dropped );
		ReplayCatalogFixture.dropEveryStepField( droppedReplay );
		writeBack( dropped, droppedReplay );

		ReplayCatalog relisted = new ReplayCatalog().corpus( corpus ).scan( List.of( corpus, trainer ));
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


	public static void checkATrainerDirectoryAloneIsNotTheCorpus(){
		File trainer = dir( "trainer-alone" );
		write( trainer, "TRAINER-ALONE", "WARRIOR", 1.0, 1, 10 );

		ReplayCatalog catalog = new ReplayCatalog().scan( List.of( trainer ));
		int inCorpus = flatten( catalog.sections().get( ReplayCatalog.SECTION_CORPUS )).size();
		if (inCorpus != 0){
			fail( "replays --dir <trainer dir> lists " + inCorpus + " trainer recording(s) as "
					+ ReplayCatalog.SECTION_CORPUS + ", because the first directory scanned was taken to be"
					+ " the corpus" );
		}
	}


	public static void checkDepthIsAReadableColumn(){
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

		if (!listing.contains( "turns" )){
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


	public static void checkRankedByScoreWithinAGroup(){
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


	public static void checkGroupedByHeroClass(){
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


	public static void checkDepthBreaksAScoreTie(){
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


	public static void checkDamagedFilesAreListedNotHidden(){
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


	public static void checkDamagedFilesSortLast(){
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


	public static void checkAMalformedFileIsListedNotFatal(){
		File dir = dir( "malformed" );
		write( dir, "good", "WARRIOR", 1.0, 1, 10 );
		File badHeader = new File( dir, "bad-header.replay" );
		File badStep = new File( dir, "bad-step.replay" );
		try {
			Files.write( badHeader.toPath(), Arrays.asList( Replay.MAGIC, "score=abc", "steps=0" ),
					StandardCharsets.UTF_8 );
			Files.write( badStep.toPath(), Arrays.asList( Replay.MAGIC, "steps=1", "FLY 0 WORLD" ),
					StandardCharsets.UTF_8 );
		} catch (IOException e){
			fail( "could not write the malformed fixture: " + e.getMessage() );
			return;
		}

		try {
			ReplayCatalog catalog = new ReplayCatalog().scan( List.of( dir ) );
			if (catalog.entries().size() != 3){
				fail( "a malformed header was dropped rather than listed: " + catalog.entries().size() + " of 3" );
				return;
			}
		} catch (RuntimeException e){
			fail( "one malformed header took the whole listing down: " + e );
			return;
		}

		try {
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO.read( badStep );
			fail( "a step naming no action was read without complaint" );
		} catch (IOException e){
			return;
		} catch (RuntimeException e){
			fail( "a step naming no action escaped as " + e + " instead of an IOException" );
		}
	}


	public static void checkLookupBySeedWithoutExtension(){
		File dir = dir( "lookup-seed" );
		write( dir, "YVU-UBS-QFE", "WARRIOR", 44.0, 1, 254 );

		List<ReplayCatalog.Entry> matches = new ReplayCatalog().scan( List.of( dir ) )
				.find( "YVU-UBS-QFE" );

		if (matches.size() != 1){
			fail( "a bare seed should resolve to 1 recording, matched " + matches.size() );
		}
	}


	public static void checkLookupByFilenameWithExtension(){
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


	public static void checkLookupIsCaseInsensitiveAndAcceptsAPath(){
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
}