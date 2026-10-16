package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import java.util.Locale;

public class Graph {

	private Graph() {}

	private static final String[] RAMP = { " ", "\u2591", "\u2592", "\u2593", "\u2588" };

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

		row.min = min;
		row.max = max;
		row.flat = (max - min) < 1e-6f;
		row.text = new StringBuilder( width ).toString();

		StringBuilder sb = new StringBuilder( width );
		for (int i = 0; i < width; i++){

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

	private static int step( Row row, float value, float min, float max ){
		if (row.flat) return RAMP.length / 2;

		float t = (value - min) / (max - min);
		return Math.min( RAMP.length - 1, Math.max( 0, Math.round( t * (RAMP.length - 1) ) ));
	}

	public static Row spark( float[] values, int width ) {
		Row row = new Row();
		if (values.length == 0 || width <= 0) return row;

		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;
		for (float v : values){
			if (v < min) min = v;
			if (v > max) max = v;
		}

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

		public boolean flat;

		public String axis(){
			if (flat) return String.format( Locale.ROOT, "%.1f (flat)", min );
			return String.format( Locale.ROOT, "%.1f..%.1f", min, max );
		}
	}
}