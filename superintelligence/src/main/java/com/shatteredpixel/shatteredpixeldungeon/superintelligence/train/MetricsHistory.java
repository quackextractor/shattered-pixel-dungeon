package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Graph;

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
		return HEADER.length;
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
				out.write( String.join( ",", HEADER ) );
				out.write( '\n' );
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

		write( s );
	}

	private void write( GenerationReport.Snapshot s ){
		if (out == null) return;

		try {
			out.write( s.generation + "," + s.episodes + "," + s.activeSeeds
					+ "," + s.sampledSteps + "," + s.droppedSteps
					+ "," + fmt( s.meanScore ) + "," + fmt( s.bestScore ) + "," + fmt( s.worstScore )
					+ "," + fmt( s.meanDepth ) + "," + s.bestDepth + "," + fmt( s.meanTurns )
					+ "," + fmt( s.policyLoss ) + "," + fmt( s.valueLoss )
					+ "," + fmt( s.entropy ) + "," + fmt( s.clipFraction ) + "," + fmt( s.klDivergence )
					+ "," + fmt( s.advantageMean ) + "," + fmt( s.advantageStd )
					+ "," + fmt( s.wallSeconds ) + "," + fmt( s.ppoSeconds ) + "," + fmt( s.barrierSeconds )
					+ "," + fmt( s.turnsPerSecond ) + "," + fmt( s.workerCores ) + "," + fmt( s.trainerCores )
					+ "\n" );
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
		line( "polcl ", 11, width );
		line( "valcl ", 12, width );

		//a moving average, because per-generation score is noisy enough that its raw shape invites
		//reading a trend into noise
		if (series.size() >= 8){
			Graph.Row smoothed = Graph.bar( movingAverage( values( 5 ), 7 ), width );
			System.out.println( "  score7 " + Ansi.wrap( "[7-gen mean] ", Ansi.DIM )
					+ smoothed.text + "  " + Ansi.wrap( smoothed.axis(), Ansi.DIM ) );
		}
	}

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
