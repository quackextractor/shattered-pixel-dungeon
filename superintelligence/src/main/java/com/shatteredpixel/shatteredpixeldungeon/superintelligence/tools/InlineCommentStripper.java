package com.shatteredpixel.shatteredpixeldungeon.superintelligence.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


public class InlineCommentStripper {

	public static void main( String[] args ) throws IOException {
		boolean javadoc = false;
		List< String > targets = new ArrayList<>();

		for (String arg : args){
			if (arg.equals( "--javadoc" )){
				javadoc = true;
				continue;
			}
			targets.add( arg );
		}

		List< Path > files = new ArrayList<>();
		for (String arg : targets){
			Path p = Path.of( arg );
			if (Files.isDirectory( p )) files.addAll( javaFiles( p ));
			else files.add( p );
		}

		int changed = 0;
		int removed = 0;
		for (Path file : files){
			String before = Files.readString( file, StandardCharsets.UTF_8 );
			String after = strip( before, javadoc );
			if (after.equals( before )) continue;

			Files.writeString( file, after, StandardCharsets.UTF_8 );
			changed++;
			removed += countStripped( before, after );
		}

		System.out.println( "[stripinlines] " + files.size() + " file(s) scanned, " + changed
				+ " rewritten, " + removed + " comment line(s) removed"
				+ (javadoc ? ", javadoc included" : "") );

	}

	private static List< Path > javaFiles( Path dir ) throws IOException {
		try (java.util.stream.Stream< Path > walk = Files.walk( dir )){
			return walk.filter( p -> p.toString().endsWith( ".java" ) && !p.toString().replace( '\\', '/' ).contains( "/build/" ))
					.collect( java.util.stream.Collectors.toList() );
		}
	}


static String strip( String source ){
		return strip( source, false );
	}


	static String strip( String source, boolean javadoc ){
		StringBuilder out = new StringBuilder( source.length() );
		int lineStart = 0;
		int i = 0;
		int n = source.length();

		while (i < n){
			char c = source.charAt( i );

			boolean javadocHere = javadoc && c == '/' && i + 2 < n
					&& source.charAt( i + 1 ) == '*' && source.charAt( i + 2 ) == '*';

			if (javadocHere || (c == '/' && i + 1 < n && source.charAt( i + 1 ) == '/')){
				int end;
				if (javadocHere){
					int close = source.indexOf( "*/", i + 2 );
					end = close < 0 ? n : close + 2;
				} else {
					end = source.indexOf( '\n', i );
					if (end < 0) end = n;
				}

				boolean lineIsOnlyComment = out.substring( lineStart ).toString().isBlank();
				if (lineIsOnlyComment){
					out.setLength( lineStart );
					i = source.startsWith( "\r\n", end ) ? end + 2 : source.startsWith( "\n", end ) ? end + 1 : end;
					lineStart = out.length();
					continue;
				}
				i = end;
				continue;
			}

			if (c == '/' && i + 1 < n && source.charAt( i + 1 ) == '*'){
				int end = source.indexOf( "*/", i + 2 );
				int stop = end < 0 ? n : end + 2;
				out.append( source, i, stop );
				i = stop;
				continue;
			}

			out.append( c );
			i++;

			if (c == '"'){
				i = skipLiteral( out, source, i, '"' );
			} else if (c == '\''){
				i = skipLiteral( out, source, i, '\'' );
			} else if (c == '\n'){
				lineStart = out.length();
			}
		}
		return out.toString();
	}


	private static int skipLiteral( StringBuilder out, String source, int i, char quote ){
		int n = source.length();
		while (i < n){
			char c = source.charAt( i );
			out.append( c );
			i++;
			if (c == '\\' && i < n){
				out.append( source.charAt( i ) );
				i++;
				continue;
			}
			if (c == quote) break;
			if (c == '\n') break;
		}
		return i;
	}

	private static int countStripped( String before, String after ){
		return lines( before ) - lines( after );
	}

	private static int lines( String text ){
		return (int) text.chars().filter( c -> c == '\n' ).count();
	}
}
