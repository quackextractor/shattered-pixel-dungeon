package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayCatalog.Entry;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ReplayListing {

	private ReplayListing(){}

	static String render( ReplayCatalog catalog, boolean plain ){
		Map<String, Map<String, List<Entry>>> sections = catalog.sections();
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
		for (Entry entry : catalog.entries()){
			if (entry.copies > 1) shared.add( entry );
		}
		if (!shared.isEmpty()){
			out.append( "\n  " ).append( shared.size() ).append( " seed" )
					.append( shared.size() == 1 ? " is" : "s are" )
					.append( " recorded more than once. Type the file name to pick one:\n\n" );

			for (Entry entry : shared){
				boolean first = true;
				for (Entry copy : copiesFor( catalog, entry )){
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


	private static String sectionBlurb( String section, Map<String, List<Entry>> groups ){
		if (ReplayCatalog.SECTION_CORPUS.equals( section )){
			return "recorded through ReplayRecorder directly - these reproduce";
		}

		int unwatchable = 0;
		for (List<Entry> hero : groups.values()){
			for (Entry entry : hero) if (!entry.watchable) unwatchable++;
		}

		String origin = section.startsWith( ReplayCatalog.GROUP_PREFIX )
				? "written by training run group " + section.substring( ReplayCatalog.GROUP_PREFIX.length() ).toLowerCase( Locale.ROOT ) + " - these reproduce"
				: "written by the trainer through the worker pipe - these reproduce";

		if (unwatchable == 0){
			return origin + ", and every one of them is watchable in the viewer";
		}

		return origin + " too. " + unwatchable
				+ " of " + sectionCount( groups ) + ", marked 'not watchable', were written before the"
				+ " replay wire carried all nine step fields and nothing can recover that"
				+ " (issues.md 15)";
	}

	private static int sectionCount( Map<String, List<Entry>> groups ){
		int n = 0;
		for (List<Entry> list : groups.values()) n += list.size();
		return n;
	}

	private static String note( Entry entry ){
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


	private static List<Entry> copiesFor( ReplayCatalog catalog, Entry entry ){
		String key = entry.seed == null || entry.seed.isEmpty() ? entry.file.getName() : entry.seed;

		for (Map.Entry<String, List<Entry>> pair : catalog.copies().entrySet()){
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
			directories = ReplayCatalog.defaultDirectories();
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

		System.out.print( render( catalog, plain ));

		if (catalog.isEmpty()) System.exit( 1 );
	}
}
