package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

public class Ansi {

	private static boolean enabled = detect();

	public static final String RESET   = "\u001B[0m";
	public static final String BOLD    = "\u001B[1m";
	public static final String DIM     = "\u001B[2m";

	public static final String GREEN   = "\u001B[32m";
	public static final String RED     = "\u001B[31m";
	public static final String YELLOW  = "\u001B[33m";
	public static final String BLUE    = "\u001B[34m";
	public static final String CYAN    = "\u001B[36m";
	public static final String WHITE   = "\u001B[37m";

	public static void enable( boolean on ){
		enabled = on;
	}

	public static boolean enabled(){
		return enabled;
	}

	private static boolean detect(){
		if (System.console() == null) return false;
		String term = System.getenv( "TERM" );

		if ( term == null || term.equals( "dumb" ) ) return false;
		return System.getenv( "NO_COLOR" ) == null;
	}

	public static String forValue( double value ){
		if (!enabled) return "";
		if (value > 0)  return GREEN;
		if (value < 0)  return RED;
		return WHITE;
	}

	public static String wrap( String text, String colour ){
		if (!enabled || colour == null || colour.isEmpty()) return text;
		return colour + text + RESET;
	}

	public static String signed( double value ){
		return wrap( String.format( "%+.2f", value ), forValue( value ) );
	}

	public static String cell( String text, int width ){
		if (text.length() >= width) return text.substring( 0, width );
		StringBuilder sb = new StringBuilder( text );
		while (sb.length() < width) sb.append( ' ' );
		return sb.toString();
	}

	public static String padLeft( String text, int width ){
		if (text.length() >= width) return text.substring( text.length() - width );
		StringBuilder sb = new StringBuilder();
		while (sb.length() < width - text.length()) sb.append( ' ' );
		sb.append( text );
		return sb.toString();
	}
}