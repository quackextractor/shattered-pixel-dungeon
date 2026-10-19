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

public class MetricsHistory {

	private static final String[] HEADER = {
			"generation", "episodes", "activeSeeds", "sampledSteps", "droppedSteps",
			"meanScore", "bestScore", "worstScore", "meanDepth", "bestDepth", "meanTurns",
			"policyLoss", "valueLoss", "entropy", "clipFraction", "klDivergence",
			"advantageMean", "advantageStd", "shapingScale",
			"meanObjectiveScore", "depth2PlusEpisodes",
			"wallSeconds", "ppoSeconds", "barrierSeconds", "turnsPerSecond",
			"workerCores", "trainerCores"
	};

	private static final String[] REASON_COLUMNS = reasonColumns();

	private static String[] reasonColumns(){
		RewardModel.TerminateReason[] reasons = RewardModel.TerminateReason.values();
		String[] names = new String[ reasons.length ];
		for (int i = 0; i < reasons.length; i++) names[ i ] = "end" + reasons[ i ].name();
		return names;
	}

	private final List< float[] > series = new ArrayList<>();

	private final File csv;
	private Writer out;

	private boolean headerWritten;

	public MetricsHistory( File csv ){
		this.csv = csv;
	}

	static int columns(){
		return HEADER.length + REASON_COLUMNS.length;
	}

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

