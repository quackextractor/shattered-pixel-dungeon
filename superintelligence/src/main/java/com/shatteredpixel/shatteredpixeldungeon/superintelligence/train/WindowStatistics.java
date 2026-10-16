package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

public final class WindowStatistics {

	private WindowStatistics(){}

	public static double mean( double[] values ){
		if (values == null || values.length == 0) return 0;
		double sum = 0;
		for (double value : values) sum += value;
		return sum / values.length;
	}


	public static double sampleStdDev( double[] values ){
		if (values == null || values.length < 2) return 0;

		double mean = mean( values );
		double sum = 0;
		for (double value : values) sum += (value - mean) * (value - mean);
		return Math.sqrt( sum / (values.length - 1) );
	}


	public static double standardError( double[] values ){
		if (values == null || values.length < 2) return 0;
		return sampleStdDev( values ) / Math.sqrt( values.length );
	}


	public static double differenceError( double[] first, double[] second ){
		double a = standardError( first );
		double b = standardError( second );
		return Math.sqrt( a * a + b * b );
	}


	public static double rate( double[] numerators, double[] denominators ){
		double top = 0;
		double bottom = 0;
		if (numerators != null) for (double n : numerators) top += n;
		if (denominators != null ){
			for (double d : denominators){
				if (d > 0) bottom += d;
			}
		}
		return bottom <= 0 ? 0 : top / bottom;
	}
}