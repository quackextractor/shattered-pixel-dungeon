package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import java.util.Locale;

/**
 * ASCII bar and sparkline rendering for the trainer dashboard.
 *
 * docs.md: "graphs depicting score over turns (annotated with floor transitions) or score over
 * floors."
 *
 * Rendered with block characters so the shape survives a terminal that does not handle colour or
 * a font with box-drawing glyphs. Each series is normalised over its own range, with the axis
 * range printed alongside, because an auto-scaled chart that does not say what it scaled to is
 * how people end up misreading a flat learning curve as an improvement.
 *
 * <p><b>The axis label is the contract.</b> It reports what was observed, never the span used to
 * make the arithmetic work. That distinction is not pedantry: an invented span here is how a run
 * that never left depth 1 was reported as having reached depth 2.
 */
public class Graph {

	private Graph() {}

	/** Block-drawing ramp, darkest to lightest. */
	private static final String[] RAMP = { " ", "\u2591", "\u2592", "\u2593", "\u2588" };

	/**
	 * Renders a series as a single row of bars.
	 *
	 * <p><b>A flat series is labelled flat, and never given an invented range.</b> This used to widen
	 * the scale by 1 whenever min equalled max, purely so the division below was defined - and then
	 * printed that widened range as if it had been observed. So a run whose best depth stayed at 1.0
	 * in every generation printed {@code depth 1.0..2.0}, which reads as "reached 2" and is exactly
	 * how a depth-2 episode was reported here that never happened. A scale invented to make arithmetic
	 * work must never reach the label, because the label is the part a reader trusts.
	 *
	 * @param values the series, oldest first
	 * @param width  how many columns to use; values are bucketed to fit
	 * @return a row plus the min and max it was scaled against
	 */
	public static Row bar( float[] values, int width ) {
		Row row = new Row();
		if (values.length == 0 || width <= 0) return row;

		int n = Math.min( values.length, width );
		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;
		for (float v : values){
			if (v < min) min = v;
			if (v > max) max = v;
		}

		//true observed bounds, whatever is used for rendering. The order matters and is the whole fix:
		//compute flat from the data, record the data, and never let a span used for rendering reach
		//the reported bounds. The original widened first and recorded afterwards, which is how
		//1.0..2.0 came out of a series that was 1.0 throughout.
		row.min = min;
		row.max = max;
		row.flat = (max - min) < 1e-6f;
		row.text = new StringBuilder( width ).toString();

		StringBuilder sb = new StringBuilder( width );
		for (int i = 0; i < width; i++){
			//bucket the series into width columns, averaging within a bucket
			int from = (int) ((long) i * values.length / width );
			int to = (int) ((long) (i + 1) * values.length / width );
			if (to <= from) to = from + 1;
			if (to > values.length) to = values.length;

			float sum = 0;
			int count = 0;
			for (int j = from; j < to; j++){ sum += values[ j ]; count++; }
			float avg = count > 0 ? sum / count : min;

			sb.append( RAMP[ step( row, avg, min, max ) ] );
		}

		row.text = sb.toString();
		return row;
	}

	/**
	 * Which rung of the ramp one value sits on.
	 *
	 * <p>A flat series renders mid-ramp rather than blank. With a zero span the old arithmetic put
	 * every bar on the darkest rung, which is the same glyph a row with no data draws - so "nothing
	 * happened" and "this never moved" looked alike. They are different facts and the reader is here
	 * to tell them apart.
	 */
	private static int step( Row row, float value, float min, float max ){
		if (row.flat) return RAMP.length / 2;

		float t = (value - min) / (max - min);
		return Math.min( RAMP.length - 1, Math.max( 0, Math.round( t * (RAMP.length - 1) ) ));
	}

	/**
	 * Renders a series as a single line, sampling to fit.
	 *
	 * Used for score-over-turns, where the shape of the line matters more than exact magnitudes.
	 */
	public static Row spark( float[] values, int width ) {
		Row row = new Row();
		if (values.length == 0 || width <= 0) return row;

		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;
		for (float v : values){
			if (v < min) min = v;
			if (v > max) max = v;
		}

		//as in bar(): observed bounds only, and no invented span. See bar() for why.
		row.min = min;
		row.max = max;
		row.flat = (max - min) < 1e-6f;
		StringBuilder sb = new StringBuilder( width );
		for (int i = 0; i < width; i++){
			int index = (int) ((long) i * (values.length - 1) / Math.max( 1, width - 1 ));
			sb.append( RAMP[ step( row, values[ index ], min, max ) ] );
		}

		row.text = sb.toString();
		return row;
	}

	public static class Row {
		public String text = "";
		public float min;
		public float max;

		/** True when every value was the same, so {@link #min} and {@link #max} are the real bounds. */
		public boolean flat;

		/**
		 * Axis label, ie. the range this row was scaled against.
		 *
		 * <p>A flat row prints one number and the word {@code flat}. Printing {@code min..max} for it
		 * would be correct, but {@code 1.0} alone reads as a truncation of a longer range, so the
		 * explicit word is what stops the next reader - or the next agent - reading a ceiling into it.
		 */
		public String axis(){
			if (flat) return String.format( Locale.ROOT, "%.1f (flat)", min );
			return String.format( Locale.ROOT, "%.1f..%.1f", min, max );
		}
	}
}