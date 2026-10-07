package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What is on disk, grouped by hero class and ranked by score.
 *
 * <pre>gradle :superintelligence:replays [--dir &lt;d&gt;]... [--plain]</pre>
 *
 * Built because "which run was that" was answered by reading every file by hand, and because the
 * answer is not obvious from a directory listing: the trainer writes
 * {@code <seed>.dat} into a temp directory while the checked-in fixtures are {@code .replay} files,
 * the two are sorted by nothing in particular, and the seed text is what a person actually has to
 * hand.
 *
 * Grouped by class because score is not comparable across classes — a Huntress that reaches depth 3 is
 * not out-performed by a Warrior that reaches depth 2 — so a single global ranking answers the wrong
 * question. Ranked within each group by score descending, with depth as the tie-break because the
 * score is a sum over a turn budget and two runs at the same score are not equally good.
 *
 * Only the header of each file is read. A full {@link ReplayIO#read} would parse every step of every
 * recording to print six numbers, and the largest fixture is 1500 of them.
 */
public class ReplayCatalog {

	/** One recording, and where it is. */
	public static class Entry {
		public final File file;
		public final String seed;
		public final String heroClass;
		public final double score;
		public final int depth;
		public final int turns;
		public final int steps;
		public final int generation;
		public final int turnLimit;

		/** Steps actually present, or -1 when the body was complete. */
		public final int truncatedAt;

		/** Set when the file could not be read at all, so a broken file is listed rather than lost. */
		public final String error;

		Entry( File file, Replay replay ){
			this.file = file;
			this.seed = replay.seedText;
			this.heroClass = replay.heroClass;
			this.score = replay.score;
			this.depth = replay.depth;
			this.turns = replay.turns;
			this.steps = replay.declaredSteps;
			this.generation = replay.generation;
			this.turnLimit = replay.turnLimitPerFloor;
			this.truncatedAt = replay.truncatedAt;
			this.error = null;
		}

		Entry( File file, String error ){
			this.file = file;
			this.seed = file.getName();
			this.heroClass = "?";
			this.score = Double.NaN;
			this.depth = 0;
			this.turns = 0;
			this.steps = 0;
			this.generation = -1;
			this.turnLimit = 0;
			this.truncatedAt = -1;
			this.error = error;
		}

		public boolean damaged(){
			return error != null || truncatedAt >= 0;
		}

		/** Filename without its extension, which is what a person tends to have to hand. */
		public String label(){
			String name = file.getName();
			int dot = name.lastIndexOf( '.' );
			return dot > 0 ? name.substring( 0, dot ) : name;
		}
	}

	/** Hero classes in the order they should be listed, with anything unrecognised after them. */
	private static final List<String> CLASS_ORDER = java.util.Arrays.asList(
			"WARRIOR", "MAGE", "HUNTRESS", "ROGUE", "DUELIST" );

	private final List<Entry> entries = new ArrayList<>();

	public List<Entry> entries(){
		return entries;
	}

	public boolean isEmpty(){
		return entries.isEmpty();
	}

	/**
	 * Scans each directory, keeping the first copy of any seed seen.
	 *
	 * Deduplicated because the trainer's temp directory and the repo's fixture directory can both hold
	 * a run of the same seed, and a catalog listing the same recording twice under two paths is a
	 * question rather than an answer. Earlier directories win, so pass the repo first.
	 */
	public ReplayCatalog scan( List<File> directories ){
		Map<String, Entry> bySeed = new LinkedHashMap<>();

		for (File dir : directories){
			if (dir == null || !dir.isDirectory()) continue;

			File[] files = dir.listFiles();
			if (files == null) continue;

			//sorted so two runs over the same directory list the same way
			java.util.Arrays.sort( files, Comparator.comparing( File::getName ));

			for (File file : files){
				String name = file.getName().toLowerCase();

				//Two extensions, deliberately. The checked-in fixtures are .replay; the trainer writes
				//<seed>.dat into a temp directory. Both are real recordings and a person looking for
				//"that run from generation 19" has no reason to know which side wrote it.
				if (!(name.endsWith( ".replay" ) || name.endsWith( ".dat" ))) continue;

				Entry entry;
				try {
					entry = new Entry( file, ReplayIO.readHeader( file ) );
				} catch (IOException e){
					//listed with the reason rather than skipped: a file that cannot be parsed is
					//exactly the one worth knowing about
					entry = new Entry( file, e.getMessage() );
				}

				String key = entry.seed == null || entry.seed.isEmpty() ? file.getName() : entry.seed;
				if (!bySeed.containsKey( key )) bySeed.put( key, entry );
			}
		}

		entries.clear();
		entries.addAll( bySeed.values() );
		return this;
	}

	/** Entries grouped by hero class, each group ranked by score descending. */
	public Map<String, List<Entry>> grouped(){
		Map<String, List<Entry>> groups = new LinkedHashMap<>();

		for (String hero : CLASS_ORDER) groups.put( hero, new ArrayList<>() );

		for (Entry entry : entries){
			String hero = entry.heroClass == null ? "?" : entry.heroClass.toUpperCase();
			groups.computeIfAbsent( hero, k -> new ArrayList<>() ).add( entry );
		}

		Comparator<Entry> rank = Comparator
				//a damaged file has NaN score, and every comparison against NaN is false - which would
				//leave it wherever it happened to be rather than at the bottom, so they sort explicitly
				.comparing( ( Entry e ) -> e.error != null )
				//negated, because the comparator ascends and a ranked list is best-first. Written this
				//way rather than reversing, so the damage rule keeps its own key and does not depend on
				//where a reversal happens to leave it.
				.thenComparingDouble( e -> e.error != null ? Double.MAX_VALUE : -e.score )
				.thenComparingInt( e -> -e.depth )
				.thenComparing( e -> e.label() );

		groups.values().forEach( list -> list.sort( rank ) );
		return groups;
	}

	/**
	 * Finds the recording a name refers to.
	 *
	 * Accepts a seed, a filename, a path, or any of those with or without the extension, and matches
	 * case-insensitively. A clipboard paste of "YVU-UBS-QFE" or "YVU-UBS-QFE.dat" both land here,
	 * because those are the two forms a person realistically has: the name they read off the screen,
	 * or the name they copied out of a directory listing.
	 *
	 * @return every match, so a caller can say "three files are called that" instead of picking one
	 */
	public List<Entry> find( String name ){
		List<Entry> matches = new ArrayList<>();
		if (name == null || name.trim().isEmpty()) return matches;

		String needle = name.trim().toLowerCase();

		//a full path was given
		File asPath = new File( name.trim() );
		if (asPath.isFile()){
			for (Entry entry : entries){
				if (entry.file.getAbsolutePath().equalsIgnoreCase( asPath.getAbsolutePath() )){
					matches.add( entry );
					return matches;
				}
			}
		}

		String stem = needle;
		int dot = stem.lastIndexOf( '.' );
		if (dot > 0) stem = stem.substring( 0, dot );

		for (Entry entry : entries){
			boolean hit = entry.file.getName().equalsIgnoreCase( needle )
					|| entry.label().equalsIgnoreCase( stem )
					|| ( entry.seed != null && entry.seed.equalsIgnoreCase( stem ) );

			if (hit) matches.add( entry );
		}

		return matches;
	}

	// --------------------------------------------------------------------------- rendering

	/**
	 * The entries in listing order, numbered from 1.
	 *
	 * The same traversal {@link #render} prints, so a number shown to a person and a number typed
	 * back refer to the same recording. Duplicated in the batch file otherwise, which is a second copy
	 * of a sort order that would drift from this one the first time either changed.
	 */
	public List<Entry> inListingOrder(){
		List<Entry> ordered = new ArrayList<>();

		for (List<Entry> group : grouped().values()){
			if (group.isEmpty()) continue;
			ordered.addAll( group );
		}

		return ordered;
	}

	/** The nth listing entry, 1-based, or null when there is no such entry. */
	public Entry byNumber( int number ){
		List<Entry> ordered = inListingOrder();
		return number < 1 || number > ordered.size() ? null : ordered.get( number - 1 );
	}

	/**
	 * Renders the grouped catalog.
	 *
	 * Columns are fixed width so the scores line up, which is what makes a ranked list readable — a
	 * ranked list where the numbers do not line up is a list you have to read rather than scan.
	 *
	 * @param plain no colour codes, for a pipe or a log
	 */
	public String render( boolean plain ){
		Map<String, List<Entry>> groups = grouped();
		StringBuilder out = new StringBuilder();

		int shown = 0;
		for (Map.Entry<String, List<Entry>> group : groups.entrySet()){
			if (group.getValue().isEmpty()) continue;

			out.append( "\n  " ).append( group.getKey() ).append( "\n" );
			out.append( "  " ).append( dashed() ).append( "\n" );

			for (Entry entry : group.getValue()){
				shown++;
				//The note is dimmed rather than colour-coded: a warning here is about the file, not
				//about the score, and mixing the two in one column makes both harder to read.
				String note = note( entry );
				if (!note.isEmpty() && !plain) note = Ansi.wrap( note, Ansi.DIM );

				out.append( String.format( Locale.US,
						"   %2d  %-22s %9s  d%-3d %7s   %s%n",
						shown,
						entry.label(),
						formatScore( entry ),
						entry.depth,
						String.format( Locale.US, "%,d", entry.turns ),
						note ) );
			}
		}

		if (shown == 0) return "  no recordings found\n";

		out.append( "\n" ).append( shown ).append( " recording" ).append( shown == 1 ? "" : "s" )
				.append( " in " ).append( groupCount( groups ) ).append( " hero classes"
				).append( ", ranked by score\n" );

		return out.toString();
	}

	private int groupCount( Map<String, List<Entry>> groups ){
		int n = 0;
		for (List<Entry> list : groups.values()) if (!list.isEmpty()) n++;
		return n;
	}

	private String note( Entry entry ){
		if (entry.error != null) return "[WARN] unreadable: " + entry.error;
		if (entry.truncatedAt >= 0) return "[WARN] truncated at " + entry.truncatedAt + " of " + entry.steps;
		if (entry.generation > 0) return "gen " + entry.generation;
		return "";
	}

	/** Right-aligned so positives and negatives line up, which they do not as plain {@code %f}. */
	private static String formatScore( Entry entry ){
		if (entry.error != null) return "-";
		return String.format( "%,.1f", entry.score );
	}

	private static String dashed(){
		return "--------------------------- ----------------------";
	}

	// --------------------------------------------------------------------------- entry point

	public static void main( String[] args ){
		List<File> directories = new ArrayList<>();
		boolean plain = false;
		String select = null;

		for (int i = 0; i < args.length; i++){
			if ("--plain".equals( args[ i ] )){
				plain = true;
			} else if ("--dir".equals( args[ i ] ) && i + 1 < args.length){
				directories.add( new File( args[ ++i ] ));
			} else if ("--select".equals( args[ i ] ) && i + 1 < args.length){
				select = args[ ++i ];
			}
		}

		if (directories.isEmpty()){
			directories.add( new File( "replays" ));
			directories.add( new File( System.getProperty( "java.io.tmpdir" ), "spd-train/replays" ));
		}

		ReplayCatalog catalog = new ReplayCatalog().scan( directories );

		// --select resolves a number from the listing to a path, and nothing else is printed.
		//
		// This exists because the alternative was for the batch file to own a second copy of the
		// grouping and ranking order. It would have looked fine and then drifted the first time a
		// comparator was touched, so a number printed to a person would select a different recording
		// than the one they were looking at.
		if (select != null){
			int number;
			try {
				number = Integer.parseInt( select.trim() );
			} catch (NumberFormatException e){
				System.err.println( "not a number: " + select );
				System.exit( 1 );
				return;
			}

			Entry entry = catalog.byNumber( number );
			if (entry == null){
				System.err.println( "no recording numbered " + number
						+ " (" + catalog.inListingOrder().size() + " available)" );
				System.exit( 1 );
				return;
			}

			System.out.print( entry.file.getAbsolutePath() );
			return;
		}

		System.out.print( catalog.render( plain ));

		if (catalog.isEmpty()) System.exit( 1 );
	}
}