	public void record( GenerationReport.Snapshot s ){
		if (s == null) return;

		series.add( new float[]{
				s.generation, s.episodes, s.activeSeeds, s.sampledSteps, s.droppedSteps,
				(float) s.meanScore, (float) s.bestScore, (float) s.worstScore,
				(float) s.meanDepth, s.bestDepth, (float) s.meanTurns,
				s.policyLoss, s.valueLoss, s.entropy, s.clipFraction, s.klDivergence,
				(float) s.advantageMean, (float) s.advantageStd, s.shaping,
				(float) s.meanObjectiveScore, s.depth2PlusEpisodes,
				(float) s.wallSeconds, (float) s.ppoSeconds, (float) s.barrierSeconds,
				(float) s.turnsPerSecond, (float) s.workerCores, (float) s.trainerCores
		} );

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
					.append( ',' ).append( fmt( s.shaping ) )
					.append( ',' ).append( fmt( s.meanObjectiveScore ) )
					.append( ',' ).append( s.depth2PlusEpisodes )
					.append( ',' ).append( fmt( s.wallSeconds ) ).append( ',' ).append( fmt( s.ppoSeconds ) )
					.append( ',' ).append( fmt( s.barrierSeconds ) )
					.append( ',' ).append( fmt( s.turnsPerSecond ) )
					.append( ',' ).append( fmt( s.workerCores ) ).append( ',' ).append( fmt( s.trainerCores ) );

			RewardModel.TerminateReason[] reasons = RewardModel.TerminateReason.values();
			int usable = Math.min( reasons.length, s.endReasons.length );
			for (int i = 0; i < reasons.length; i++){
				row.append( ',' ).append( i < usable ? s.endReasons[ i ] : 0 );
			}

			out.write( row.append( '\n' ).toString() );

			out.flush();
		} catch (IOException e){
			System.err.println( "[WARN] metrics history stopped after generation "
					+ s.generation + ": " + e.getMessage() );
			close();
		}
	}

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

		}
		out = null;
	}

	public int generations(){
		return series.size();
	}

	public Snapshot latest(){
		if (series.isEmpty()) return null;

		float[] row = series.get( series.size() - 1 );
		return new Snapshot( row[ COL_MEAN_SCORE ], row[ COL_BEST_DEPTH ] );
	}

	private static final int COL_MEAN_SCORE = 5;
	private static final int COL_BEST_DEPTH = 9;
	private static final int COL_OBJECTIVE = 19;
	private static final int COL_DEPTH2PLUS = 20;
	private static final int COL_EPISODES = 1;

	public static class Snapshot {
		public final double meanScore;
		public final int bestDepth;

		Snapshot( double meanScore, double bestDepth ){
			this.meanScore = meanScore;
			this.bestDepth = (int) bestDepth;
		}
	}

	public float[] values( int column ){
		float[] result = new float[ series.size() ];
		if (column < 0 || column >= HEADER.length) return result;
		for (int i = 0; i < series.size(); i++) result[ i ] = series.get( i )[ column ];
		return result;
	}

	public void printTrend(){
		if (series.isEmpty()) return;

		int width = Math.min( 72, Math.max( 16, series.size() ) );
		System.out.println();
		System.out.println( Ansi.wrap( "trend", Ansi.DIM ) + "   " + series.size()
				+ " generations, " + ( csv == null ? "no CSV" : Trainer.describe( csv ) ) );

		line( "score ", 5, width );
		line( "depth ", 8, width );
		line( "turns ", 10, width );
		line( "polcl ", 11, width );
		line( "valcl ", 12, width );

		printWindowSummary();

		printEndReasonTrend();

		if (series.size() >= 8){
			Graph.Row smoothed = Graph.bar( movingAverage( values( 5 ), 7 ), width );
			System.out.println( "  score7 " + Ansi.wrap( "[7-gen mean] ", Ansi.DIM )
					+ smoothed.text + "  " + Ansi.wrap( smoothed.axis(), Ansi.DIM ) );
		}
	}


	private void printWindowSummary(){
		int n = series.size();
		if (n < 4) return;

		int split = n / 2;
		double[] firstScore = window( COL_MEAN_SCORE, 0, split );
		double[] secondScore = window( COL_MEAN_SCORE, split, n );
		double[] firstDepth = window( COL_DEPTH2PLUS, 0, split );
		double[] secondDepth = window( COL_DEPTH2PLUS, split, n );
		double[] firstEpisodes = window( COL_EPISODES, 0, split );
		double[] secondEpisodes = window( COL_EPISODES, split, n );

		System.out.println( "  windows " + Ansi.wrap( "first " + split + " generations against the last "
				+ (n - split), Ansi.DIM ) );

		printWindow( "score    ", firstScore, secondScore );
		printWindow( "objective", window( COL_OBJECTIVE, 0, split ), window( COL_OBJECTIVE, split, n ) );

		System.out.println( "  depth>=2 " + String.format( "%.1f%% -> %.1f%%",
				100 * WindowStatistics.rate( firstDepth, firstEpisodes ),
				100 * WindowStatistics.rate( secondDepth, secondEpisodes ) )
				+ Ansi.wrap( "  (share of episodes reaching floor 2 or deeper)", Ansi.DIM ) );
	}


	private void printWindow( String label, double[] first, double[] second ){
		double delta = WindowStatistics.mean( second ) - WindowStatistics.mean( first );
		double error = WindowStatistics.differenceError( first, second );

		System.out.println( "  " + label + "   " + Ansi.signed( WindowStatistics.mean( first ) )
				+ " sd=" + String.format( "%.1f", WindowStatistics.sampleStdDev( first ) )
				+ " sem=" + String.format( "%.1f", WindowStatistics.standardError( first ) )
				+ "   ->   " + Ansi.signed( WindowStatistics.mean( second ) )
				+ " sd=" + String.format( "%.1f", WindowStatistics.sampleStdDev( second ) )
				+ " sem=" + String.format( "%.1f", WindowStatistics.standardError( second ) )
				+ "   delta=" + Ansi.signed( delta ) + " +/- " + String.format( "%.1f", error ) );
	}


	private double[] window( int column, int from, int to ){
		double[] out = new double[ Math.max( 0, to - from ) ];
		for (int i = from; i < to && i < series.size(); i++){
			out[ i - from ] = series.get( i )[ column ];
		}
		return out;
	}

	private void printEndReasonTrend(){
		if (series.size() < 4) return;

		int n = series.size();
		int third = Math.max( 1, n / 3 );

		RewardModel.TerminateReason[] reasons = RewardModel.TerminateReason.values();
		int[] early = new int[ reasons.length ];
		int[] late = new int[ reasons.length ];
		int earlyTotal = 0;
		int lateTotal = 0;

		for (int i = 0; i < n; i++){
			int[] counts = endReasons.get( i );
			for (int r = 0; r < reasons.length && r < counts.length; r++ ){
				if (i < third){
					early[ r ] += counts[ r ];
					earlyTotal += counts[ r ];
				}
				if (i >= n - third){
					late[ r ] += counts[ r ];
					lateTotal += counts[ r ];
				}
			}
		}

		StringBuilder sb = new StringBuilder();
		for (int r = 0; r < reasons.length; r++ ){
			double f = earlyTotal <= 0 ? 0 : early[ r ] * 100.0 / earlyTotal;
			double l = lateTotal <= 0 ? 0 : late[ r ] * 100.0 / lateTotal;
			if (f < 0.5 && l < 0.5 ) continue;

			if (sb.length() > 0 ) sb.append( "  " );
			sb.append( reasons[ r ].name().toLowerCase( java.util.Locale.ROOT ) )
					.append( " " ).append( String.format( java.util.Locale.ROOT, "%.0f%%->%.0f%%", f, l ) );
		}

		System.out.println( "  ended   " + (sb.length() == 0 ? "no reason recorded" : sb.toString())
				+ Ansi.wrap( "  (first third -> last third)", Ansi.DIM ) );
	}

	private final List< int[] > endReasons = new ArrayList<>();

	private void line( String label, int column, int width ){
		Graph.Row row = Graph.bar( values( column ), width );
		System.out.println( "  " + label + "    " + row.text + "  "
				+ Ansi.wrap( row.axis(), Ansi.DIM ) );
	}

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
