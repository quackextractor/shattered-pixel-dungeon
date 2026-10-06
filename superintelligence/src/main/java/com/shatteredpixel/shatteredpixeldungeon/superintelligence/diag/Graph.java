package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

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
 */
public class Graph {

	private Graph() {}

	/** Block-drawing ramp, darkest to lightest. */
	private static final String[] RAMP = { " ", "\u2591", "\u2592", "\u2593", "\u2588" };

	/**
	 * Renders a series as a single row of bars.
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
		if (max - min < 1e-6f) max = min + 1f;

		row.min = min;
		row.max = max;
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

			float t = (avg - min) / (max - min);
			int step = Math.min( RAMP.length - 1, Math.max( 0, Math.round( t * (RAMP.length - 1) ) ));
			sb.append( RAMP[ step ] );
		}

		row.text = sb.toString();
		return row;
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
		if (max - min < 1e-6f) max = min + 1f;

		row.min = min;
		row.max = max;

		StringBuilder sb = new StringBuilder( width );
		for (int i = 0; i < width; i++){
			int index = (int) ((long) i * (values.length - 1) / Math.max( 1, width - 1 ));
			float t = (values[ index ] - min) / (max - min);
			int step = Math.min( RAMP.length - 1, Math.max( 0, Math.round( t * (RAMP.length - 1) ) ));
			sb.append( RAMP[ step ] );
		}

		row.text = sb.toString();
		return row;
	}

	public static class Row {
		public String text = "";
		public float min;
		public float max;

		/** Axis label, ie. the range this row was scaled against. */
		public String axis(){
			return String.format( "%.1f..%.1f", min, max );
		}
	}
}