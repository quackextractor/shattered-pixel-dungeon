package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import java.util.Locale;

public final class ProgressBar {

	private static final int WIDTH = 24;

	private boolean drawn;

	void draw( int done, int total, long startedAt, MetricsHistory metrics ){
		double meanScore = Double.NaN;
		int bestDepth = 0;
		MetricsHistory.Snapshot last = metrics == null ? null : metrics.latest();
		if (last != null){
			meanScore = last.meanScore;
			bestDepth = last.bestDepth;
		}
		System.out.print( render( done, total, startedAt, meanScore, bestDepth ) );
		drawn = true;
	}

	void clear(){
		if (!drawn) return;
		System.out.println();
		drawn = false;
	}

	public static String render( int done, int total, long startedMs, double meanScore, int bestDepth ){
		int safeTotal = Math.max( 1, total );
		int fraction = (int) Math.round( (double) Math.min( done, safeTotal ) / safeTotal * WIDTH );

		StringBuilder bar = new StringBuilder( "[");
		for (int i = 0; i < WIDTH; i++) bar.append( i < fraction ? '#' : '-' );
		bar.append( ']' );

		int percent = (int) Math.round( 100.0 * Math.min( done, safeTotal ) / safeTotal );

		return String.format( Locale.ROOT, "\r%s %3d%%  gen %d/%d  %s  eta %s  score %s  bestDepth %d",
				bar, percent, done, total, elapsed( startedMs ), estimate( done, total, startedMs ),
				score( meanScore ), bestDepth );
	}

	public static String estimate( int done, int total, long startedMs ){
		if (done <= 0 || total <= 0 || done >= total) return "-";

		long elapsed = System.currentTimeMillis() - startedMs;
		long remaining = (long) (elapsed / (double) done * (total - done) );
		return duration( remaining );
	}

	public static String elapsed( long startedMs ){
		return duration( Math.max( 0, System.currentTimeMillis() - startedMs ) );
	}

	private static String duration( long ms ){
		long seconds = ms / 1000;
		long hours = seconds / 3600;
		long minutes = (seconds % 3600) / 60;
		long rest = seconds % 60;

		if (hours > 0) return String.format( Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest );
		return String.format( Locale.ROOT, "%d:%02d", minutes, rest );
	}

	private static String score( double meanScore ){
		if (Double.isNaN( meanScore )) return "-";
		return String.format( Locale.ROOT, "%,.0f", meanScore );
	}
}