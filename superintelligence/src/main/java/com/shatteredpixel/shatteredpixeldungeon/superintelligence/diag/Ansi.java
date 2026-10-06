package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

/**
 * Terminal colouring for the trainer dashboard.
 *
 * docs.md: "a summary interface should display floor-by-floor point gains and losses... Numbers
 * should be color-coded in green or red, accompanied by graphs depicting score over turns
 * (annotated with floor transitions) or score over floors."
 *
 * ANSI is used rather than an icon set so the same report code can render to a console, a log file
 * or a terminal window unchanged. Colour is emitted only when the output is believed to be a
 * terminal; redirected output gets plain text, which also keeps log files readable.
 */
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
		//dumb terminals cannot render escapes, and NO_COLOR is an explicit request
		if ( term == null || term.equals( "dumb" ) ) return false;
		return System.getenv( "NO_COLOR" ) == null;
	}

	/** Green for a gain, red for a loss, matching the positive/negative convention throughout. */
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

	/** Green or red value, formatted to two decimals. */
	public static String signed( double value ){
		return wrap( String.format( "%+.2f", value ), forValue( value ) );
	}

	/** Right-padded fixed-width cell, so columns line up in a monospace terminal. */
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