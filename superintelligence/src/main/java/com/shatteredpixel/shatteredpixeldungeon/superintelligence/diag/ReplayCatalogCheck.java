package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayCatalog;
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

	private static final int CHECKS = 9;

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
			checkDeduplicatesTheSameSeed();
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
				"turn_limit=1500",
				"steps=" + declared ));

		for (int i = 0; i < actual; i++) lines.add( "WAIT 0 WORLD 100 0.0" );

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