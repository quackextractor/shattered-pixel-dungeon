package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;
import java.util.List;

public class OrderedDiff {

	private OrderedDiff() {}

	public static int firstDifference( List< String > left, List< String > right ){
		int shared = Math.min( left.size(), right.size() );
		for (int i = 0; i < shared; i++){
			if (!left.get( i ).equals( right.get( i ) )) return i;
		}
		return left.size() == right.size() ? -1 : shared;
	}

	public static String report( String leftName, String rightName, List< String > left, List< String > right,
			int window ){
		int at = firstDifference( left, right );
		if (at < 0){
			return leftName + " and " + rightName + " agree on all " + left.size() + " entries";
		}

		StringBuilder sb = new StringBuilder();
		sb.append( leftName ).append( " and " ).append( rightName ).append( " part at entry " )
				.append( at ).append( " of " ).append( Math.max( left.size(), right.size() ) ).append( '\n' );

		for (int i = Math.max( 0, at - window ); i <= at; i++){
			sb.append( "    " ).append( i ).append( "  " ).append( leftName ).append( ": " )
					.append( entry( left, i ) ).append( '\n' );
			sb.append( "    " ).append( i ).append( "  " ).append( rightName ).append( ": " )
					.append( entry( right, i ) ).append( '\n' );
		}
		sb.append( "    " ).append( at + 1 ).append( "  " ).append( leftName ).append( ": " )
				.append( entry( left, at + 1 ) ).append( '\n' );
		sb.append( "    " ).append( at + 1 ).append( "  " ).append( rightName ).append( ": " )
				.append( entry( right, at + 1 ) ).append( '\n' );
		return sb.toString();
	}

	private static String entry( List< String > log, int index ){
		if (index < log.size()) return log.get( index );
		return "(past the end)";
	}
}