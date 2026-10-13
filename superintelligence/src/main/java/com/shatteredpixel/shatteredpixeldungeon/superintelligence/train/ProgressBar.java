package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import java.util.Locale;

/**
 * The one line a run rewrites in place while it is going.
 *
 * <p>A hundred-and-fifty generation run is over an hour, and the alternative to a bar is a terminal that
 * looks broken for most of it. So: a fixed-width bar, the generation count, elapsed, an estimate built
 * from the generations that have actually finished, and the last two numbers anybody watching a run cares
 * about.
 *
 * <p>Every number here is measured. The bar's fraction is generations completed over generations asked
 * for, the estimate is elapsed over that same fraction rather than a guess about episode length, and it
 * is blank until there is a generation to divide by - a bar that reports an estimate before it has
 * observed anything is a bar that lies in the one place a waiting reader is watching.
 *
 * <p>Pure string in, string out, and no printing: {@code graphcheck} asserts the shape of what this
 * renders, which is only possible if it can be asked for a bar without a run.
 */
public final class ProgressBar {

	/** Filled and empty cells. Fixed width, so the line does not jitter as the fraction changes. */
	private static final int WIDTH = 24;

	private ProgressBar() {}

	/**
	 * One line, without a newline, ending in a carriage return so the next one overwrites it.
	 *
	 * @param done     generations finished
	 * @param total    generations asked for
	 * @param startedMs {@link System#currentTimeMillis()} when the run began
	 * @param meanScore the last generation's mean score, or a sentinel meaning "none yet"
	 * @param bestDepth the deepest floor seen so far
	 */
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

	/** The estimate, or a dash when nothing has finished yet to divide by. */
	public static String estimate( int done, int total, long startedMs ){
		if (done <= 0 || total <= 0 || done >= total) return "-";

		long elapsed = System.currentTimeMillis() - startedMs;
		long remaining = (long) (elapsed / (double) done * (total - done) );
		return duration( remaining );
	}

	/** Wall time since the run began, always positive even if the clock was set backwards. */
	public static String elapsed( long startedMs ){
		return duration( Math.max( 0, System.currentTimeMillis() - startedMs ) );
	}

	/** {@code m:ss} under an hour and {@code h:mm:ss} over it, which is how long a run actually is. */
	private static String duration( long ms ){
		long seconds = ms / 1000;
		long hours = seconds / 3600;
		long minutes = (seconds % 3600) / 60;
		long rest = seconds % 60;

		if (hours > 0) return String.format( Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest );
		return String.format( Locale.ROOT, "%d:%02d", minutes, rest );
	}

	/** The score as a plain number, or a dash for the sentinel that means no generation has reported. */
	private static String score( double meanScore ){
		if (Double.isNaN( meanScore )) return "-";
		return String.format( Locale.ROOT, "%,.0f", meanScore );
	}
}