package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;


public final class GenerationReport {

	public static class Snapshot {
		public int generation;
	public int episodes;
	public int activeSeeds;
	public int totalSeeds;
	public float shaping;


	public int[] endReasons = new int[RewardModel.TerminateReason.values().length];


		public int sampledSteps;


		public int droppedSteps;


		public long sampledBytes;
		public long gridBytes;


		public double advantageMean;
		public double advantageStd;

		public double meanScore;
		public double bestScore;
		public double worstScore;
		public double meanDepth;
		public int bestDepth;
		public double meanTurns;
		public long totalTurns;

		public float policyLoss;
		public float valueLoss;
		public float entropy;
		public float clipFraction;
		public float klDivergence;

		public double wallSeconds;
		public double ppoSeconds;
		public double barrierSeconds;
		public double turnsPerSecond;
		public double episodesPerSecond;


		public double systemLoad;
		public int logicalProcessors;
		public double workerCores;
		public double trainerCores;
		public int poolSize;
		public int episodesPerWorker;
	}

	private GenerationReport() {}

	public static void print( Snapshot s ){
		System.out.println();
		System.out.println( Ansi.wrap( "generation " + s.generation, Ansi.BOLD + Ansi.CYAN )
				+ "  episodes=" + s.episodes
				+ "  seeds=" + s.activeSeeds + "/" + s.totalSeeds
				+ "  shaping=" + String.format( "%.2f", s.shaping ) );
		System.out.println( "  score   mean=" + Ansi.signed( s.meanScore )
				+ "  best=" + Ansi.signed( s.bestScore )
				+ "  worst=" + Ansi.signed( s.worstScore ) );
		System.out.println( "  depth   mean=" + String.format( "%.1f", s.meanDepth )
				+ "  best=" + s.bestDepth );
		System.out.println( "  turns   mean=" + String.format( "%.0f", s.meanTurns )
				+ "  total=" + String.format( "%,d", s.totalTurns ) );
		System.out.println( "  ended   " + endReasons( s ) );
		System.out.println( "  ppo     policy=" + String.format( "%.4f", s.policyLoss )
				+ "  value=" + String.format( "%.4f", s.valueLoss )
				+ "  entropy=" + String.format( "%.3f", s.entropy )
				+ "  clip=" + String.format( "%.2f", s.clipFraction )
				+ "  kl=" + String.format( "%.4f", s.klDivergence ) );

		System.out.println();
		System.out.println( "  speed   " + String.format( "%,.0f turns/s", s.turnsPerSecond )
				+ "  " + String.format( "%,.1f episodes/s", s.episodesPerSecond )
				+ "  " + String.format( "%.2fs wall", s.wallSeconds )
				+ ( s.ppoSeconds > 0 ? "  (ppo update " + String.format( "%.2fs", s.ppoSeconds ) + ")" : "" )
				+ "  " + String.format( "barrier %.0f%%", pct( s.barrierSeconds, s.wallSeconds ) ) );
		System.out.println( "  data    " + String.format( "%,d sampled steps", s.sampledSteps )
				+ " of " + String.format( "%,d collected", s.totalTurns )
				+ ( s.droppedSteps > 0
						? "  [WARN] " + String.format( "%,d", s.droppedSteps ) + " dropped at the cap"
						: "" )
				+ "  advantage mean=" + String.format( "%+.3f", s.advantageMean )
				+ " sd=" + String.format( "%.3f", s.advantageStd ) );
		System.out.println( "  buffer  " + mb( s.sampledBytes ) + " resident, "
				+ String.format( "%.1f%%", pct( s.gridBytes, s.sampledBytes ) )
				+ " of it the packed grid" );
		System.out.println( "  usage   " + ( s.systemLoad >= 0
						? String.format( "%.0f%% of machine (%.1f of %d logical cores)",
								s.systemLoad * 100, cores( s ), s.logicalProcessors )
						: String.format( "%.1f cores busy (worker-reported)", s.workerCores ) )
				+ "  pool " + s.poolSize + " x " + s.episodesPerWorker + " episodes"
				+ String.format( "  workers %.1f cores, trainer %.2f", s.workerCores, s.trainerCores ) );
	}


	private static String endReasons( Snapshot s ){
		RewardModel.TerminateReason[] reasons = RewardModel.TerminateReason.values();
		Integer[] order = new Integer[ reasons.length ];
		for (int i = 0; i < order.length; i++) order[ i ] = i;

		final int[] counts = s.endReasons;
		java.util.Arrays.sort( order, ( a, b ) -> counts[ b ] - counts[ a ] );

		StringBuilder sb = new StringBuilder();
		for (int i : order){
			if (s.endReasons[ i ] == 0 ) continue;
			if (sb.length() > 0 ) sb.append( "  " );
			sb.append( reasons[ i ].name().toLowerCase( java.util.Locale.ROOT ) );
			sb.append( " " ).append( s.endReasons[ i ] );
			sb.append( String.format( " (%.0f%%)", pct( s.endReasons[ i ], s.episodes ) ) );
		}
		if (sb.length() == 0 ) return "none recorded";
		return sb.toString();
	}


	private static double cores( Snapshot s ){
		return s.systemLoad * s.logicalProcessors;
	}

	private static String mb( long bytes ){
		return String.format( "%.1f MB", bytes / (1024.0 * 1024.0) );
	}

	private static double pct( double used, double total ){
		return total <= 0 ? 0 : used / total * 100;
	}
}