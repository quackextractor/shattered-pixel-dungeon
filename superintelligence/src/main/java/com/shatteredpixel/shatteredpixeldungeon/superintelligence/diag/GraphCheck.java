package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.ProgressBar;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.WindowStatistics;

import java.util.ArrayList;
import java.util.List;

public class GraphCheck {

	private static final int CHECKS = 7;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		checkFlatSeriesReportsItselfAsFlat();
		checkFlatSeriesDoesNotWidenItsReportedRange();
		checkFlatSeriesIsNotBlank();
		checkVaryingSeriesStillReportsItsRealRange();
		checkTheProgressBarReportsWhatItMeasured();
		checkTheWindowStatisticsAreWhatTheyClaim();
		checkTheDepthRateCountsEpisodesNotGenerations();

		if (failures.isEmpty()){
			System.out.println( "[OK]     graph honesty: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  graph honesty: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	private static void checkTheWindowStatisticsAreWhatTheyClaim(){
		double[] window = { -3500, -3400, -3600, -3300, -3500, -3400 };

		if (Math.abs( WindowStatistics.mean( window ) + 3450 ) > 1e-9){
			fail( "a window of six scores whose mean is -3450 reports "
					+ WindowStatistics.mean( window ) );
			return;
		}

		double sd = WindowStatistics.sampleStdDev( window );
		if (Math.abs( sd - Math.sqrt( 55000.0 / 5 ) ) > 1e-9){
			fail( "the sample standard deviation of " + java.util.Arrays.toString( window ) + " is "
					+ sd + " where the n-1 form gives " + Math.sqrt( 55000.0 / 5 )
					+ ". A population deviation understates the spread of a small window and makes every"
					+ " error bar here too narrow." );
			return;
		}

		double sem = WindowStatistics.standardError( window );
		if (Math.abs( sem - sd / Math.sqrt( 6 ) ) > 1e-9){
			fail( "the standard error of a six-generation window is " + sem + " rather than s/sqrt(6) = "
					+ sd / Math.sqrt( 6 ) );
			return;
		}

		double[] other = { -3200, -3100, -3300, -3200 };
		double deltaError = WindowStatistics.differenceError( window, other );
		double expected = Math.sqrt( sem * sem + WindowStatistics.standardError( other ) * WindowStatistics.standardError( other ) );
		if (Math.abs( deltaError - expected ) > 1e-9){
			fail( "the error on the difference of two windows is " + deltaError + " rather than the"
					+ " quadrature sum " + expected + ". Adding the two standard errors instead would"
					+ " overstate it by about 40% on windows of this size." );
			return;
		}

		System.out.println( "  window statistics: mean -3450, sd " + String.format( "%.2f", sd )
				+ ", sem " + String.format( "%.2f", sem ) + ", difference error "
				+ String.format( "%.2f", deltaError ) + " on a deterministic fixture" );
	}


	private static void checkTheDepthRateCountsEpisodesNotGenerations(){
		double[] reached = { 1, 0, 3, 2 };
		double[] episodes = { 4, 4, 4, 4 };

		if (Math.abs( WindowStatistics.rate( reached, episodes ) - 0.375 ) > 1e-9){
			fail( "six episodes across four generations reached floor 2 and the rate reports "
					+ WindowStatistics.rate( reached, episodes ) + " rather than 0.375. Counting"
					+ " generations instead of episodes is how bestDepth became a coin flip." );
			return;
		}

		if (WindowStatistics.rate( new double[]{ 0, 0 }, new double[]{ 0, 0 } ) != 0){
			fail( "a window with no episodes reports a rate other than 0" );
			return;
		}

		System.out.println( "  depth rate counts episodes: 6 of 16 -> 0.375, and an empty window is 0" );
	}

	private static void checkFlatSeriesReportsItselfAsFlat(){
		float[] depth = { 1f, 1f, 1f, 1f, 1f, 1f };
		String axis = Graph.bar( depth, 24 ).axis();

		if (axis.contains( "2" )){
			fail( "a best-depth series that was 1 in all " + depth.length + " generations reports the"
					+ " axis as \"" + axis + "\", which contains a 2 that was never observed. That is how"
					+ " a depth-2 episode was reported that did not happen: every replay file and every"
					+ " metrics row said depth 1, and only this label disagreed." );
		}
	}

	private static void checkFlatSeriesDoesNotWidenItsReportedRange(){
		float[] values = { 2.5f, 2.5f, 2.5f };
		Graph.Row row = Graph.bar( values, 12 );

		if (row.max != row.min ){
			fail( "a flat series reports min=" + row.min + " max=" + row.max + ". The rendering may"
					+ " use any internal span it likes, but these are the observed bounds and they must"
					+ " be the observed bounds." );
			return;
		}

		if (row.max != 2.5f ){
			fail( "a flat series of 2.5 reports max=" + row.max + "." );
		}
	}

	private static void checkFlatSeriesIsNotBlank(){
		Graph.Row row = Graph.bar( new float[]{ 5f, 5f, 5f, 5f }, 8 );

		boolean anyMarked = false;
		for (int i = 0; i < row.text.length(); i++ ){
			if (row.text.charAt( i ) != ' ' ){ anyMarked = true; break; }
		}

		if (!anyMarked ){
			fail( "a constant series renders as all spaces (\"" + row.text + "\"), which is"
					+ " indistinguishable from a row with no data. A series that never moved is not the"
					+ " same fact as a series that was never measured." );
			return;
		}

		if (!row.text.equals( Graph.bar( new float[]{ 0f, 0f, 0f, 0f }, 8 ).text )){
			fail( "two constant series at different values rendered differently (\"" + row.text
					+ "\" vs \"" + Graph.bar( new float[]{ 0f, 0f, 0f, 0f }, 8 ).text
					+ "\"). With no range there is nothing to place a value against, so the bar cannot"
					+ " legitimately encode the magnitude." );
		}
	}

	private static void checkVaryingSeriesStillReportsItsRealRange(){
		float[] score = { 10f, 50f, 20f, 80f };
		Graph.Row row = Graph.bar( score, 16 );
		String axis = row.axis();

		if (row.min != 10f || row.max != 80f ){
			fail( "a varying series reports min=" + row.min + " max=" + row.max
					+ " where the data spans 10 to 80." );
			return;
		}

		if (!axis.contains( "10.0" ) || !axis.contains( "80.0" )){
			fail( "a varying series labels itself \"" + axis + "\" rather than its real 10.0..80.0." );
			return;
		}

		if (row.flat){
			fail( "a series spanning 10 to 80 is reported as flat." );
		}
	}

	private static void checkTheProgressBarReportsWhatItMeasured(){
		long now = System.currentTimeMillis();
		String start = ProgressBar.render( 0, 30, now, Double.NaN, 0 );
		String half = ProgressBar.render( 15, 30, now, -4200, 2 );
		String end = ProgressBar.render( 30, 30, now, 1200, 3 );
		String over = ProgressBar.render( 40, 30, now, 1200, 3 );

		if (!start.contains( "0%" ) || !half.contains( "50%" ) || !end.contains( "100%" )
				|| !over.contains( "100%" )){
			fail( "the progress bar's percentages are wrong: 0 -> '" + pct( start ) + "', 15/30 -> '"
					+ pct( half ) + "', 30/30 -> '" + pct( end ) + "', 40/30 -> '" + pct( over )
					+ "'. A run asked for 30 generations and given 40 must still say 100%, or a resumed run"
					+ " ends up with a bar that runs off the end." );
			return;
		}

		if (countCells( start ) != countCells( half ) || countCells( half ) != countCells( end )){
			fail( "the bar is not a fixed width (" + countCells( start ) + ", " + countCells( half )
					+ ", " + countCells( end ) + " cells), so the line jumps as the fraction changes." );
			return;
		}

		if (countCells( end ) != countCells( half )){
			return;
		}

		if (filled( half ) * 2 != filled( end )){
			fail( "the bar at half is " + filled( half ) + " cells filled and at the end " + filled( end )
					+ ", which is not double. The fraction and the picture disagree, and only one of them"
					+ " is what a reader believes." );
			return;
		}

		if (!ProgressBar.estimate( 0, 30, now ).equals( "-" )
				|| !ProgressBar.estimate( 30, 30, now ).equals( "-" )
				|| ProgressBar.estimate( 15, 30, now ).equals( "-" )){
			fail( "the estimate is '" + ProgressBar.estimate( 0, 30, now ) + "' before any generation has"
					+ " finished, '" + ProgressBar.estimate( 15, 30, now ) + "' half way and '"
					+ ProgressBar.estimate( 30, 30, now ) + "' at the end. It has to be blank at both ends -"
					+ " an estimate before the first division is invented rather than measured - and a"
					+ " duration in between." );
			return;
		}

		if (!start.contains( "score -" )){
			fail( "the bar before the first generation reports '" + start + "' instead of a dash for the"
					+ " score it has not measured." );
			return;
		}

		System.out.println( "  progress bar: 0% -> " + pct( start ) + ", 50% -> " + pct( half )
				+ ", 100% -> " + pct( end ) + ", " + countCells( end ) + " fixed cells, estimate blank"
				+ " until a generation finishes" );
	}

	private static String pct( String bar ){
		for (String word : bar.split( " " )) if (word.endsWith( "%" )) return word;
		return "(none)";
	}

	private static String cells( String bar ){
		int open = bar.indexOf( '[' );
		int close = bar.indexOf( ']', open );
		return open < 0 || close < 0 ? "" : bar.substring( open + 1, close );
	}

	private static int countCells( String bar ){
		int n = 0;
		for (char c : cells( bar ).toCharArray()) if (c == '#' || c == '-') n++;
		return n;
	}

	private static int filled( String bar ){
		int n = 0;
		for (char c : cells( bar ).toCharArray()) if (c == '#') n++;
		return n;
	}

	private static void fail( String message ){
		failures.add( message );
	}
}
