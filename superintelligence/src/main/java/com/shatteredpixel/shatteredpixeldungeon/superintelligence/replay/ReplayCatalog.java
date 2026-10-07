package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

		/** How many files carry this seed. More than one means the name alone does not identify it. */
		public int copies = 1;

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

	private final Map<String, List<Entry>> copies = new LinkedHashMap<>();

	/** Every entry found, including the duplicates the listing collapses. */
	private final List<Entry> allEntries = new ArrayList<>();

	public List<Entry> entries(){
		return entries;
	}

	public boolean isEmpty(){
		return entries.isEmpty();
	}

	/**
	 * Where recordings are looked for when {@code --dir} says nothing.
	 *
	 * <p>The checked-in fixtures first, because they are the deliberate reference set and a duplicate
	 * of one of those should lose to the repository copy rather than win on directory order.
	 *
	 * <p>Then everything {@link ReplayIndex} knows about, which is the part that was missing. The
	 * trainer writes into {@code <--out>/replays} and {@code --out} is wherever the run was pointed;
	 * searching a fixed pair of directories found 5 of 147 recordings on this machine and silently
	 * missed the other 142. Nothing errored - the recordings were written, ranked and closed, and then
	 * unreachable.
	 *
	 * <p>The default temp directory is last rather than omitted, so a run made before the index
	 * existed is still findable.
	 */
	private static List<File> defaultDirectories(){
		//deduplicated by path. The index and the built-in default overlap by design - a run that used
		//the default directory records it - and scanning a directory twice makes every file in it look
		//like a duplicate of itself, which then reads as "recorded 2 times" for every seed.
		LinkedHashSet<File> dirs = new LinkedHashSet<>();
		dirs.add( new File( "replays" ).getAbsoluteFile() );
		dirs.addAll( ReplayIndex.read() );
		dirs.add( new File( System.getProperty( "java.io.tmpdir" ), "spd-train/replays" ).getAbsoluteFile() );
		return new ArrayList<>( dirs );
	}

	/**
	 * Scans each directory, keeping the first copy of any seed seen in the listing.
	 *
	 * <p>Deduplicated because the trainer's temp directory and the repo's fixture directory can both
	 * hold a run of the same seed, and a catalog listing the same recording twice under two paths is a
	 * question rather than an answer. Earlier directories win, so pass the repo first.
	 *
	 * <p><b>But the duplicates are kept, and counted.</b> They used to be dropped on the floor, which
	 * made a name ambiguous in a way nothing was willing to say: typing {@code TLH-MLA-DYU} silently
	 * returned whichever copy the directory order happened to reach first - a 139-step recording in
	 * one directory rather than the 1511-step one in another, with no indication that a second
	 * existed. A person checking why a particular run diverged would be handed a different run and no
	 * reason to doubt it.
	 *
	 * <p>So every copy is retained in {@link #copies}, the listing marks a seed that has more than
	 * one, and resolving a bare name that is ambiguous refuses rather than guessing.
	 */
	public ReplayCatalog scan( List<File> directories ){
		Map<String, Entry> bySeed = new LinkedHashMap<>();
		Map<String, List<Entry>> all = new LinkedHashMap<>();

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
				all.computeIfAbsent( key, k -> new ArrayList<>() ).add( entry );
			}
		}

		copies.clear();
		copies.putAll( all );

		//one entry per *file*, not per seed and not per group - ambiguous() counts files, so a list
		//holding both the deduped entries and the full groups counted a duplicated seed three times
		allEntries.clear();
		for (List<Entry> group : all.values()) allEntries.addAll( group );

		//count on the entry itself, so the renderer does not need the map
		for (Map.Entry<String, List<Entry>> pair : copies.entrySet()){
			for (Entry entry : pair.getValue()) entry.copies = pair.getValue().size();
		}

		entries.clear();
		entries.addAll( bySeed.values() );
		return this;
	}

	/**
	 * Every copy of every seed found, including duplicates the listing collapses.
	 *
	 * <p>Keyed by seed. A seed with more than one entry here is a name that cannot be resolved to a
	 * single file without being told which one is meant.
	 */
	public Map<String, List<Entry>> copies(){
		return copies;
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
	 * Finds a recording by the name a person would type.
	 *
	 * <p>Accepts the seed, the seed with either extension, or the bare filename, case-insensitively -
	 * because all three are things someone will try, and a resolver that only understands one of them
	 * reads as "no such recording" rather than "you typed it slightly differently".
	 *
	 * <p>The filename is tried before the seed, because two directories can hold different recordings
	 * under one seed name and the exact file the person saw is the one they meant.
	 */
	public Entry byName( String name ){
		String wanted = name == null ? "" : name.trim();
		if (wanted.isEmpty()) return null;

		//The guard lives here rather than in the caller. It was in the caller first, and byName still
		//answered: its extension-completion step turned "TWIN-SEED" into "TWIN-SEED.replay", matched
		//one of two identically named files, and returned it - so a caller that forgot to check first
		//got a confident wrong answer rather than nothing.
		if (!ambiguous( wanted ).isEmpty()) return null;

		String lower = wanted.toLowerCase();

		//an exact filename is unambiguous even when the seed is not: the person named a file
		for (Entry entry : entries){
			if (entry.file.getName().toLowerCase().equals( lower )) return entry;
		}

		if (!lower.endsWith( ".dat" ) && !lower.endsWith( ".replay" )){
			for (String ext : new String[]{ ".dat", ".replay" }){
				for (Entry entry : entries){
					if (entry.file.getName().toLowerCase().equals( lower + ext )) return entry;
				}
			}
		}

		for (Entry entry : entries){
			if (entry.seed != null && entry.seed.equalsIgnoreCase( wanted ) ) return entry;
		}

		return null;
	}

	/**
	 * Every recording a name refers to, when that name is ambiguous.
	 *
	 * <p>Empty when the name identifies exactly one file. Used to refuse a guess rather than return
	 * whichever copy the directory order reached first.
	 *
	 * <p><b>Filenames count too, which the first version of this got wrong.</b> It checked seeds only,
	 * on the assumption that a filename identifies a file. It does not: two directories each holding
	 * {@code TWIN-SEED.replay} is the normal state here, because every run that used a given seed wrote
	 * the same filename. So the seed check passed and the resolution quietly returned one of them.
	 * A name is ambiguous if it matches more than one file, whatever kind of name it is.
	 */
	public List<Entry> ambiguous( String name ){
		List<Entry> matches = new ArrayList<>();

		if (name == null ) return matches;
		String lower = name.trim().toLowerCase();

		String withDat = lower.endsWith( ".dat" ) || lower.endsWith( ".replay" )
				? lower : lower + ".dat";
		String withReplay = lower.endsWith( ".dat" ) || lower.endsWith( ".replay" )
				? lower : lower + ".replay";

		for (Entry entry : allEntries){
			String fileName = entry.file.getName().toLowerCase();
			boolean sameFile = fileName.equals( lower )
					|| fileName.equals( withDat )
					|| fileName.equals( withReplay );
			boolean sameSeed = entry.seed != null && entry.seed.equalsIgnoreCase( lower.trim() );

			if (sameFile || sameSeed) matches.add( entry );
		}

		return matches.size() > 1 ? matches : new ArrayList<>();
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
				.append( " in " ).append( groupCount( groups )).append( " hero classes"
				).append( ", ranked by score\n" );

		//the copies have to be locatable, or refusing to resolve the name is a dead end
		List<Entry> shared = new ArrayList<>();
		for (Entry entry : entries){
			if (entry.copies > 1) shared.add( entry );
		}
		if (!shared.isEmpty()){
			out.append( "\n  " ).append( shared.size() ).append( " seed"
					).append( shared.size() == 1 ? " is" : "s are" )
					.append( " recorded more than once; pass a full path to pick one:\n" );

			for (Entry entry : shared){
				for (Entry copy : copiesFor( entry )){
					out.append( "    " ).append( copy.file.getAbsolutePath() )
							.append( "   (" ).append( copy.steps ).append( " steps, score " )
							.append( String.format( Locale.ROOT, "%.1f", copy.score ) ).append( ")\n" );
				}
			}
		}

		return out.toString();
	}

	private int groupCount( Map<String, List<Entry>> groups ){
		int n = 0;
		for (List<Entry> list : groups.values()) if (!list.isEmpty()) n++;
		return n;
	}

	private String note( Entry entry ){
		if (entry.error != null) return "[WARN] unreadable: " + entry.error;

		//before the generation, because a name that does not identify one file is a bigger obstacle
		//to acting on this line than not knowing which generation wrote it
		if (entry.copies > 1){
			return "[WARN] " + entry.copies + " recordings share this seed - name the file"
					+ ( entry.generation > 0 ? ", gen " + entry.generation : "" );
		}

		if (entry.truncatedAt >= 0) return "[WARN] truncated at " + entry.truncatedAt + " of " + entry.steps;
		if (entry.generation > 0) return "gen " + entry.generation;
		return "";
	}

	/** Every file recorded under the same seed as {@code entry}. */
	private List<Entry> copiesFor( Entry entry ){
		String key = entry.seed == null || entry.seed.isEmpty() ? entry.file.getName() : entry.seed;

		for (Map.Entry<String, List<Entry>> pair : copies.entrySet()){
			if (pair.getKey().equals( key )) return pair.getValue();
		}
		return List.of( entry );
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
		String resolve = null;

		for (int i = 0; i < args.length; i++ ){
			if ("--plain".equals( args[ i ] )){
				plain = true;
			} else if ("--dir".equals( args[ i ] ) && i + 1 < args.length){
				directories.add( new File( args[ ++i ] ));
			} else if ("--select".equals( args[ i ] ) && i + 1 < args.length){
				select = args[ ++i ];
			} else if ("--resolve".equals( args[ i ] ) && i + 1 < args.length){
				resolve = args[ ++i ];
			}
		}

		if (directories.isEmpty()){
			directories = defaultDirectories();
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

		// --resolve turns a name typed at the prompt into a path, searching every directory the index
		// knows about rather than the fixed pair the batch file used to guess at. Same reason as
		// --select: one implementation of "where are the recordings", in one language, testable.
		if (resolve != null){
			//a path is the way out of an ambiguous name, so honour it before anything else
			File asPath = new File( resolve );
			if (asPath.isFile()){
				System.out.print( asPath.getAbsolutePath() );
				return;
			}

			//A refusal is written to stdout and the process still exits 0, deliberately. This output is
			//read by a batch file that wants either a path or a sentence and nothing else; a non-zero
			//exit would make gradle print its own FAILURE block into the same stream, so the explanation
			//would arrive wrapped in a stack-trace-looking wall of build noise.
			List<Entry> many = catalog.ambiguous( resolve );
			if (!many.isEmpty()){
				StringBuilder message = new StringBuilder();
				message.append( resolve ).append( " is recorded " ).append( many.size() )
						.append( " times; pass a full path to pick one:" ).append( System.lineSeparator() );
				for (Entry entry : many){
					message.append( "  " ).append( entry.file.getAbsolutePath() )
							.append( "  (" ).append( entry.steps ).append( " steps, score " )
							.append( String.format( Locale.ROOT, "%.1f", entry.score ) ).append( ")" )
							.append( System.lineSeparator() );
				}
				System.out.print( message );
				return;
			}

			Entry entry = catalog.byName( resolve );
			if (entry == null){
				System.out.print( "No recording named " + resolve + ". Searched "
						+ directories.size() + " director"
						+ ( directories.size() == 1 ? "y" : "ies" ) + "."
						+ System.lineSeparator() );
				return;
			}

			System.out.print( entry.file.getAbsolutePath() );
			return;
		}

		System.out.print( catalog.render( plain ));

		if (catalog.isEmpty()) System.exit( 1 );
	}
}