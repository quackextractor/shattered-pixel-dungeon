package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.CatalogLayout;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.CatalogView;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayCatalog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.dir;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.fail;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.write;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ReplayCatalogFixture.writeGrouped;

public final class ReplayCatalogViewCheck {

	private ReplayCatalogViewCheck(){}

	private static ReplayCatalog catalog;

	private static ReplayCatalog fixture(){
		if (catalog != null) return catalog;

		File corpus = dir( "view-corpus" );
		File trainer = dir( "view-trainer" );
		write( corpus, "VC-WARRIOR", "WARRIOR", 50.0, 2, 300 );
		write( corpus, "VC-MAGE", "MAGE", -10.0, 1, 40 );
		writeGrouped( trainer, "floor-1", "F1-DEEP", "WARRIOR", -3000.0, 3, 900 );
		writeGrouped( trainer, "floor-1", "F1-SHALLOW", "MAGE", -100.0, 1, 80 );
		writeGrouped( trainer, "floor-10", "F10-ONE", "ROGUE", -200.0, 2, 120 );
		writeGrouped( trainer, "pre-floor-1", "PRE-ONE", "ROGUE", 10.0, 1, 60 );

		catalog = new ReplayCatalog().corpus( corpus ).scan( List.of( corpus, trainer ));
		return catalog;
	}

	private static List<String> labels( CatalogView view ){
		List<String> labels = new ArrayList<>();
		for (ReplayCatalog.Entry entry : CatalogLayout.numbered( fixture(), view )) labels.add( entry.label() );
		return labels;
	}

	public static void checkNumbersFollowTheView(){
		CatalogView view = CatalogView.parse( "sort", "depth", "flat" );
		List<ReplayCatalog.Entry> numbered = CatalogLayout.numbered( fixture(), view );
		String listing = fixture().render( true, view );

		for (int i = 0; i < numbered.size(); i++){
			if (!listing.contains( String.format( "\n     %2d  %s", i + 1, numbered.get( i ).label() ))){
				fail( "under 'sort depth flat' row " + ( i + 1 ) + " is not " + numbered.get( i ).label()
						+ ", so --select with the same view would play a different file than the one shown" );
				return;
			}
		}

		List<String> floor1 = labels( CatalogView.parse( "only", "floor-1", "sort", "depth" ));
		if (!floor1.equals( Arrays.asList( "F1-DEEP", "F1-SHALLOW" ))){
			fail( "'only floor-1 sort depth' numbered " + floor1 + ", expected [F1-DEEP, F1-SHALLOW]" );
		}
	}

	public static void checkOnlySelectsOneGroupExactlyOrByPrefix(){
		List<String> exact = labels( CatalogView.parse( "only floor-1" ));
		if (exact.contains( "F10-ONE" ) || exact.contains( "PRE-ONE" ) || exact.size() != 2){
			fail( "'only floor-1' listed " + exact + ": a group whose name merely starts with or contains"
					+ " floor-1 leaked in beside the one asked for" );
			return;
		}

		List<String> prefix = labels( CatalogView.parse( "only floor" ));
		if (prefix.size() != 3 || prefix.contains( "PRE-ONE" )){
			fail( "'only floor' listed " + prefix + ", expected floor-1 and floor-10 and nothing from pre-floor-1" );
			return;
		}

		List<String> corpus = labels( CatalogView.parse( "only corpus" ));
		if (!corpus.equals( Arrays.asList( "VC-WARRIOR", "VC-MAGE" ))){
			fail( "'only corpus' listed " + corpus );
		}
	}

	public static void checkACollapsedGroupIsOneLineAndUnnumbered(){
		CatalogView view = CatalogView.parse( "collapse all expand corpus" );
		String listing = fixture().render( true, view );
		List<String> numbered = labels( view );

		if (!numbered.equals( Arrays.asList( "VC-WARRIOR", "VC-MAGE" ))){
			fail( "with every group but the corpus collapsed the numbering spans " + numbered );
			return;
		}
		if (listing.contains( "F1-DEEP" ) || !listing.contains( "GROUP FLOOR-1  [+] 2 recordings" )){
			fail( "a collapsed group should show one summary line and none of its rows:\n" + listing );
			return;
		}
		if (!listing.contains( "deepest d3" )){
			fail( "the collapsed floor-1 summary does not report its deepest floor, d3" );
		}
	}

	public static void checkFiltersNarrowRows(){
		check( "hero rogue", "F10-ONE", "PRE-ONE" );
		check( "depth 2-3 sort depth", "VC-WARRIOR", "F1-DEEP", "F10-ONE" );
		check( "score -250..-50 flat", "F1-SHALLOW", "F10-ONE" );
		check( "find shallow", "F1-SHALLOW" );
		check( "only floor-1 top 1 flat", "F1-SHALLOW" );
	}

	private static void check( String command, String... expected ){
		List<String> got = labels( CatalogView.parse( command ));
		if (!got.equals( Arrays.asList( expected ))){
			fail( "'" + command + "' numbered " + got + ", expected " + Arrays.asList( expected ));
		}
	}

	public static void checkAViewSurvivesBeingSaved(){
		CatalogView view = CatalogView.parse( "sort turns asc only floor hide floor-10 collapse corpus hero mage depth 1-2 score -3000..0 find f1 top 4 order newest flat" );
		File file = new File( ReplayCatalogFixture.workDir, "catalog-view.txt" );
		try {
			view.save( file );
		} catch (IOException e){
			fail( "could not save a view: " + e.getMessage() );
			return;
		}
		String reloaded = CatalogView.load( file ).toString();
		if (!reloaded.equals( view.toString() )){
			fail( "a saved view reloads as '" + reloaded + "' instead of '" + view + "'" );
		}
	}

	public static void checkABadCommandIsRefusedByName(){
		String unknown = new CatalogView().apply( List.of( "srot depth" ));
		String badValue = new CatalogView().apply( List.of( "sort colour" ));
		String missing = new CatalogView().apply( List.of( "only" ));
		if (unknown == null || !unknown.contains( "srot" ) || badValue == null || missing == null){
			fail( "a mistyped command must be refused and named, got: " + unknown + " / " + badValue + " / " + missing );
		}
	}
}
