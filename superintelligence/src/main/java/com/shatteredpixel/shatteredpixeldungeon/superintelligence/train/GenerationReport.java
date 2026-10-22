package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.StallCause;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;


public final class GenerationReport {

public static class Snapshot {
		public int generation;
	public int episodes;
	public int activeSeeds;
	public int totalSeeds;
	public float shaping;

	public double meanObjectiveScore;

		public int depth2PlusEpisodes;

	public int kills;
	public int goldGains;
	public int itemPickups;
	public int talentsSpent;
	public int itemsCrafted;

	public final double[] typeTotals = new double[RewardTerm.Type.values().length];
	public final int[] stallCauses = new int[StallCause.values().length];


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
				+ "  worst=" + Ansi.signed( s.worstScore )
				+ "  objective=" + Ansi.signed( s.meanObjectiveScore ) );
		System.out.println( "  depth   mean=" + String.format( "%.1f", s.meanDepth )
				+ "  best=" + s.bestDepth );
		System.out.println( "  events  kills=" + s.kills + "  gold=" + s.goldGains
				+ "  pickups=" + s.itemPickups + "  talents=" + s.talentsSpent
				+ "  crafted=" + s.itemsCrafted );
		System.out.println( "  stalls  " + stallCauses( s ) );
		System.out.println( "  types   " + typeTotals( s ) );
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


	private static String stallCauses( Snapshot s ){
		StringBuilder sb = new StringBuilder();
		int stalled = 0;
		for (StallCause cause : StallCause.values()){
			if (cause == StallCause.NONE || s.stallCauses[ cause.ordinal() ] == 0) continue;
			stalled += s.stallCauses[ cause.ordinal() ];
		}
		if (stalled == 0) return "none";

		for (StallCause cause : StallCause.values()){
			int count = s.stallCauses[ cause.ordinal() ];
			if (count == 0 || cause == StallCause.NONE) continue;
			if (sb.length() > 0) sb.append( "  " );
			sb.append( cause.name().toLowerCase( java.util.Locale.ROOT ) );
			sb.append( " " ).append( count );
			sb.append( String.format( " (%.0f%%)", pct( count, s.episodes ) ));
		}
		return sb + " of " + stalled + " stalled episodes";
	}


	private static String typeTotals( Snapshot s ){
		StringBuilder sb = new StringBuilder();
		for (RewardTerm.Type type : RewardTerm.Type.values()){
			double perEpisode = s.typeTotals[ type.ordinal() ] / Math.max( 1, s.episodes );
			if (perEpisode == 0) continue;
			if (sb.length() > 0) sb.append( "  " );
			sb.append( type.name().toLowerCase( java.util.Locale.ROOT ) );
			sb.append( " " ).append( Ansi.signed( perEpisode ) );
		}
		if (sb.length() == 0) return "none";
		return sb + "  (mean per episode)";
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

	static long gridBytes( java.util.List< com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition > retained ){
		long bytes = 0;
		for (com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition t : retained) bytes += t.grid.length;
		return bytes;
	}

	static long sampledBytes( java.util.List< com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition > retained ){
		long bytes = gridBytes( retained );
		for (com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition t : retained){
			bytes += 4L * (t.inventory.length + t.hero.length + t.actionMask.length + t.slotMask.length + t.targetMask.length);
		}
		return bytes;
	}

	static void tally( Snapshot s, java.util.List<Episode> episodes ){
		long totalTurns = 0;
		int best = Integer.MIN_VALUE, worst = Integer.MAX_VALUE;
		Episode bestEpisode = null;

		for (Episode e : episodes){
			s.meanScore += e.score;
			s.meanDepth += e.depth;
			s.meanTurns += e.turns;
			s.meanObjectiveScore += e.objectiveScore;
			if (e.depth >= 2) s.depth2PlusEpisodes++;
			s.kills += e.termCounts[ RewardTerm.KILL.ordinal() ];
			s.goldGains += e.termCounts[ RewardTerm.GOLD_GAIN.ordinal() ];
			s.itemPickups += e.termCounts[ RewardTerm.ITEM_PICKUP.ordinal() ];
			s.talentsSpent += e.termCounts[ RewardTerm.TALENT_UPGRADE.ordinal() ];
			s.itemsCrafted += e.termCounts[ RewardTerm.CRAFTED.ordinal() ];
			for (RewardTerm term : RewardTerm.values()){
				s.typeTotals[ term.type().ordinal() ] += e.termTotals[ term.ordinal() ];
			}
			s.stallCauses[ e.stallCause ]++;
			totalTurns += e.turns;
			best = Math.max( best, (int) e.score );
			worst = Math.min( worst, (int) e.score );
			if (bestEpisode == null || e.score > bestEpisode.score) bestEpisode = e;

			countEndReason( s, e.reason );
		}

		int n = episodes.size();
		s.meanScore /= n;
		s.meanObjectiveScore /= n;
		s.meanDepth /= n;
		s.meanTurns /= n;
		s.bestScore = best;
		s.worstScore = worst;
		s.bestDepth = bestEpisode.depth;
		s.totalTurns = totalTurns;
	}

	private static void countEndReason( GenerationReport.Snapshot s, String reason ){
		if (reason == null || reason.isEmpty()){
			s.endReasons[ RewardModel.TerminateReason.OTHER.ordinal() ]++;
			return;
		}

		RewardModel.TerminateReason[] reasons = RewardModel.TerminateReason.values();
		for (int i = 0; i < reasons.length; i++){
			if (reasons[ i ].name().equals( reason )){
				s.endReasons[ i ]++;
				return;
			}
		}

		s.endReasons[ RewardModel.TerminateReason.OTHER.ordinal() ]++;
		if (!warnedUnknownReason){
			warnedUnknownReason = true;
			System.err.println( "[WARN] a worker reported the end reason '" + reason
					+ "', which this build does not know. Counted as OTHER; the trainer and workers are"
					+ " probably from different builds." );
		}
	}

	private static boolean warnedUnknownReason;
}
