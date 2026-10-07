package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Graph;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates one row per generation, so a run's history outlives the process that produced it.
 *
 * <p>Until this existed, {@link Trainer} kept {@code lastEpisodes} - one generation - and discarded
 * it. A run printed a block per generation into console scrollback and threw it away, which meant
 * "is generation 200 better than generation 100" had to be believed rather than checked. That matters
 * more than usual here, because {@code clip=} used to report a constant (see {@link
 * com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO}) and the losses are the only
 * remaining evidence the update is doing anything.
 *
 * <p><b>CSV and terminal both, because they answer different questions.</b> CSV is how two runs are
 * compared - it survives, it can be diffed, and a plotting tool can read it. The graph is how one run
 * reads at a glance in a terminal, and {@link Graph} already existed unused for it. Writing only one
 * would have left a real use unserved: the CSV cannot be read without leaving the terminal, and the
 * graph cannot be compared across runs.
 *
 * <p>Appended rather than rewritten so a crashed or resumed run keeps what it reached. The header is
 * written once, when the file is new or empty; {@link #HEADER} and {@link #columns()} are the single
 * source of truth for the shape, so the row cannot drift from the header.
 */
public class MetricsHistory {

	/**
	 * Column names, in the order {@link #row} emits them.
	 *
	 * <p>Additive only. Reordering or renaming a column silently breaks every CSV a previous run
	 * wrote, and there is no version column to catch it.
	 */
	private static final String[] HEADER = {
			"generation", "episodes", "activeSeeds", "sampledSteps", "droppedSteps",
			"meanScore", "bestScore", "worstScore", "meanDepth", "bestDepth", "meanTurns",
			"policyLoss", "valueLoss", "entropy", "clipFraction", "klDivergence",
			"advantageMean", "advantageStd",
			"wallSeconds", "ppoSeconds", "barrierSeconds", "turnsPerSecond",
			"workerCores", "trainerCores"
	};

	/**
	 * End-reason columns, appended after {@link #HEADER}.
	 *
	 * <p>Named columns rather than one packed column, so a spreadsheet can chart "what fraction of
	 * episodes died" without a formula. The set is derived from the enum so a new reason cannot be
	 * added without its column appearing.
	 */
	private static final String[] REASON_COLUMNS = reasonColumns();

	private static String[] reasonColumns(){
		RewardModel.TerminateReason[] reasons = RewardModel.TerminateReason.values();
		String[] names = new String[ reasons.length ];
		for (int i = 0; i < reasons.length; i++) names[ i ] = "end" + reasons[ i ].name();
		return names;
	}

	/** The rows of the current run, oldest first, for the end-of-run graph. */
	private final List< float[] > series = new ArrayList<>();

	private final File csv;
	private Writer out;

	/** True once a header has been written, so an appended file does not get a second one. */
	private boolean headerWritten;

	public MetricsHistory( File csv ){
		this.csv = csv;
	}

	static int columns(){
		return HEADER.length + REASON_COLUMNS.length;
	}

	/**
	 * Opens the file, creating parent directories.
	 *
	 * <p>A failure here is reported and swallowed rather than thrown: losing the metrics must not
	 * abort a run that is otherwise learning. The alternative - refuse to train unless a CSV can be
	 * written - would mean a full disk stops the only thing that matters.
	 */
	public void open(){
		if (csv == null) return;

		try {
			File parent = csv.getParentFile();
			if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()){
				throw new IOException( "cannot create " + parent );
			}

			headerWritten = csv.isFile() && csv.length() > 0;
			out = new BufferedWriter( new java.io.OutputStreamWriter(
					Files.newOutputStream( csv.toPath(), StandardOpenOption.CREATE,
							StandardOpenOption.WRITE, StandardOpenOption.APPEND ),
					StandardCharsets.UTF_8 ) );

			if (!headerWritten){
				StringBuilder header = new StringBuilder( String.join( ",", HEADER ) );
				for (String name : REASON_COLUMNS) header.append( ',' ).append( name );
				out.write( header.append( '\n' ).toString() );
				headerWritten = true;
			}
		} catch (IOException e){
			System.err.println( "[WARN] metrics history disabled, cannot write " + csv
					+ ": " + e.getMessage() );
			close();
		}
	}

	/**
	 * Records one generation, appending a CSV row and keeping it for the graph.
	 *
	 * <p>Called after {@link GenerationReport#print} so the console still leads and the file catches
	 * up. A row is kept even if the write fails, so the end-of-run graph is still complete.
	 */
	public void record( GenerationReport.Snapshot s ){
		if (s == null) return;

		series.add( new float[]{
				s.generation, s.episodes, s.activeSeeds, s.sampledSteps, s.droppedSteps,
				(float) s.meanScore, (float) s.bestScore, (float) s.worstScore,
				(float) s.meanDepth, s.bestDepth, (float) s.meanTurns,
				s.policyLoss, s.valueLoss, s.entropy, s.clipFraction, s.klDivergence,
				(float) s.advantageMean, (float) s.advantageStd,
				(float) s.wallSeconds, (float) s.ppoSeconds, (float) s.barrierSeconds,
				(float) s.turnsPerSecond, (float) s.workerCores, (float) s.trainerCores
		} );

		//copied rather than retained: the snapshot is rebuilt every generation, so holding the
		//reference would show the last generation's counts for every row of the trend
		endReasons.add( s.endReasons.clone() );

		write( s );
	}

	private void write( GenerationReport.Snapshot s ){
		if (out == null) return;

		try {
			StringBuilder row = new StringBuilder();
			row.append( s.generation ).append( ',' ).append( s.episodes ).append( ',' ).append( s.activeSeeds )
					.append( ',' ).append( s.sampledSteps ).append( ',' ).append( s.droppedSteps )
					.append( ',' ).append( fmt( s.meanScore ) ).append( ',' ).append( fmt( s.bestScore ) )
					.append( ',' ).append( fmt( s.worstScore ) )
					.append( ',' ).append( fmt( s.meanDepth ) ).append( ',' ).append( s.bestDepth )
					.append( ',' ).append( fmt( s.meanTurns ) )
					.append( ',' ).append( fmt( s.policyLoss ) ).append( ',' ).append( fmt( s.valueLoss ) )
					.append( ',' ).append( fmt( s.entropy ) ).append( ',' ).append( fmt( s.clipFraction ) )
					.append( ',' ).append( fmt( s.klDivergence ) )
					.append( ',' ).append( fmt( s.advantageMean ) ).append( ',' ).append( fmt( s.advantageStd ) )
					.append( ',' ).append( fmt( s.wallSeconds ) ).append( ',' ).append( fmt( s.ppoSeconds ) )
					.append( ',' ).append( fmt( s.barrierSeconds ) )
					.append( ',' ).append( fmt( s.turnsPerSecond ) )
					.append( ',' ).append( fmt( s.workerCores ) ).append( ',' ).append( fmt( s.trainerCores ) );

			//end reasons last, matching REASON_COLUMNS. The count is clamped to the reason's own range
			//rather than the generation's, so a mismatched array is visible as a wrong number here
			//instead of an index error somewhere less obvious.
			RewardModel.TerminateReason[] reasons = RewardModel.TerminateReason.values();
			int usable = Math.min( reasons.length, s.endReasons.length );
			for (int i = 0; i < reasons.length; i++){
				row.append( ',' ).append( i < usable ? s.endReasons[ i ] : 0 );
			}

			out.write( row.append( '\n' ).toString() );
			//flushed per generation rather than per run: the run this exists for is long, and a buffer
			//lost to a crash is exactly the history that was wanted
			out.flush();
		} catch (IOException e){
			System.err.println( "[WARN] metrics history stopped after generation "
					+ s.generation + ": " + e.getMessage() );
			close();
		}
	}

	/**
	 * A double with {@code Locale.ROOT}, because a comma decimal separator would shift every
	 * column after it.
	 */
	private static String fmt( double v ){
		return String.format( java.util.Locale.ROOT, "%.6f", v );
	}

	private static String fmt( float v ){
		return String.format( java.util.Locale.ROOT, "%.6f", (double) v );
	}

	public void close(){
		if (out == null) return;
		try {
			out.close();
		} catch (IOException e){
			//nothing useful to do; the rows are already flushed
		}
		out = null;
	}

	public int generations(){
		return series.size();
	}

	/** One series as a float array, for a caller that wants to plot or compare it. */
	public float[] values( int column ){
		float[] result = new float[ series.size() ];
		if (column < 0 || column >= HEADER.length) return result;
		for (int i = 0; i < series.size(); i++) result[ i ] = series.get( i )[ column ];
		return result;
	}

	/**
	 * Renders the end-of-run trend.
	 *
	 * <p>Deliberately only the four series that answer "is it learning", each with its axis range
	 * printed: score and depth are the goal, and the two losses say whether the optimiser is working
	 * at all. Everything else is in the CSV, where it can be examined properly rather than squinted at
	 * in a terminal.
	 */
	public void printTrend(){
		if (series.isEmpty()) return;

		int width = Math.min( 72, Math.max( 16, series.size() ) );
		System.out.println();
		System.out.println( Ansi.wrap( "trend", Ansi.DIM ) + "   " + series.size()
				+ " generations, " + ( csv == null ? "no CSV" : csv.getPath() ) );

		line( "score ", 5, width );
		line( "depth ", 8, width );
		line( "turns ", 10, width );
		line( "polcl ", 11, width );
		line( "valcl ", 12, width );

		//end reasons are categorical, not a series to plot, so they are summarised as a fraction
		printEndReasonTrend();

		//a moving average, because per-generation score is noisy enough that its raw shape invites
		//reading a trend into noise
		if (series.size() >= 8){
			Graph.Row smoothed = Graph.bar( movingAverage( values( 5 ), 7 ), width );
			System.out.println( "  score7 " + Ansi.wrap( "[7-gen mean] ", Ansi.DIM )
					+ smoothed.text + "  " + Ansi.wrap( smoothed.axis(), Ansi.DIM ) );
		}
	}

	/**
	 * Prints how the share of each end reason moved over the run.
	 *
	 * <p>First versus last third, rather than a per-generation dump, because the question this answers
	 * is whether the distribution is *changing* — and a converged agent produces one flat row. A
	 * training run whose episodes all end the same way looks identical in every other column.
	 */
	private void printEndReasonTrend(){
		if (series.size() < 4) return;

		int n = series.size();
		int third = Math.max( 1, n / 3 );

		int[][] first = new int[ REASON_COLUMNS.length ][ 2 ];
		int[][] last = new int[ REASON_COLUMNS.length ][ 2 ];
		RewardModel.TerminateReason[] reasons = RewardModel.TerminateReason.values();

		for (int i = 0; i < n; i++){
			int[] counts = endReasons.get( i );
			boolean early = i < third;
			boolean late = i >= n - third;
			if (!early && !late) continue;

			for (int r = 0; r < reasons.length; r++ ){
				if (r >= counts.length ) break;
				int target = early ? 0 : 1;
				first[ r ][ target ] += counts[ r ];
				last[ r ][ target ] += counts[ r ];
			}
		}

		StringBuilder sb = new StringBuilder();
		for (int r = 0; r < reasons.length; r++ ){
			double f = first[ r ][ 0 ] + first[ r ][ 1 ] <= 0
					? 0 : first[ r ][ 0 ] * 100.0 / (first[ r ][ 0 ] + first[ r ][ 1 ] );
			double l = last[ r ][ 0 ] + last[ r ][ 1 ] <= 0
					? 0 : last[ r ][ 0 ] * 100.0 / (last[ r ][ 0 ] + last[ r ][ 1 ] );
			if (f < 0.5 && l < 0.5 ) continue;

			if (sb.length() > 0 ) sb.append( "  " );
			sb.append( reasons[ r ].name().toLowerCase( java.util.Locale.ROOT ) )
					.append( " " ).append( String.format( java.util.Locale.ROOT, "%.0f%%->%.0f%%", f, l ) );
		}

		System.out.println( "  ended   " + (sb.length() == 0 ? "no reason recorded" : sb.toString())
				+ Ansi.wrap( "  (first third -> last third)", Ansi.DIM ) );
	}

	/** Per-generation end-reason counts, indexed by {@code TerminateReason} ordinal. */
	private final List< int[] > endReasons = new ArrayList<>();

	private void line( String label, int column, int width ){
		Graph.Row row = Graph.bar( values( column ), width );
		System.out.println( "  " + label + "    " + row.text + "  "
				+ Ansi.wrap( row.axis(), Ansi.DIM ) );
	}

	/** Centred moving average; the ends are averaged over what exists rather than padded. */
	private static float[] movingAverage( float[] values, int window ){
		if (window <= 1 || values.length == 0) return values;

		float[] out = new float[ values.length ];
		int half = window / 2;
		for (int i = 0; i < values.length; i++){
			int from = Math.max( 0, i - half );
			int to = Math.min( values.length, i + half + 1 );
			float sum = 0;
			for (int j = from; j < to; j++) sum += values[ j ];
			out[ i ] = sum / (to - from);
		}
		return out;
	}
}
