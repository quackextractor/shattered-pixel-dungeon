package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;

/**
 * Running tally of where a run's score came from.
 *
 * docs.md asks for a summary that shows "floor-by-floor point gains and losses, type totals and
 * subtotals, and the overall run score". A single scalar cannot produce that, so every
 * {@link RewardTerm} is accumulated separately, per floor as well as per run.
 *
 * The per-floor totals are what make the trainer interface possible: the dashboard renders one row
 * per floor, and the red/green colouring comes straight from {@link FloorTotals#net}.
 */
public class RewardLedger {

	/** Per-floor rollup, kept in visit order so the dashboard can print floors as a table. */
	private final java.util.ArrayList<FloorTotals> floors = new java.util.ArrayList<>();

	/** Per-term run totals. */
	private final double[] runTotals = new double[ RewardTerm.count() ];

	private FloorTotals current;

	/** Total reward handed to the learner so far this run. */
	private double total;

	/** Reward accumulated during the current turn. */
	private double pending;

	/**
	 * Scale applied to shaping terms, driven by the curriculum.
	 *
	 * research.md Phase 2 fades the micro rewards out so the agent stops hoarding. Applying the
	 * fade here rather than at the point of computation means the floor breakdown the dashboard
	 * renders is already the scaled score the learner actually saw, so the two can never
	 * disagree.
	 */
	private double shapingScale = 1.0;

	public void shapingScale( double scale ){
		this.shapingScale = scale;
	}

	public double shapingScale(){
		return shapingScale;
	}

	public static class FloorTotals {

		public final int depth;
		public final int branch;
		public final double[] totals = new double[ RewardTerm.count() ];

		/** True when this floor was left by descending rather than by dying or timing out. */
		public boolean cleared;

		public int turns;
		public int goldGained;
		public int kills;

		FloorTotals( int depth, int branch ){
			this.depth = depth;
			this.branch = branch;
		}

		/** Sum of every term on this floor. */
		public double net(){
			double sum = 0;
			for (double t : totals) sum += t;
			return sum;
		}

		/** Sum over the shaping terms only, ie. everything that the curriculum can switch off. */
		public double shapingNet(){
			double sum = 0;
			for (int i = 0; i < totals.length; i++){
				if (RewardTerm.at( i ).isShaping()) sum += totals[ i ];
			}
			return sum;
		}
	}

	/** Opens a new floor's row. Called when the hero enters a floor. */
	public void beginFloor( int depth, int branch ){
		current = new FloorTotals( depth, branch );
		floors.add( current );
	}

	/**
	 * Records reward for one term.
	 *
	 * @param clearedByAdvancing true when this floor ended because the hero descended, which is
	 *                           what distinguishes a cleared floor from one the hero fled
	 */
	public void add( RewardTerm term, double amount, boolean clearedByAdvancing ){
		if (term.isShaping()) amount *= shapingScale;

		int i = term.ordinal();
		runTotals[ i ] += amount;
		pending += amount;
		total += amount;
		if (current != null){
			current.totals[ i ] += amount;
			if (clearedByAdvancing) current.cleared = true;
		}
	}

	/** Marks the current floor as cleared by descending. */
	public void markFloorCleared(){
		if (current != null) current.cleared = true;
	}

	public void countTurn(){
		if (current != null) current.turns++;
	}

	public void countKill(){
		if (current != null) current.kills++;
	}

	public void countGold( int delta ){
		if (current != null && delta > 0) current.goldGained += delta;
	}

	/** Clears the per-turn accumulator and returns it. */
	public double flushTurn(){
		double out = pending;
		pending = 0;
		return out;
	}

	/** Reward for the turn just finished. */
	public double pending(){
		return pending;
	}

	/** Everything accumulated this run. */
	public double total(){
		return total;
	}

	public double termTotal( RewardTerm term ){
		return runTotals[ term.ordinal() ];
	}

	public java.util.ArrayList<FloorTotals> floors(){
		return floors;
	}

	public FloorTotals currentFloor(){
		return current;
	}

	/** Resets for a new episode. */
	public void reset(){
		floors.clear();
		java.util.Arrays.fill( runTotals, 0 );
		current = null;
		total = 0;
		pending = 0;
	}
}