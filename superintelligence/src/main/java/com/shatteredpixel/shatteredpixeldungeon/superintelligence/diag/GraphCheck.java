package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import java.util.ArrayList;
import java.util.List;

/**
 * A chart must not report a range it did not observe.
 *
 * <pre>gradle :superintelligence:graphcheck</pre>
 *
 * <p><b>Why this is a gate.</b> {@code Graph.bar} widened the scale by 1 whenever the series was flat,
 * so that the division in the loop was defined - and then printed that widened scale as the axis
 * range. A run whose best depth was 1.0 in every single generation printed
 * {@code depth 1.0..2.0}, which reads as "reached depth 2".
 *
 * <p>It was believed. A depth-2 episode was reported here, in the README, in a plan document and in
 * the changelog, on the strength of that one number. Every underlying measurement said otherwise:
 * {@code bestDepth=1} in all 147 replay files and in every generation of every metrics CSV. Nothing
 * disagreed with the chart, so nothing caught it.
 *
 * <p>A trend line is a summary a reader trusts more than the raw column, which is what makes it the
 * dangerous place for a fudge to live. The fudge existed to protect the arithmetic; it should have
 * lived there and nowhere else.
 */
public class GraphCheck {

	private static final int CHECKS = 4;

	private static final List< String > failures = new ArrayList<>();

	public static void main( String[] args ){
		checkFlatSeriesReportsItselfAsFlat();
		checkFlatSeriesDoesNotWidenItsReportedRange();
		checkFlatSeriesIsNotBlank();
		checkVaryingSeriesStillReportsItsRealRange();

		if (failures.isEmpty()){
			System.out.println( "[OK]     graph honesty: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  graph honesty: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	/**
	 * The exact series that produced the false claim: best depth 1 in every generation.
	 *
	 * <p>Asserted on the label rather than on {@code min}/{@code max} alone, because the old code got
	 * those fields right and then lied in the string a human reads.
	 */
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

	/** The reported bounds must equal the data, whichever way the row is drawn. */
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

	/**
	 * A constant series must not render as blank.
	 *
	 * <p>With a zero span the old arithmetic put every bar on the darkest rung, which is a space - the
	 * same thing an absent row draws. A flat series therefore looked like no data at all, which is the
	 * other half of the same lie: not just an inflated axis label, but a row that read as empty.
	 *
	 * <p>Two constant series at different values render identically on purpose. There is no scale to
	 * place either on, so the glyph cannot say which value it was - that is what the axis label is
	 * for. Asserting they differ would be asserting that a chart invented a distinction it cannot
	 * have.
	 */
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

	/** The ordinary case must keep working, or the fix has simply disabled the chart. */
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

	private static void fail( String message ){
		failures.add( message );
	}
}
