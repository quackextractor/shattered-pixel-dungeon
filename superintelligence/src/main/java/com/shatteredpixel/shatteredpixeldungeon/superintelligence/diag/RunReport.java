package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardLedger;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;

/**
 * The run summary docs.md describes.
 *
 * "a summary interface should display floor-by-floor point gains and losses, type totals/subtotals,
 * and the overall run score. Numbers should be color-coded in green or red, accompanied by graphs
 * depicting score over turns (annotated with floor transitions) or score over floors."
 *
 * Every number here comes from {@link RewardLedger}, which accumulates per term per floor. That is
 * what makes the breakdown possible at all - a single run score cannot be attributed to a cause,
 * and an unattributed score is not something an agent can be trained against.
 */
public class RunReport {

	private final RewardLedger ledger;
	private final String seedText;

	/** Score after each turn, for the score-over-turns graph. */
	private final java.util.ArrayList<Float> scoreByTurn = new java.util.ArrayList<>();

	/** Depth at the end of each turn, so floor transitions can be annotated on that graph. */
	private final java.util.ArrayList<Integer> depthByTurn = new java.util.ArrayList<>();

	public RunReport( RewardLedger ledger, String seedText ){
		this.ledger = ledger;
		this.seedText = seedText;
	}

	/** Records one turn's cumulative score and the depth it happened on. */
	public void sample( float cumulativeScore, int depth ){
		scoreByTurn.add( cumulativeScore );
		depthByTurn.add( depth );
	}

	public float[] scoreSeries(){
		float[] out = new float[ scoreByTurn.size() ];
		for (int i = 0; i < out.length; i++) out[ i ] = scoreByTurn.get( i );
		return out;
	}

	public int[] depthSeries(){
		int[] out = new int[ depthByTurn.size() ];
		for (int i = 0; i < out.length; i++) out[ i ] = depthByTurn.get( i );
		return out;
	}

	public RewardLedger ledger(){
		return ledger;
	}

	/**
	 * Renders the full report.
	 *
	 * @param graphWidth columns to use for the score-over-turns graph
	 */
	public String render( int graphWidth ) {
		StringBuilder sb = new StringBuilder();

		renderHeader( sb );
		renderFloors( sb );
		renderTerms( sb );
		renderGraph( sb, graphWidth );

		return sb.toString();
	}

	private void renderHeader( StringBuilder sb ){
		sb.append( Ansi.wrap( "RUN " + (seedText.isEmpty() ? "<random>" : seedText), Ansi.BOLD + Ansi.CYAN ) );
		sb.append( "  score=" ).append( Ansi.signed( ledger.total() ) );
		sb.append( "  floors=" ).append( ledger.floors().size() );
		sb.append( "  deepest=" ).append( deepestFloor() );
		sb.append( '\n' );
	}

	private int deepestFloor(){
		int deepest = 0;
		for (RewardLedger.FloorTotals f : ledger.floors()){
			deepest = Math.max( deepest, f.depth );
		}
		return deepest;
	}

	private void renderFloors( StringBuilder sb ){
		sb.append( '\n' );
		sb.append( Ansi.wrap( Ansi.cell( "floor", 8 ) + Ansi.cell( "turns", 7 )
				+ Ansi.cell( "kills", 7 ) + Ansi.cell( "gold", 7 )
				+ Ansi.padLeft( "score", 10 ), Ansi.DIM ) );
		sb.append( '\n' );

		for (RewardLedger.FloorTotals floor : ledger.floors()){
			String label = (floor.branch == 0)
					? Integer.toString( floor.depth )
					: floor.depth + "-" + floor.branch;

			double net = floor.net();
			String status = floor.cleared ? "*" : " ";

			sb.append( Ansi.cell( status + label, 8 ) );
			sb.append( Ansi.cell( Integer.toString( floor.turns ), 7 ) );
			sb.append( Ansi.cell( Integer.toString( floor.kills ), 7 ) );
			sb.append( Ansi.cell( Integer.toString( floor.goldGained ), 7 ) );
			sb.append( Ansi.padLeft( Ansi.signed( net ), 10 + (Ansi.enabled() ? Ansi.GREEN.length() + Ansi.RESET.length() : 0) ) );
			sb.append( Ansi.wrap( " cleared", Ansi.DIM + Ansi.GREEN ) );
			sb.append( '\n' );
		}
	}

	private void renderTerms( StringBuilder sb ){
		sb.append( '\n' );
		sb.append( Ansi.wrap( "terms", Ansi.DIM ) );
		sb.append( '\n' );

		for (RewardTerm term : RewardTerm.values()){
			double total = ledger.termTotal( term );
			if (total == 0) continue;
			sb.append( "  " ).append( Ansi.cell( term.name(), 20 ) );
			sb.append( Ansi.padLeft( Ansi.signed( total ), 12 + (Ansi.enabled() ? 10 : 0) ) );
			sb.append('\n');
		}
	}

	/**
	 * Score over turns, with floor transitions marked.
	 *
	 * docs.md asks for the transitions to be annotated on the graph, because a reward spike that
	 * lines up with a floor change is usually just the depth bonus and not learning.
	 */
	private void renderGraph( StringBuilder sb, int width ){
		float[] series = scoreSeries();
		if (series.length == 0) return;

		sb.append( '\n' );
		sb.append( Ansi.wrap( "score over turns (| marks a floor change)", Ansi.DIM ) );
		sb.append( '\n' );

		Graph.Row row = Graph.spark( series, width );
		int[] depths = depthSeries();

		int[] depthLine = new int[ width ];
		int lastDepth = depths.length > 0 ? depths[ 0 ] : 0;
		for (int i = 0; i < width; i++){
			int index = (int) ((long) i * depths.length / Math.max( 1, width ));
			int depth = index < depths.length ? depths[ index ] : lastDepth;
			if (depth != lastDepth){
				depthLine[ i ] = 1;
				lastDepth = depth;
			}
		}

		for (int i = 0; i < width; i++){
			if (depthLine[ i ] == 1){
				//splice the marker in without disturbing the series shape
				row.text = row.text.substring( 0, i ) + Ansi.wrap( "|", Ansi.YELLOW )
						+ row.text.substring( i + 1 );
			}
		}

		sb.append( "  " ).append( row.text ).append( '\n' );
		sb.append( Ansi.wrap( "  range " + row.axis(), Ansi.DIM ) ).append( '\n' );
	}

	/** Score against floor number, for the "score over floors" view. */
	public String renderScoreOverFloors( int width ){
		StringBuilder sb = new StringBuilder();
		java.util.ArrayList<RewardLedger.FloorTotals> floors = ledger.floors();
		if (floors.isEmpty()) return "";

		float[] net = new float[ floors.size() ];
		for (int i = 0; i < floors.size(); i++) net[ i ] = (float) floors.get( i ).net();

		Graph.Row row = Graph.bar( net, Math.min( width, floors.size() ) );

		sb.append( Ansi.wrap( "score over floors", Ansi.DIM ) ).append( '\n' );
		sb.append( "  " ).append( row.text ).append( '\n' );
		sb.append( Ansi.wrap( "  range " + row.axis(), Ansi.DIM ) ).append( '\n' );

		return sb.toString();
	}
}