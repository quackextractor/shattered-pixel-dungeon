package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;

public class RewardLedger {

	private final java.util.ArrayList<FloorTotals> floors = new java.util.ArrayList<>();

	private final double[] runTotals = new double[ RewardTerm.count() ];

	private FloorTotals current;

	private double total;

	private double pending;

	private double shapingScale = 1.0;

	private com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile profile
			= RewardProfile.DEFAULT;

	public void profile( RewardProfile profile ){
		this.profile = profile == null ? RewardProfile.DEFAULT : profile;
	}

	public RewardProfile profile(){
		return profile;
	}

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

		public boolean cleared;

		public int turns;
		public int goldGained;
		public int kills;

		FloorTotals( int depth, int branch ){
			this.depth = depth;
			this.branch = branch;
		}

		public double net(){
			double sum = 0;
			for (double t : totals) sum += t;
			return sum;
		}

		public double shapingNet(){
			double sum = 0;
			for (int i = 0; i < totals.length; i++){
				if (RewardTerm.at( i ).isShaping()) sum += totals[ i ];
			}
			return sum;
		}
	}

	public void beginFloor( int depth, int branch ){
		current = new FloorTotals( depth, branch );
		floors.add( current );
	}

	private final int[] notes = new int[ RewardTerm.count() ];

	public int notes( RewardTerm term ){
		return term == null ? 0 : notes[ term.ordinal() ];
	}

	public void noteEvent( RewardTerm term ){
		if (term != null) notes[ term.ordinal() ]++;
	}

	public void add( RewardTerm term, double amount, boolean clearedByAdvancing ){
		notes[ term.ordinal() ]++;
		if (term.isShaping()) amount *= shapingScale;
		if (!profile.allows( term )) amount = 0;

		int i = term.ordinal();
		runTotals[ i ] += amount;
		pending += amount;
		total += amount;
		if (current != null){
			current.totals[ i ] += amount;
			if (clearedByAdvancing) current.cleared = true;
		}
	}

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

	public double flushTurn(){
		double out = pending;
		pending = 0;
		return out;
	}

	public double pending(){
		return pending;
	}

	public double total(){
		return total;
	}

	public double termTotal( RewardTerm term ){
		return runTotals[ term.ordinal() ];
	}

	public java.util.EnumMap<RewardTerm.Type, Double> typeTotals(){
		java.util.EnumMap<RewardTerm.Type, Double> out
				= new java.util.EnumMap<>( RewardTerm.Type.class );
		for (RewardTerm.Type type : RewardTerm.Type.values()) out.put( type, 0.0 );

		for (RewardTerm term : RewardTerm.values()){
			out.merge( term.type(), runTotals[ term.ordinal() ], Double::sum );
		}
		return out;
	}

	public java.util.EnumMap<RewardTerm.Type, Integer> typeCounts(){
		java.util.EnumMap<RewardTerm.Type, Integer> out
				= new java.util.EnumMap<>( RewardTerm.Type.class );
		for (RewardTerm.Type type : RewardTerm.Type.values()) out.put( type, 0 );

		for (int i = 0; i < notes.length; i++){
			if (notes[ i ] == 0) continue;
			out.merge( RewardTerm.at( i ).type(), notes[ i ], Integer::sum );
		}
		return out;
	}

	public java.util.ArrayList<FloorTotals> floors(){
		return floors;
	}

	public FloorTotals currentFloor(){
		return current;
	}

	public void reset(){
		floors.clear();
		java.util.Arrays.fill( runTotals, 0 );
		current = null;
		total = 0;
		pending = 0;
	}
}