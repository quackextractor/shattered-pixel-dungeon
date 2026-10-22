package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayCatalog.Entry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CatalogLayout {

	public static final class Section {
		public final String title;
		public final String id;
		public final List<Entry> all;
		public final List<Entry> matched;
		public final Map<String, List<Entry>> lists;
		public final boolean collapsed;

		Section( String title, String id, List<Entry> all, List<Entry> matched,
				Map<String, List<Entry>> lists, boolean collapsed ){
			this.title = title;
			this.id = id;
			this.all = all;
			this.matched = matched;
			this.lists = lists;
			this.collapsed = collapsed;
		}

		public int shown(){
			int n = 0;
			for (List<Entry> list : lists.values()) n += list.size();
			return n;
		}
	}

	private CatalogLayout(){}

	public static String idOf( String title ){
		if (ReplayCatalog.SECTION_CORPUS.equals( title )) return "corpus";
		if (ReplayCatalog.SECTION_TRAINER.equals( title )) return "trainer";
		return title.substring( ReplayCatalog.GROUP_PREFIX.length() ).toLowerCase( Locale.ROOT );
	}

	public static List<Section> sections( ReplayCatalog catalog, CatalogView view ){
		Map<String, Map<String, List<Entry>>> byTitle = catalog.sections();

		List<String> ids = new ArrayList<>();
		for (String title : byTitle.keySet()) ids.add( idOf( title ));

		List<Section> sections = new ArrayList<>();
		for (Map.Entry<String, Map<String, List<Entry>>> pair : byTitle.entrySet()){
			String id = idOf( pair.getKey() );
			if (!view.only.isEmpty() && !CatalogView.selects( view.only, id, ids )) continue;
			if (CatalogView.selects( view.hidden, id, ids )) continue;

			List<Entry> all = new ArrayList<>();
			for (List<Entry> hero : pair.getValue().values()) all.addAll( hero );
			if (all.isEmpty()) continue;

			List<Entry> matched = new ArrayList<>();
			for (Entry entry : all) if (view.matches( entry )) matched.add( entry );
			if (matched.isEmpty()) continue;
			matched.sort( view.order() );

			Map<String, List<Entry>> lists = new LinkedHashMap<>();
			if (view.flat){
				lists.put( "", cap( matched, view.top ));
			} else {
				for (String hero : pair.getValue().keySet()) lists.put( hero, new ArrayList<>() );
				for (Entry entry : matched){
					String hero = entry.heroClass == null ? "?" : entry.heroClass.toUpperCase();
					lists.computeIfAbsent( hero, k -> new ArrayList<>() ).add( entry );
				}
				lists.values().removeIf( List::isEmpty );
				lists.replaceAll( ( hero, rows ) -> cap( rows, view.top ));
			}

			boolean collapsed = view.collapseAll
					? !CatalogView.selects( view.expanded, id, ids )
					: CatalogView.selects( view.collapsed, id, ids );
			sections.add( new Section( pair.getKey(), id, all, matched, lists, collapsed ));
		}

		List<Section> groups = new ArrayList<>();
		for (Section section : sections) if (section.title.startsWith( ReplayCatalog.GROUP_PREFIX )) groups.add( section );
		sections.removeAll( groups );
		groups.sort( groupOrder( view.order ));
		sections.addAll( groups );
		return sections;
	}

	private static Comparator<Section> groupOrder( CatalogView.GroupOrder order ){
		Comparator<Section> byName = Comparator.comparing( s -> s.id );
		switch (order){
			case NEWEST: return Comparator.comparingLong( CatalogLayout::newest ).reversed().thenComparing( byName );
			case BEST: return Comparator.comparingDouble( CatalogLayout::best ).reversed().thenComparing( byName );
			case SIZE: return Comparator.comparingInt( ( Section s ) -> s.matched.size() ).reversed().thenComparing( byName );
			default: return byName;
		}
	}

	private static List<Entry> cap( List<Entry> rows, int top ){
		return top > 0 && rows.size() > top ? new ArrayList<>( rows.subList( 0, top )) : rows;
	}

	public static List<Entry> numbered( ReplayCatalog catalog, CatalogView view ){
		List<Entry> ordered = new ArrayList<>();
		for (Section section : sections( catalog, view )){
			if (section.collapsed) continue;
			for (List<Entry> rows : section.lists.values()) ordered.addAll( rows );
		}
		return ordered;
	}

	public static double best( Section section ){
		double best = Double.NEGATIVE_INFINITY;
		for (Entry entry : section.matched) if (entry.error == null) best = Math.max( best, entry.score );
		return best;
	}

	public static int deepest( Section section ){
		int deepest = 0;
		for (Entry entry : section.matched) deepest = Math.max( deepest, entry.depth );
		return deepest;
	}

	public static long newest( Section section ){
		long newest = 0;
		for (Entry entry : section.matched) newest = Math.max( newest, entry.file.lastModified() );
		return newest;
	}
}
