package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayCatalog.Entry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CatalogView {

	public enum Sort {
		SCORE( false ), DEPTH( false ), TURNS( false ), STEPS( false ),
		RECORDED( false ), GEN( false ), NAME( true ), HERO( true );

		final boolean ascendingByDefault;

		Sort( boolean ascendingByDefault ){
			this.ascendingByDefault = ascendingByDefault;
		}
	}

	public enum GroupOrder { NAME, NEWEST, BEST, SIZE }

	public static final String HELP = String.join( "\n",
			"  sort <score|depth|turns|steps|recorded|gen|name|hero> [asc|desc]   order rows in each list",
			"  asc | desc                         flip the current sort direction",
			"  only <group>[,<group>]             list those groups alone (corpus, trainer or a run id; a prefix such as floor matches floor-1, floor-2...)",
			"  hide <group>[,<group>] | show <group|all>   drop groups from the listing, or bring them back",
			"  collapse <group|all> | expand <group|all>   fold a group to one summary line, or unfold it",
			"  hero <class|all>                   one hero class only",
			"  depth <n> | depth <n>-<m>          deepest floor at least n, or between n and m",
			"  score <min>..<max>                 either side optional, eg score 0.. or score ..-3000",
			"  gen <n> | since <yyyy-mm-dd|3d|12h> | find <text>   generation, recorded since, name/seed/group contains",
			"  watchable | broken | dupes         only playable, only damaged/unwatchable, only seeds recorded twice",
			"  flat | nested                      one list per group, or one per hero class (default)",
			"  top <n>                            first n rows of each list, 0 for all",
			"  order <name|newest|best|size>      order of the run groups",
			"  <filter> off                       clear one filter, eg depth off;  reset clears everything",
			"  Commands combine on one line: only fresh-1022 sort depth top 5" );

	Sort sort = Sort.SCORE;
	Boolean ascending;
	GroupOrder order = GroupOrder.NAME;
	String hero;
	final Set<String> only = new LinkedHashSet<>();
	final Set<String> hidden = new LinkedHashSet<>();
	final Set<String> collapsed = new LinkedHashSet<>();
	final Set<String> expanded = new LinkedHashSet<>();
	boolean collapseAll;
	Integer minDepth, maxDepth, gen;
	Double minScore, maxScore;
	String since, text;
	boolean watchable, broken, dupes, flat;
	int top;

	public static CatalogView parse( String... tokens ){
		CatalogView view = new CatalogView();
		String error = view.apply( Arrays.asList( tokens ));
		if (error != null) throw new IllegalArgumentException( error );
		return view;
	}

	public String apply( List<String> tokens ){
		List<String> words = new ArrayList<>();
		for (String token : tokens){
			for (String word : token.trim().split( "\\s+" )){
				if (!word.isEmpty()) words.add( word.replaceFirst( "^--", "" ).toLowerCase( Locale.ROOT ));
			}
		}

		for (int i = 0; i < words.size(); i++){
			String word = words.get( i );
			String next = i + 1 < words.size() ? words.get( i + 1 ) : null;
			boolean off = "off".equals( next ) || "all".equals( next );

			try {
				switch (word){
					case "sort":
						if (next == null) return "sort needs a key: " + keys( Sort.values() );
						sort = Sort.valueOf( next.toUpperCase( Locale.ROOT ));
						ascending = null;
						i++;
						if (i + 1 < words.size() && ( "asc".equals( words.get( i + 1 )) || "desc".equals( words.get( i + 1 )))){
							ascending = "asc".equals( words.get( ++i ));
						}
						break;
					case "asc": ascending = true; break;
					case "desc": ascending = false; break;
					case "order":
						if (next == null) return "order needs one of " + keys( GroupOrder.values() );
						order = GroupOrder.valueOf( next.toUpperCase( Locale.ROOT ));
						i++;
						break;
					case "hero":
						if (next == null) return "hero needs a class or all";
						hero = off ? null : next.toUpperCase( Locale.ROOT );
						i++;
						break;
					case "only": case "group":
						if (next == null) return word + " needs a group name or all";
						only.clear();
						if (!off) only.addAll( names( next ));
						i++;
						break;
					case "hide":
						if (next == null) return "hide needs a group name";
						hidden.addAll( names( next ));
						i++;
						break;
					case "show":
						if (next == null) return "show needs a group name or all";
						if (off) hidden.clear(); else hidden.removeAll( names( next ));
						i++;
						break;
					case "collapse":
						if (next == null) return "collapse needs a group name or all";
						if ("all".equals( next )){
							collapseAll = true;
							expanded.clear();
							collapsed.clear();
						} else {
							collapsed.addAll( names( next ));
							expanded.removeAll( names( next ));
						}
						i++;
						break;
					case "expand":
						if (next == null) return "expand needs a group name or all";
						if ("all".equals( next )){
							collapseAll = false;
							expanded.clear();
							collapsed.clear();
						} else {
							expanded.addAll( names( next ));
							collapsed.removeAll( names( next ));
						}
						i++;
						break;
					case "depth":
						if (next == null) return "depth needs n, n-m or off";
						minDepth = maxDepth = null;
						if (!off){
							String[] range = next.split( "-", -1 );
							if (!range[ 0 ].isEmpty()) minDepth = Integer.parseInt( range[ 0 ] );
							if (range.length > 1 && !range[ 1 ].isEmpty()) maxDepth = Integer.parseInt( range[ 1 ] );
						}
						i++;
						break;
					case "score":
						if (next == null) return "score needs min..max or off";
						minScore = maxScore = null;
						if (!off){
							if (!next.contains( ".." )) return "score takes min..max, eg score -3000..";
							String[] range = next.split( "\\.\\.", -1 );
							if (!range[ 0 ].isEmpty()) minScore = Double.parseDouble( range[ 0 ] );
							if (!range[ 1 ].isEmpty()) maxScore = Double.parseDouble( range[ 1 ] );
						}
						i++;
						break;
					case "gen":
						if (next == null) return "gen needs a number or off";
						gen = off ? null : Integer.valueOf( next );
						i++;
						break;
					case "since":
						if (next == null) return "since needs yyyy-mm-dd, <n>d, <n>h or off";
						if (!off && sinceMillis( next ) < 0) return "since takes yyyy-mm-dd, <n>d or <n>h, not " + next;
						since = off ? null : next;
						i++;
						break;
					case "find":
						if (next == null) return "find needs text or off";
						text = "off".equals( next ) ? null : next;
						i++;
						break;
					case "watchable": watchable = !"off".equals( next ); if ("off".equals( next )) i++; break;
					case "broken": broken = !"off".equals( next ); if ("off".equals( next )) i++; break;
					case "dupes": dupes = !"off".equals( next ); if ("off".equals( next )) i++; break;
					case "flat": flat = true; break;
					case "nested": flat = false; break;
					case "top":
						if (next == null) return "top needs a number";
						top = off ? 0 : Math.max( 0, Integer.parseInt( next ));
						i++;
						break;
					case "reset":
						copyFrom( new CatalogView() );
						break;
					default:
						return "unknown command '" + word + "'; h lists them";
				}
			} catch (IllegalArgumentException e){
				return word + ": '" + next + "' is not a value it takes";
			}
		}
		return null;
	}

	private void copyFrom( CatalogView other ){
		sort = other.sort; ascending = other.ascending; order = other.order; hero = other.hero;
		only.clear(); hidden.clear(); collapsed.clear(); expanded.clear();
		collapseAll = other.collapseAll;
		minDepth = other.minDepth; maxDepth = other.maxDepth; gen = other.gen;
		minScore = other.minScore; maxScore = other.maxScore;
		since = other.since; text = other.text;
		watchable = other.watchable; broken = other.broken; dupes = other.dupes; flat = other.flat;
		top = other.top;
	}

	private static List<String> names( String list ){
		List<String> names = new ArrayList<>();
		for (String name : list.split( "," )) if (!name.isEmpty()) names.add( name );
		return names;
	}

	private static String keys( Enum<?>[] values ){
		List<String> keys = new ArrayList<>();
		for (Enum<?> value : values) keys.add( value.name().toLowerCase( Locale.ROOT ));
		return String.join( "|", keys );
	}

	static long sinceMillis( String spec ){
		try {
			if (spec.matches( "\\d+d" )) return System.currentTimeMillis() - Long.parseLong( spec.substring( 0, spec.length() - 1 )) * 86_400_000L;
			if (spec.matches( "\\d+h" )) return System.currentTimeMillis() - Long.parseLong( spec.substring( 0, spec.length() - 1 )) * 3_600_000L;
			return LocalDate.parse( spec ).atStartOfDay( ZoneId.systemDefault() ).toInstant().toEpochMilli();
		} catch (RuntimeException e){
			return -1;
		}
	}

	public boolean isDefault(){
		return toString().isEmpty();
	}

	public boolean matches( Entry entry ){
		if (hero != null && !hero.equalsIgnoreCase( entry.heroClass )) return false;
		if (minDepth != null && entry.depth < minDepth) return false;
		if (maxDepth != null && entry.depth > maxDepth) return false;
		if (minScore != null && !(entry.score >= minScore)) return false;
		if (maxScore != null && !(entry.score <= maxScore)) return false;
		if (gen != null && entry.generation != gen) return false;
		if (since != null && entry.file.lastModified() < sinceMillis( since )) return false;
		if (watchable && (entry.damaged() || !entry.watchable)) return false;
		if (broken && !(entry.damaged() || !entry.watchable)) return false;
		if (dupes && entry.copies < 2) return false;
		if (text != null){
			String haystack = ( entry.label() + " " + entry.seed + " " + entry.group ).toLowerCase( Locale.ROOT );
			if (!haystack.contains( text )) return false;
		}
		return true;
	}

	static boolean selects( Collection<String> patterns, String id, Collection<String> ids ){
		for (String pattern : patterns){
			if (pattern.equals( id )) return true;
			if (!ids.contains( pattern ) && id.startsWith( pattern )) return true;
		}
		return false;
	}

	public Comparator<Entry> order(){
		Comparator<Entry> key;
		switch (sort){
			case DEPTH: key = Comparator.comparingInt( e -> e.depth ); break;
			case TURNS: key = Comparator.comparingInt( e -> e.turns ); break;
			case STEPS: key = Comparator.comparingInt( e -> e.steps ); break;
			case RECORDED: key = Comparator.comparingLong( e -> e.file.lastModified() ); break;
			case GEN: key = Comparator.comparingInt( e -> e.generation ); break;
			case NAME: key = Comparator.comparing( Entry::label, String.CASE_INSENSITIVE_ORDER ); break;
			case HERO: key = Comparator.comparing( e -> String.valueOf( e.heroClass )); break;
			default: key = Comparator.comparingDouble( e -> e.score ); break;
		}
		if (!ascending()) key = key.reversed();

		return Comparator.comparing( ( Entry e ) -> e.error != null )
				.thenComparing( key )
				.thenComparingDouble( e -> e.error != null ? Double.MAX_VALUE : -e.score )
				.thenComparingInt( e -> -e.depth )
				.thenComparing( Entry::label );
	}

	boolean ascending(){
		return ascending != null ? ascending : sort.ascendingByDefault;
	}

	public String describeSort(){
		return sort.name().toLowerCase( Locale.ROOT ) + ( ascending() ? " ascending" : " descending" );
	}

	@Override
	public String toString(){
		List<String> parts = new ArrayList<>();
		if (sort != Sort.SCORE || ascending != null){
			parts.add( "sort " + sort.name().toLowerCase( Locale.ROOT ) + ( ascending == null ? "" : ascending ? " asc" : " desc" ));
		}
		if (order != GroupOrder.NAME) parts.add( "order " + order.name().toLowerCase( Locale.ROOT ));
		if (!only.isEmpty()) parts.add( "only " + String.join( ",", only ));
		if (!hidden.isEmpty()) parts.add( "hide " + String.join( ",", hidden ));
		if (collapseAll) parts.add( "collapse all" );
		if (!collapsed.isEmpty()) parts.add( "collapse " + String.join( ",", collapsed ));
		if (!expanded.isEmpty()) parts.add( "expand " + String.join( ",", expanded ));
		if (hero != null) parts.add( "hero " + hero.toLowerCase( Locale.ROOT ));
		if (minDepth != null || maxDepth != null){
			parts.add( "depth " + ( minDepth == null ? "" : minDepth ) + ( maxDepth == null ? "" : "-" + maxDepth ));
		}
		if (minScore != null || maxScore != null){
			parts.add( "score " + ( minScore == null ? "" : number( minScore )) + ".." + ( maxScore == null ? "" : number( maxScore )));
		}
		if (gen != null) parts.add( "gen " + gen );
		if (since != null) parts.add( "since " + since );
		if (text != null) parts.add( "find " + text );
		if (watchable) parts.add( "watchable" );
		if (broken) parts.add( "broken" );
		if (dupes) parts.add( "dupes" );
		if (flat) parts.add( "flat" );
		if (top > 0) parts.add( "top " + top );
		return String.join( " ", parts );
	}

	private static String number( double value ){
		return value == Math.rint( value ) ? String.valueOf( (long) value ) : String.valueOf( value );
	}

	public static CatalogView load( File file ){
		CatalogView view = new CatalogView();
		if (file == null || !file.isFile()) return view;
		try {
			if (view.apply( Files.readAllLines( file.toPath(), StandardCharsets.UTF_8 )) != null) return new CatalogView();
		} catch (IOException e){
			return new CatalogView();
		}
		return view;
	}

	public void save( File file ) throws IOException {
		File parent = file.getAbsoluteFile().getParentFile();
		if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException( "cannot create " + parent );
		Files.write( file.toPath(), ( toString() + System.lineSeparator() ).getBytes( StandardCharsets.UTF_8 ));
	}
}
