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
import java.util.Set;


public class ReplayCatalog {


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


		public final String group;


		public final int truncatedAt;


		public final boolean watchable;


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
			this.group = replay.group == null ? "" : replay.group;
			this.truncatedAt = replay.truncatedAt;
			this.watchable = !replay.wireDroppedStepFields();
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
			this.group = "";
			this.truncatedAt = -1;
			this.watchable = false;
			this.error = error;
		}


		public int copies = 1;


		public boolean inCorpus;

		public boolean damaged(){
			return error != null || truncatedAt >= 0;
		}


		public String label(){
			String name = file.getName();
			int dot = name.lastIndexOf( '.' );
			return dot > 0 ? name.substring( 0, dot ) : name;
		}
	}


	private static final List<String> CLASS_ORDER = java.util.Arrays.asList(
			"WARRIOR", "MAGE", "HUNTRESS", "ROGUE", "DUELIST" );


	public static final String SECTION_CORPUS = "TEST CORPUS";


	public static final String SECTION_TRAINER = "TRAINER OUTPUT";


	public static final String GROUP_PREFIX = "GROUP ";


	private static final String REJECTED_DIR = "rejected";

	private final List<Entry> entries = new ArrayList<>();

	private final Map<String, List<Entry>> copies = new LinkedHashMap<>();


	private final List<Entry> allEntries = new ArrayList<>();

	private File corpus = corpusDirectory();

	public ReplayCatalog corpus( File dir ){
		corpus = canonical( dir );
		return this;
	}

	private static File corpusDirectory(){
		File repo = repositoryRoot();
		return repo == null ? null : canonical( new File( repo, "superintelligence/test-replays" ));
	}

	public List<Entry> entries(){
		return entries;
	}

	public boolean isEmpty(){
		return entries.isEmpty();
	}


	private static List<File> defaultDirectories(){

		LinkedHashSet<File> dirs = new LinkedHashSet<>();

		File repo = repositoryRoot();
		if (repo != null){
			dirs.add( corpusDirectory() );
			dirs.add( canonical( new File( repo, "superintelligence/trainer-replays" )));
		}

		for (File dir : ReplayIndex.read()) dirs.add( canonical( dir ));

		dirs.add( canonical( new File( com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer.outDirDefault(), "replays" )));
		return new ArrayList<>( dirs );
	}


	static File canonical( File dir ){
		if (dir == null) return null;
		try {
			return dir.getCanonicalFile();
		} catch (IOException e){
			return dir.getAbsoluteFile();
		}
	}


	private static File repositoryRoot(){
		File dir = new File( System.getProperty( "user.dir" ) ).getAbsoluteFile();

		for (int depth = 0; depth < 12 && dir != null; depth++){
			if (new File( dir, "settings.gradle" ).isFile()) return dir;
			dir = dir.getParentFile();
		}

		return null;
	}


	public ReplayCatalog scan( List<File> directories ){
		Map<String, Entry> bySeed = new LinkedHashMap<>();
		Map<String, List<Entry>> all = new LinkedHashMap<>();

		Set<String> seen = new LinkedHashSet<>();

		for (File dir : directories){
			if (dir == null || !dir.isDirectory()) continue;

File canonical = canonical( dir );
		if (!seen.add( canonical.getPath() )) continue;

		for (File file : recordingsUnder( canonical )){
			String name = file.getName().toLowerCase();

			if (!(name.endsWith( ".replay" ) || name.endsWith( ".dat" ))) continue;

				Entry entry;
				try {
					entry = new Entry( file, ReplayIO.readHeader( file ) );
				} catch (IOException e){

					entry = new Entry( file, e.getMessage() );
				}

				entry.inCorpus = canonical.equals( corpus );

				String key = entry.seed == null || entry.seed.isEmpty() ? file.getName() : entry.seed;
				if (!bySeed.containsKey( key )) bySeed.put( key, entry );
				all.computeIfAbsent( key, k -> new ArrayList<>() ).add( entry );
			}
		}

		copies.clear();
		copies.putAll( all );

		allEntries.clear();
		for (List<Entry> group : all.values()) allEntries.addAll( group );

		for (Map.Entry<String, List<Entry>> pair : copies.entrySet()){
			for (Entry entry : pair.getValue()) entry.copies = pair.getValue().size();
		}

		entries.clear();
		entries.addAll( bySeed.values() );
		return this;
	}


	private static List<File> recordingsUnder( File dir ){
		List<File> found = new ArrayList<>();
		collectRecordings( dir, found, 0 );
		found.sort( Comparator.comparing( File::getName ).thenComparing( File::getPath ));
		return found;
	}


	private static void collectRecordings( File dir, List<File> found, int depth ){
		if (depth > 4) return;

		File[] children = dir.listFiles();
		if (children == null) return;

		for (File child : children){
			if (child.isDirectory()){
				if (REJECTED_DIR.equals( child.getName() )) continue;
				collectRecordings( child, found, depth + 1 );
			} else {
				found.add( child );
			}
		}
	}


	public Map<String, List<Entry>> copies(){
		return copies;
	}


	public Map<String, List<Entry>> grouped(){
		Map<String, List<Entry>> groups = new LinkedHashMap<>();

		for (String hero : CLASS_ORDER) groups.put( hero, new ArrayList<>() );

		for (Entry entry : entries){
			String hero = entry.heroClass == null ? "?" : entry.heroClass.toUpperCase();
			groups.computeIfAbsent( hero, k -> new ArrayList<>() ).add( entry );
		}

		groups.values().forEach( list -> list.sort( rankOrder() ));
		return groups;
	}


	private static Comparator<Entry> rankOrder(){
		return Comparator

				.comparing( ( Entry e ) -> e.error != null )

				.thenComparingDouble( e -> e.error != null ? Double.MAX_VALUE : -e.score )
				.thenComparingInt( e -> -e.depth )
				.thenComparing( e -> e.label() );
	}


	public List<Entry> find( String name ){
		List<Entry> matches = new ArrayList<>();
		if (name == null || name.trim().isEmpty()) return matches;

		String needle = name.trim().toLowerCase();

		File asPath = new File( name.trim() );
		if (asPath.isFile()){
			for (Entry entry : allEntries){
				if (entry.file.getAbsolutePath().equalsIgnoreCase( asPath.getAbsolutePath() )){
					matches.add( entry );
					return matches;
				}
			}
		}

		String stem = needle;
		int dot = stem.lastIndexOf( '.' );
		if (dot > 0) stem = stem.substring( 0, dot );

		for (Entry entry : allEntries){
			boolean hit = entry.file.getName().equalsIgnoreCase( needle )
					|| entry.label().equalsIgnoreCase( stem )
					|| ( entry.seed != null && entry.seed.equalsIgnoreCase( stem ) );

			if (hit) matches.add( entry );
		}

		return matches;
	}


	public List<Entry> inListingOrder(){
		List<Entry> ordered = new ArrayList<>();

		for (Map<String, List<Entry>> group : sections().values()){
			for (List<Entry> hero : group.values()){
				ordered.addAll( hero );
			}
		}

		return ordered;
	}

	public String sectionOf( Entry entry ){
		if (entry.inCorpus) return SECTION_CORPUS;
		return entry.group.isEmpty() ? SECTION_TRAINER : GROUP_PREFIX + entry.group.toUpperCase( Locale.ROOT );
	}


	public Map<String, Map<String, List<Entry>>> sections(){
		Map<String, Map<String, List<Entry>>> sections = new LinkedHashMap<>();
		sections.put( SECTION_CORPUS, new LinkedHashMap<>() );
		sections.put( SECTION_TRAINER, new LinkedHashMap<>() );

		List<String> groups = new ArrayList<>( new LinkedHashSet<>( namedGroups() ));
		groups.sort( String::compareTo );
		for (String group : groups) sections.put( GROUP_PREFIX + group, new LinkedHashMap<>() );

		for (Map<String, List<Entry>> heroes : sections.values()){
			for (String hero : CLASS_ORDER) heroes.put( hero, new ArrayList<>() );
		}

		for (Entry entry : entries){
			String hero = entry.heroClass == null ? "?" : entry.heroClass.toUpperCase();
			sections.get( sectionOf( entry )).computeIfAbsent( hero, k -> new ArrayList<>() ).add( entry );
		}

		for (Map<String, List<Entry>> heroes : sections.values()){
			heroes.values().forEach( list -> list.sort( rankOrder() ));
		}

		return sections;
	}


	private Set<String> namedGroups(){
		Set<String> groups = new LinkedHashSet<>();
		for (Entry entry : entries){
			if (!entry.inCorpus && !entry.group.isEmpty()) groups.add( entry.group.toUpperCase( Locale.ROOT ));
		}
		return groups;
	}


	public Entry byNumber( int number ){
		List<Entry> ordered = inListingOrder();
		return number < 1 || number > ordered.size() ? null : ordered.get( number - 1 );
	}


	public Entry byName( String name ){
		String wanted = name == null ? "" : name.trim();
		if (wanted.isEmpty()) return null;

		if (!ambiguous( wanted ).isEmpty()) return null;

		String lower = wanted.toLowerCase();

		for (Entry entry : allEntries){
			if (entry.file.getName().toLowerCase().equals( lower )) return entry;
		}

		if (!lower.endsWith( ".dat" ) && !lower.endsWith( ".replay" )){
			for (String ext : new String[]{ ".dat", ".replay" }){
				for (Entry entry : allEntries){
					if (entry.file.getName().toLowerCase().equals( lower + ext )) return entry;
				}
			}
		}

		for (Entry entry : entries){
			if (entry.seed != null && entry.seed.equalsIgnoreCase( wanted ) ) return entry;
		}

		return null;
	}


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


	public String render( boolean plain ){
		Map<String, Map<String, List<Entry>>> sections = sections();
		StringBuilder out = new StringBuilder();

		int shown = 0;
		for (Map.Entry<String, Map<String, List<Entry>>> section : sections.entrySet()){
			int inSection = sectionCount( section.getValue() );
			if (inSection == 0) continue;

			String blurb = "  " + sectionBlurb( section.getKey(), section.getValue() );
			if (!plain) blurb = Ansi.wrap( blurb, Ansi.DIM );

			out.append( "\n  " ).append( section.getKey() ).append( " " ).append( blurb ).append( "\n" );
			out.append( "  " ).append( dashed() ).append( "\n" );
			out.append( "  " ).append( columnHeadings() ).append( "\n" );

			for (Map.Entry<String, List<Entry>> group : section.getValue().entrySet()){
				if (group.getValue().isEmpty()) continue;

				out.append( "\n    " ).append( group.getKey() ).append( "\n" );

				for (Entry entry : group.getValue()){
					shown++;

					String note = note( entry );
					if (!note.isEmpty() && !plain) note = Ansi.wrap( note, Ansi.DIM );

					out.append( String.format( Locale.US,
							"     %2d  %-22s %9s  %5s  %9s  %7s   %s%n",
							shown,
							entry.label(),
							formatScore( entry ),
							formatDepth( entry ),
							formatRecordedAt( entry ),
							String.format( Locale.US, "%,d", entry.turns ),
							note ) );
				}
			}
		}

		if (shown == 0) return "  no recordings found\n";

		out.append( "\n" ).append( shown ).append( " recording" ).append( shown == 1 ? "" : "s" )
				.append( ", ranked by score within each section"
				).append( ", numbered 1 to " ).append( shown ).append( "\n" );

		List<Entry> shared = new ArrayList<>();
		for (Entry entry : entries){
			if (entry.copies > 1) shared.add( entry );
		}
		if (!shared.isEmpty()){
			out.append( "\n  " ).append( shared.size() ).append( " seed" )
					.append( shared.size() == 1 ? " is" : "s are" )
					.append( " recorded more than once. Type the file name to pick one:\n\n" );

			for (Entry entry : shared){
				boolean first = true;
				for (Entry copy : copiesFor( entry )){
					out.append( String.format( Locale.ROOT, "    %-18s %-30s %-16s %6d  %9.1f",
							first ? entry.label() : "",
							copy.file.getName(),
							parentName( copy ),
							copy.steps,
							copy.score ) ).append( "\n" );
					first = false;
				}
			}
		}

		return out.toString();
	}


	private static String parentName( Entry entry ){
		File parent = entry.file.getParentFile();
		return parent == null ? "" : parent.getName();
	}


	private String sectionBlurb( String section, Map<String, List<Entry>> groups ){
		if (SECTION_CORPUS.equals( section )){
			return "recorded through ReplayRecorder directly - these reproduce";
		}

		int unwatchable = 0;
		for (List<Entry> hero : groups.values()){
			for (Entry entry : hero) if (!entry.watchable) unwatchable++;
		}

		String origin = section.startsWith( GROUP_PREFIX )
				? "written by training run group " + section.substring( GROUP_PREFIX.length() ).toLowerCase( Locale.ROOT ) + " - these reproduce"
				: "written by the trainer through the worker pipe - these reproduce";

		if (unwatchable == 0){
			return origin + ", and every one of them is watchable in the viewer";
		}

		return origin + " too. " + unwatchable
				+ " of " + sectionCount( groups ) + ", marked 'not watchable', were written before the"
				+ " replay wire carried all nine step fields and nothing can recover that"
				+ " (issues.md 15)";
	}

	private int sectionCount( Map<String, List<Entry>> groups ){
		int n = 0;
		for (List<Entry> list : groups.values()) n += list.size();
		return n;
	}

	private String note( Entry entry ){
		if (entry.error != null) return "[WARN] unreadable: " + entry.error;

		if (entry.copies > 1){
			return "[WARN] " + entry.copies + " recordings share this seed - name the file"
					+ ( entry.generation > 0 ? ", gen " + entry.generation : "" );
		}

		if (entry.truncatedAt >= 0) return "[WARN] truncated at " + entry.truncatedAt + " of " + entry.steps;

		String watchable = entry.watchable ? "" : "not watchable ";

		if (entry.generation > 0) return watchable + "gen " + entry.generation;
		return entry.watchable ? "" : watchable.trim();
	}


	private List<Entry> copiesFor( Entry entry ){
		String key = entry.seed == null || entry.seed.isEmpty() ? entry.file.getName() : entry.seed;

		for (Map.Entry<String, List<Entry>> pair : copies.entrySet()){
			if (pair.getKey().equals( key )) return pair.getValue();
		}
		return List.of( entry );
	}


	private static String formatScore( Entry entry ){
		if (entry.error != null) return "-";
		return String.format( "%,.1f", entry.score );
	}


	private static String formatDepth( Entry entry ){
		if (entry.error != null) return "-";
		return "d" + entry.depth;
	}


	private static String columnHeadings(){
		return String.format( Locale.US,
				"  %-4s %-22s %9s  %5s  %9s  %7s",
				"#", "recording", "score", "depth", "recorded", "turns" );
	}


	private static String formatRecordedAt( Entry entry ){
		if (entry.error != null) return "-";
		long modified = entry.file.lastModified();
		if (modified <= 0) return "-";

		java.time.LocalDateTime at = java.time.LocalDateTime.ofInstant(
				java.time.Instant.ofEpochMilli( modified ),
				java.time.ZoneId.systemDefault() );
		return String.format( Locale.US, "%02d-%02dT%02d:%02d",
				at.getMonthValue(), at.getDayOfMonth(), at.getHour(), at.getMinute() );
	}

	private static String dashed(){
		return "--------------------------- ----------------------";
	}

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

		if (resolve != null){

			File asPath = new File( resolve );
			if (asPath.isFile()){
				System.out.print( asPath.getAbsolutePath() );
				return;
			}

			List<Entry> many = catalog.ambiguous( resolve );
			if (!many.isEmpty()){
				StringBuilder message = new StringBuilder();
				message.append( resolve ).append( " is recorded " ).append( many.size() )
						.append( " times; pass a file name to pick one:" ).append( System.lineSeparator() );
				for (Entry entry : many){

					message.append( "  " ).append( entry.file.getName() )
							.append( String.format( Locale.ROOT, "  %-16s %6d steps, score %9.1f",
									parentName( entry ), entry.steps, entry.score ))
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
