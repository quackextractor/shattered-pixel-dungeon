package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import java.util.ArrayList;
import java.util.List;

public final class GateReport {

	private static final List< String > failures = new ArrayList<>();

	private GateReport(){}

	public static void fail( String message ){
		failures.add( message );
	}

	public static void fail( String where, String what ){
		failures.add( where + ": " + what );
	}

	public static int failureCount(){
		return failures.size();
	}

	public static List< String > failures(){
		return failures;
	}

	public static int report( String label, int checks ){
		if (failures.isEmpty()){
			System.out.println( "[OK]     " + label + ": " + checks + " checks passed" );
			return 0;
		}

		System.out.println( "[ERROR]  " + label + ": " + failures.size() + " of " + checks
				+ " checks failed" );
		for (String f : failures) System.out.println( "        " + f );
		return 1;
	}
}