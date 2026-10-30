package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class KernelProfile {

	static final boolean ON = Boolean.getBoolean( "spd.clprofile" );

	private static final Map< String, long[] > TOTALS = new ConcurrentHashMap<>();

	private KernelProfile(){}

	static void add( String kernel, long nanos ){
		long[] t = TOTALS.computeIfAbsent( kernel, k -> new long[ 2 ] );
		synchronized (t){
			t[ 0 ] += nanos;
			t[ 1 ]++;
		}
	}

	public static void reset(){
		TOTALS.clear();
	}

	public static String report(){
		StringBuilder b = new StringBuilder();
		TOTALS.entrySet().stream().sorted( ( x, y ) -> Long.compare( y.getValue()[ 0 ], x.getValue()[ 0 ] )).forEach( e ->
				b.append( String.format( Locale.ROOT, "    %-30s %8.1f ms %7d launches %8.1f us each%n", e.getKey(),
						e.getValue()[ 0 ] / 1e6, e.getValue()[ 1 ], e.getValue()[ 0 ] / 1e3 / e.getValue()[ 1 ] )));
		return b.toString();
	}
}
