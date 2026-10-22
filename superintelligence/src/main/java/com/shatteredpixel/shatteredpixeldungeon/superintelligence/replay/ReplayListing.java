package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayCatalog.Entry;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ReplayListing {

	private ReplayListing(){}

	static String render( ReplayCatalog catalog, boolean plain ){
		return render( catalog, plain, new CatalogView() );
	}

	static String render( ReplayCatalog catalog, boolean plain, CatalogView view ){
		List<CatalogLayout.Section> sections = CatalogLayout.sections( catalog, view );
		StringBuilder out = new StringBuilder();

		int shown = 0, matched = 0, folded = 0;
		List<Entry> visible = new ArrayList<>();
		boolean previousCollapsed = false;
		for (CatalogLayout.Section section : sections){
			matched += section.matched.size();

			if (section.collapsed){
				folded++;
				out.append( previousCollapsed ? "  " : "\n  " ).append( section.title ).append( "  " )
						.append( dim( "[+] " + summary( section ), plain )).append( "\n" );
				previousCollapsed = true;
				continue;
			}
			previousCollapsed = false;

			out.append( "\n  " ).append( section.title ).append( " " )
					.append( dim( "  " + sectionBlurb( section.title, section.all ), plain )).append( "\n" );
			out.append( "  " ).append( dim( "[-] " + summary( section ), plain )).append( "\n" );
			out.append( "  " ).append( dashed() ).append( "\n" );
			out.append( "  " ).append( columnHeadings() ).append( "\n" );

			for (Map.Entry<String, List<Entry>> list : section.lists.entrySet()){
				if (!list.getKey().isEmpty()) out.append( "\n    " ).append( list.getKey() ).append( "\n" );
				else out.append( "\n" );

				for (Entry entry : list.getValue()){
					shown++;
					visible.add( entry );

					String note = note( entry );
					if (list.getKey().isEmpty()) note = ( entry.heroClass == null ? "?" : entry.heroClass.toLowerCase( Locale.ROOT )) + ( note.isEmpty() ? "" : "  " + note );
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

		if (catalog.isEmpty()) return "  no recordings found\n";
		if (sections.isEmpty()){
			return "  no recordings match the view: " + view + "\n  reset clears it, h lists the commands\n";
		}

		int total = catalog.entries().size();
		out.append( "\n" );
		if (view.isDefault()){
			out.append( shown ).append( " recording" ).append( shown == 1 ? "" : "s" )
					.append( ", ranked by score within each section" );
		} else {
			out.append( shown ).append( " of " ).append( total ).append( " recordings shown" )
					.append( matched > shown ? " (" + matched + " match)" : "" )
					.append( ", sorted by " ).append( view.describeSort() );
		}
		if (shown > 0) out.append( ", numbered 1 to " ).append( shown );
		out.append( "\n" );
		if (!view.isDefault()) out.append( "  view: " ).append( view ).append( "\n" );
		if (folded > 0){
			out.append( "  " ).append( folded ).append( folded == 1 ? " group is" : " groups are" )
					.append( " collapsed: expand <group> or expand all lists their recordings\n" );
		}

		List<Entry> shared = new ArrayList<>();
		for (Entry entry : visible){
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

	private static String dim( String text, boolean plain ){
		return plain ? text : Ansi.wrap( text, Ansi.DIM );
	}

	private static String summary( CatalogLayout.Section section ){
		int matched = section.matched.size();
		StringBuilder line = new StringBuilder();
		line.append( matched ).append( matched == section.all.size() ? "" : " of " + section.all.size() )
				.append( matched == 1 ? " recording" : " recordings" );
		double best = CatalogLayout.best( section );
		if (best > Double.NEGATIVE_INFINITY) line.append( ", best " ).append( String.format( Locale.US, "%,.1f", best ));
		line.append( ", deepest d" ).append( CatalogLayout.deepest( section ));
		long newest = CatalogLayout.newest( section );
		if (newest > 0) line.append( ", newest " ).append( formatInstant( newest ));
		int shown = section.shown();
		if (!section.collapsed && shown < matched) line.append( ", top " ).append( shown ).append( " shown" );
		line.append( "  [" ).append( section.id ).append( "]" );
		return line.toString();
	}

	private static String parentName( Entry entry ){
		File parent = entry.file.getParentFile();
		return parent == null ? "" : parent.getName();
	}


	private static String sectionBlurb( String section, List<Entry> entries ){
		if (ReplayCatalog.SECTION_CORPUS.equals( section )){
			return "recorded through ReplayRecorder directly - these reproduce";
		}

		int unwatchable = 0;
		for (Entry entry : entries) if (!entry.watchable) unwatchable++;

		String origin = section.startsWith( ReplayCatalog.GROUP_PREFIX )
				? "written by training run group " + section.substring( ReplayCatalog.GROUP_PREFIX.length() ).toLowerCase( Locale.ROOT ) + " - these reproduce"
				: "written by the trainer through the worker pipe - these reproduce";

		if (unwatchable == 0){
			return origin + ", and every one of them is watchable in the viewer";
		}

		return origin + " too. " + unwatchable
				+ " of " + entries.size() + ", marked 'not watchable', were written before the"
				+ " replay wire carried all nine step fields and nothing can recover that"
				+ " (issues.md 15)";
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
		return modified <= 0 ? "-" : formatInstant( modified );
	}

	private static String formatInstant( long modified ){
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
		File viewFile = null;
		List<String> viewTokens = new ArrayList<>();
		List<String> apply = null;

		for (int i = 0; i < args.length; i++ ){
			if ("--plain".equals( args[ i ] )){
				plain = true;
			} else if ("--dir".equals( args[ i ] ) && i + 1 < args.length){
				directories.add( new File( args[ ++i ] ));
			} else if ("--select".equals( args[ i ] ) && i + 1 < args.length){
				select = args[ ++i ];
			} else if ("--resolve".equals( args[ i ] ) && i + 1 < args.length){
				resolve = args[ ++i ];
			} else if ("--view-file".equals( args[ i ] ) && i + 1 < args.length){
				viewFile = new File( args[ ++i ] );
			} else if ("--apply".equals( args[ i ] )){
				apply = new ArrayList<>( Arrays.asList( args ).subList( i + 1, args.length ));
				break;
			} else {
				viewTokens.add( args[ i ] );
			}
		}

		CatalogView view = CatalogView.load( viewFile );
		String bad = view.apply( viewTokens );
		if (bad != null){
			System.err.println( "[ERROR] " + bad );
			System.exit( 1 );
			return;
		}

		if (apply != null){
			System.exit( applyAndSave( view, apply, viewFile ));
			return;
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

			List<Entry> numbered = CatalogLayout.numbered( catalog, view );
			Entry entry = number < 1 || number > numbered.size() ? null : numbered.get( number - 1 );
			if (entry == null){
				System.err.println( "no recording numbered " + number
						+ " (" + numbered.size() + " available)" );
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

		System.out.print( render( catalog, plain, view ));

		if (catalog.isEmpty()) System.exit( 1 );
	}

	private static int applyAndSave( CatalogView view, List<String> command, File viewFile ){
		String line = String.join( " ", command ).trim().toLowerCase( Locale.ROOT );
		if (line.isEmpty() || line.equals( "h" ) || line.equals( "help" ) || line.equals( "?" )){
			System.out.println( CatalogView.HELP );
			return 1;
		}

		String error = view.apply( command );
		if (error != null){
			System.out.println( "[ERROR] " + error );
			return 1;
		}

		if (viewFile != null){
			try {
				view.save( viewFile );
			} catch (IOException e){
				System.out.println( "[ERROR] could not save the view to " + viewFile + ": " + e.getMessage() );
				return 1;
			}
		}
		System.out.println( "[OK]     view: " + ( view.isDefault() ? "default" : view.toString() ));
		return 0;
	}
}
