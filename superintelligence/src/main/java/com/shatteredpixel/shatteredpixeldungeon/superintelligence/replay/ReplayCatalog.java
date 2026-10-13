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

		/** The training run that wrote this, or "" when nobody grouped it. */
		public final String group;

		/** Steps actually present, or -1 when the body was complete. */
		public final int truncatedAt;

		/**
		 * Whether this recording can be watched at all.
		 *
		 * <p>Read from the body rather than assumed, so the catalog can say per file what is true rather
		 * than applying one blanket warning to a section - and a blanket warning becomes <em>wrong</em> the
		 * moment a fixed recording joins the ones it condemns.
		 *
		 * <p>The discriminator is the signature of the broken wire, not the absence of quickslots: the
		 * nine pre-fix trainer recordings carry {@code hp = -1, turn = -1.0, inventory = ""} on every
		 * step, and that is what identifies them. A recording with real health and real engine time had a
		 * wire that carried everything, so it plays.
		 */
		public final boolean watchable;

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

		/** How many files carry this seed. More than one means the name alone does not identify it. */
		public int copies = 1;

		/**
		 * Position of the directory this file was found in, which is what decides its section.
		 *
		 * <p>Not a path and not a name, because the section a recording belongs to is a statement about
		 * how it was <em>found</em> - the reference set passed first, or anything after it. Matching on
		 * directory names instead would have made a recording change section when someone renamed a
		 * folder, and would have had no answer at all for the index and temp directories that training
		 * runs write to, which are named after whatever {@code --out} happened to be.
		 */
		public int sourceIndex;

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

	/** The reference set: the first {@code --dir}, which is {@code test-replays} in practice. */
	public static final String SECTION_CORPUS = "TEST CORPUS";

	/** Everything a training run wrote that was not filed under a group. */
	public static final String SECTION_TRAINER = "TRAINER OUTPUT";

	/**
	 * Headings for the recordings one training run wrote.
	 *
	 * <p>Its own prefix rather than {@code "TRAINER " + group}, because a group named {@code output}
	 * would then produce a heading identical to {@link #SECTION_TRAINER} and two sections would share one
	 * key - the listing would print one of them twice and the other not at all.
	 */
	public static final String GROUP_PREFIX = "GROUP ";

	/** Not scanned: recordings a rebuild could not reproduce, which is where a corpus script parks them. */
	private static final String REJECTED_DIR = "rejected";

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
	 *
	 * <p><b>The checked-in directories are located by walking up to the repository root, not by
	 * relative path.</b> They used to be {@code new File( "replays" )}, which resolves against the
	 * working directory - and the gradle {@code run} task uses the <em>module</em> directory as its
	 * working directory, so that silently meant {@code superintelligence/replays}, which does not exist.
	 * The whole corpus was invisible to a default {@code --list}. The batch file worked around this by
	 * passing an absolute {@code --dir}; this was the same mistake one layer down, and the two
	 * directories now live inside the module, so a relative guess would have looked correct and kept
	 * resolving to nothing.
	 */
	private static List<File> defaultDirectories(){
		//deduplicated by canonical path. The index and the built-in default overlap by design - a run
		//that used the default directory records it - and scanning a directory twice makes every file in
		//it look like a duplicate of itself, which then reads as "recorded 2 times" for every seed.
		//
		//Canonical, not absolute, because the two spellings of the same directory are not always the same
		//string: the index holds `superintelligence/./trainer-replays` as the trainer wrote it and the
		//built-in default holds `superintelligence/trainer-replays`, and a File set compares paths. So the
		//dedup was here and did nothing, and every one of the 185 trainer recordings was listed twice.
		LinkedHashSet<File> dirs = new LinkedHashSet<>();

		File repo = repositoryRoot();
		if (repo != null){
			dirs.add( canonical( new File( repo, "superintelligence/test-replays" )));
			dirs.add( canonical( new File( repo, "superintelligence/trainer-replays" )));
		}

		for (File dir : ReplayIndex.read()) dirs.add( canonical( dir ));

		dirs.add( canonical( new File( com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer.outDirDefault(), "replays" )));
		return new ArrayList<>( dirs );
	}

	/**
	 * The directory's real path, with {@code .} and {@code ..} resolved away.
	 *
	 * <p>Falls back to the absolute path, which is what every caller already had. Canonicalisation
	 * touches the filesystem and can fail on a path nothing can reach, and a catalog that lists a
	 * directory twice is a much smaller problem than one that throws while being built.
	 */
	static File canonical( File dir ){
		if (dir == null) return null;
		try {
			return dir.getCanonicalFile();
		} catch (IOException e){
			return dir.getAbsoluteFile();
		}
	}

	/**
	 * The repository root, found by walking up from the working directory until {@code settings.gradle}
	 * appears, or {@code null} when the run started somewhere that is not inside the repository.
	 *
	 * <p>Walking up rather than looking one directory down from the module, because the two are only
	 * the same shape by accident: a jar run from a checkout has the module at {@code x/superintelligence}
	 * and one level down is the root, but a run from {@code x/superintelligence} itself needs two. The
	 * marker file is what settles it either way.
	 *
	 * <p>{@code null} is a supported answer rather than a failure. The catalog is reachable from
	 * anywhere - a user's temp directory, a packaged jar - and a missing root there costs the two
	 * checked-in directories and nothing else, because {@link ReplayIndex} still supplies whatever
	 * training runs have recorded.
	 */
	private static File repositoryRoot(){
		File dir = new File( System.getProperty( "user.dir" ) ).getAbsoluteFile();

		//bounded, because a pathologically deep working directory should not spin. 12 levels covers
		//a checkout nested a few directories deep with room to spare.
		for (int depth = 0; depth < 12 && dir != null; depth++){
			if (new File( dir, "settings.gradle" ).isFile()) return dir;
			dir = dir.getParentFile();
		}

		return null;
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

		//A directory is scanned once per *canonical* path, not once per spelling. The same directory
		//arrives under more than one - the index holds whatever the trainer wrote, the built-in default
		//holds the tidied form - and scanning it twice lists every file in it twice, which then reads
		//as "recorded 2 times" for every seed. The first spelling wins, so sourceIndex still says which
		//section a file came from.
		Set<String> seen = new LinkedHashSet<>();

		for (int dirIndex = 0; dirIndex < directories.size(); dirIndex++){
			File dir = directories.get( dirIndex );
			if (dir == null || !dir.isDirectory()) continue;

File canonical = canonical( dir );
		if (!seen.add( canonical.getPath() )) continue;

		for (File file : recordingsUnder( canonical )){
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

				entry.sourceIndex = dirIndex;

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
	 * Every file below {@code dir}, one level and deeper.
	 *
	 * <p>Deeper because a training run given {@code --replay-group} files its recordings into a
	 * subdirectory, and a listing that stopped at the top level would show the directory as holding
	 * nothing at all - the recordings would be written, ranked, and unreachable, which is the exact
	 * failure {@link ReplayIndex} exists to stop happening by accident.
	 *
	 * <p>Skipping {@code rejected} is the one exclusion. That directory is where
	 * {@code regenerate-corpus.ps1} parks recordings a rebuild could not reproduce, and listing them
	 * would be the failure this project has already paid for once: a picker offering a file whose
	 * playback diverges is a dead end, and a label on the row does not stop anybody taking it.
	 *
	 * <p>Total order, by name and then by path. {@code listFiles} order is unspecified, so two runs
	 * over the same tree would otherwise disagree about which of two same-named recordings a seed
	 * dedup keeps - which is the class of bug where directory order decides a property of the listing.
	 */
	private static List<File> recordingsUnder( File dir ){
		List<File> found = new ArrayList<>();
		collectRecordings( dir, found, 0 );
		found.sort( Comparator.comparing( File::getName ).thenComparing( File::getPath ));
		return found;
	}

	/** Bounded: a directory tree deeper than any run writes into is not one of ours. */
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

		groups.values().forEach( list -> list.sort( rankOrder() ));
		return groups;
	}

	/**
	 * Best first, and a damaged file last regardless of the score it claims.
	 *
	 * <p>Shared by {@link #grouped} and {@link #sections} so the two views cannot rank the same two
	 * recordings differently - which would mean the number beside a recording in the sectioned listing
	 * and the position it holds in the hero-class view disagree, for no visible reason.
	 */
	private static Comparator<Entry> rankOrder(){
		return Comparator
				//a damaged file has NaN score, and every comparison against NaN is false - which would
				//leave it wherever it happened to be rather than at the bottom, so they sort explicitly
				.comparing( ( Entry e ) -> e.error != null )
				//negated, because the comparator ascends and a ranked list is best-first. Written this
				//way rather than reversing, so the damage rule keeps its own key and does not depend on
				//where a reversal happens to leave it.
				.thenComparingDouble( e -> e.error != null ? Double.MAX_VALUE : -e.score )
				.thenComparingInt( e -> -e.depth )
				.thenComparing( e -> e.label() );
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

		//Every file, not one row per seed - the same blind spot byName had. This method exists to say
		//"three files are called that", and a deduplicated list can never say it for a shared seed.
		for (Entry entry : allEntries){
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

		for (Map<String, List<Entry>> group : sections().values()){
			for (List<Entry> hero : group.values()){
				ordered.addAll( hero );
			}
		}

		return ordered;
	}

	public String sectionOf( Entry entry ){
		if (entry.sourceIndex == 0) return SECTION_CORPUS;
		return entry.group.isEmpty() ? SECTION_TRAINER : GROUP_PREFIX + entry.group.toUpperCase( Locale.ROOT );
	}

	/**
	 * The listing's sections, each holding its hero-class groups.
	 *
	 * <p><b>Provenance is the outermost axis, and it is there because the halves are not
	 * interchangeable.</b> The reference corpus is built by {@code replay-viewer --record} through
	 * {@code ReplayRecorder} directly and reproduces end to end; what the trainer wrote crossed a
	 * worker pipe and, for every recording made before {@code train.ReplayCodec}, stopped at its first
	 * slot-bearing step because the quickslot bindings never arrived. Those files look identical in a
	 * ranking that interleaves them by hero class - the only tell is a filename in {@code XXX-XXX-XXX}
	 * form among descriptive ones, and the viewer gives no reason why selecting one halts after a
	 * frame.
	 *
	 * <p>Within the training half, a {@code --replay-group} is a section of its own. It is read from the
	 * header rather than from the directory the file happens to sit in, so a group survives being copied
	 * and moved, and a recording filed by a run that named its group says which run it is even when the
	 * folder is gone. Groups come after the ungrouped output and in name order, so the numbering stays a
	 * function of what is on disk rather than of which group was written last.
	 *
	 * <p>Both are still numbered in one sequence: two listings with two sets of numbers would make the
	 * word "number" mean two different recordings depending on which was on screen.
	 *
	 * <p>The split is the first {@code --dir} against everything after it, which is the same precedence
	 * {@link #scan} already applies when a seed appears in two directories. That falls out of the
	 * existing rule rather than adding a second notion of which directory is the reference one, and it
	 * has an answer for the index and temp directories, whose names follow whatever {@code --out} was.
	 */
	public Map<String, Map<String, List<Entry>>> sections(){
		Map<String, Map<String, List<Entry>>> sections = new LinkedHashMap<>();
		sections.put( SECTION_CORPUS, new LinkedHashMap<>() );
		sections.put( SECTION_TRAINER, new LinkedHashMap<>() );

		//in name order, and after the ungrouped output, so a listing reads the same way twice
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

	/** The distinct group ids recorded by the trainer, lowercased the way a heading uppercases them. */
	private Set<String> namedGroups(){
		Set<String> groups = new LinkedHashSet<>();
		for (Entry entry : entries){
			if (entry.sourceIndex != 0 && !entry.group.isEmpty()) groups.add( entry.group.toUpperCase( Locale.ROOT ));
		}
		return groups;
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
	 *
	 * <p><b>The filename is searched across every file, not across {@link #entries}.</b> {@code entries}
	 * is deduplicated to one per seed, which is right for a listing and wrong here: {@code IPW-QMA-AFY-r3.dat}
	 * is a file and a perfectly unambiguous name for it, and the only reason it was not found is that it
	 * shares a seed with three other files and so is not in the deduplicated list. The listing told the
	 * reader to type the file name; typing it answered "no such recording".
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
		Map<String, Map<String, List<Entry>>> sections = sections();
		StringBuilder out = new StringBuilder();

		int shown = 0;
		for (Map.Entry<String, Map<String, List<Entry>>> section : sections.entrySet()){
			int inSection = sectionCount( section.getValue() );
			if (inSection == 0) continue;

			//The provenance line is the reason the section exists. A heading that just said "trainer
			//output" would leave a person to discover the difference by picking a file and watching it
			//halt after one frame.
			//
			//Plain drops the colour and keeps the words. Removing the warning for the benefit of plain
			//output is backwards: a piped listing is read by a script or a log file, which is where a
			//note about a recording not playing is most likely to be wanted and least likely to be
			//discovered.
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
					//The note is dimmed rather than colour-coded: a warning here is about the file, not
					//about the score, and mixing the two in one column makes both harder to read.
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

//The copies have to be locatable, or refusing to resolve the name is a dead end.
		//
		//By file name, not by absolute path. A filename is what a person has in front of them - it is the
		//second column of the listing above - and byName already resolves one exactly, so a full path was
		//never necessary; the instruction only said so. It was also unreadable: 26 absolute paths, each
		//about a hundred characters of shared parent directory, printed after a ranked list and dwarfing
		//it. The parent is reduced to its directory name, which is the only part two copies can differ in.
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

	/** The directory a file sits in, by name - the part of the path two copies of one seed can differ in. */
	private static String parentName( Entry entry ){
		File parent = entry.file.getParentFile();
		return parent == null ? "" : parent.getName();
	}

	/** One line saying what a section is and what to expect from it, counting what cannot be played. */
	private String sectionBlurb( String section, Map<String, List<Entry>> groups ){
		if (SECTION_CORPUS.equals( section )){
			return "recorded through ReplayRecorder directly - these reproduce";
		}

		//The count is in the blurb because the blurb used to assert the opposite in plain sight. It
		//read "every one of them is watchable in the viewer" over a section that carried nine lines
		//marked not watchable - so the header and the list below it contradicted each other on the same
		//screen, and the header is the part a skimming reader takes away.
		int unwatchable = 0;
		for (List<Entry> hero : groups.values()){
			for (Entry entry : hero) if (!entry.watchable) unwatchable++;
		}

		//A group's heading already names the run; the blurb says what kind of recording that run left,
		//which is the one thing a reader of a heading cannot infer from it.
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

		//before the generation, because a name that does not identify one file is a bigger obstacle
		//to acting on this line than not knowing which generation wrote it
		if (entry.copies > 1){
			return "[WARN] " + entry.copies + " recordings share this seed - name the file"
					+ ( entry.generation > 0 ? ", gen " + entry.generation : "" );
		}

		if (entry.truncatedAt >= 0) return "[WARN] truncated at " + entry.truncatedAt + " of " + entry.steps;

		//Whether it can be watched at all, which is the only question a reader of this section has. The nine
		//trainer recordings written before the wire was fixed sent four of nine step fields and left the
		//rest at their sentinels, so a slot index in them resolves against nothing. The catalog used to
		//say only "NOT known to play end to end" for the whole section, which left a reader unable to
		//tell the one watchable file from the nine dead ones - and became wrong the moment a fixed
		//recording joined them.
		//
		//Deliberately without a [WARN] prefix, unlike every other note here. Nine of eleven recordings in
		//a section are in this state and none of them is going to be fixed, so a prefix would make the
		//exception the signal: a reader would come to expect the two clean lines to be the faulty ones.
		String watchable = entry.watchable ? "" : "not watchable ";

		if (entry.generation > 0) return watchable + "gen " + entry.generation;
		return entry.watchable ? "" : watchable.trim();
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

	/**
	 * Deepest floor reached, as a column rather than a bare {@code d2}.
	 *
	 * <p>It was already printed, inline and unlabelled, and it was the one column nobody could use: the
	 * listing ranks by score, so the run that actually made progress - the first to reach depth 2 in this
	 * project's history - sat in the middle of a score-ordered block with a {@code d2} that read as a
	 * footnote. A column with a heading is a column; a value in the middle of another column's padding is
	 * not.
	 *
	 * <p>{@code deepest}, not where the hero died. `Replay.depth` is `Statistics.deepestFloor`, so a run
	 * that went down and came back up reports the floor it reached rather than the one it left from.
	 */
	private static String formatDepth( Entry entry ){
		if (entry.error != null) return "-";
		return "d" + entry.depth;
	}

	/** Column headings, aligned to {@link #render}'s row format so the columns read as columns. */
	private static String columnHeadings(){
		return String.format( Locale.US,
				"  %-4s %-22s %9s  %5s  %9s  %7s",
				"#", "recording", "score", "depth", "recorded", "turns" );
	}

	/**
	 * When the file was written, as {@code MM-dd HH:mm}.
	 *
	 * <p>The filesystem's own modification time, and the answer is "when this file last changed", which
	 * for a recording written once is when the run produced it.
	 *
	 * <p>Asked for because the list alone cannot answer "which of these is new": every recording carries a
	 * generation number, and a run pointed at the same seed twice writes over the first, so a file's age
	 * was the only thing that distinguished a fresh training run's output from yesterday's. Width is fixed
	 * rather than formatted per locale, because the listing pads everything else to a column and one
	 * variable-width cell would break every row below it.
	 *
	 * <p><b>No space inside the value.</b> {@code 10-11 17:10} is two cells to anything that splits on
	 * whitespace, which makes the column ambiguous to read and impossible to assert on - the check that
	 * guards it had to treat the date and the time as separate fields. So {@code 10-11T17:10}, which is
	 * both one cell and still readable, and is what the default {@code java.time} ISO form prints anyway.
	 */
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
						.append( " times; pass a file name to pick one:" ).append( System.lineSeparator() );
				for (Entry entry : many){
					//a file name, which is what --resolve takes next. The absolute path is printed too,
					//because it is the one thing this output is unambiguous about and a batch file may
					//need it - but it is not required and the instruction must not say it is.
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
