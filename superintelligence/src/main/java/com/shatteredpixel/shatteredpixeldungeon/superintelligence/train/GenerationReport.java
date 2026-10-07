package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;

/**
 * Renders one generation's console block.
 *
 * A {@link Snapshot} of plain numbers rather than a reference to the trainer, so the formatting can be
 * changed or tested without a live pool of workers. {@link Trainer} fills one snapshot per generation
 * from whatever it measured.
 *
 * Every figure here is something a decision could rest on. If it is not printed it cannot be used to
 * decide anything, which is why the sampled-step count is a field rather than something derived on
 * demand: the whole sampled-transition design rests on that number being where the arithmetic says it
 * should be.
 */
public final class GenerationReport {

	public static class Snapshot {
		public int generation;
	public int episodes;
	public int activeSeeds;
	public int totalSeeds;
	public float shaping;

	/**
	 * Episodes per termination reason, keyed by {@code RewardModel.TerminateReason} ordinal.
	 *
	 * <p><b>The single most useful number in this report, and it took a 20-generation run to discover
	 * it was missing.</b> The run converged {@code meanScore} on exactly -5.0 with turns collapsing
	 * 62 → 8, and -5.0 is {@code STALLED}'s terminal reward. That diagnosis was reached by arithmetic
	 * on the score rather than by reading the reason, because the reason was never reported. A
	 * converged mean score is the visible symptom of an agent that has found a cheap way to end an
	 * episode; which way is the question, and it was one subtraction too indirect.
	 *
	 * <p>Indexed by ordinal rather than a map so the report and the CSV can both iterate it in a fixed
	 * order. Length is {@code TerminateReason.values().length}; a count outside that range would be an
	 * array bounds error, so the length is asserted where the array is built rather than trusted.
	 */
	public int[] endReasons = new int[RewardModel.TerminateReason.values().length];

		/** Transitions retained for the update across the whole generation. */
		public int sampledSteps;

		/** Transitions the memory cap discarded. Non-zero means the cap, not the rate, is binding. */
		public int droppedSteps;

		/**
		 * Bytes the sampled transitions occupy in the trainer, and how much of that is the packed grid.
		 *
		 * The grid share is the number the deferred bit-packing would attack, so it is measured rather
		 * than quoted. Resident and wire are the same size here and deliberately not reported twice:
		 * the frame is fp32 for everything except the grid, which is already one byte per cell in
		 * memory too.
		 */
		public long sampledBytes;
		public long gridBytes;

		/**
		 * Mean and standard deviation of the raw advantages as they arrived.
		 *
		 * Printed before normalisation, and that is the point: a batch whose advantages are all
		 * identical has nothing to learn a direction from, and after normalisation it would look like a
		 * healthy mean of zero and standard deviation of one. These are the numbers that catch it.
		 */
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

		/** System-wide CPU load in 0..1, or -1 when the JVM will not report it. */
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

	/**
	 * Renders the end-reason breakdown, most frequent first.
	 *
	 * <p>Sorted by count so the dominant reason is first, and zero counts omitted so a run whose
	 * episodes mostly end one way reads as one line rather than seven. Every count is shown as a
	 * fraction of the generation's episodes, because the absolute count moves with worker count and the
	 * fraction is what says whether the policy has changed.
	 */
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

	/** Cores the machine is using, derived from the system load where the JVM reports one. */
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