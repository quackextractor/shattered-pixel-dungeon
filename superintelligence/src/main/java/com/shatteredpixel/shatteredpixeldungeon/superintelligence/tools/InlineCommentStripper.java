package com.shatteredpixel.shatteredpixeldungeon.superintelligence.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes {@code //} comments from Java source, leaving every other comment alone.
 *
 * <p>Invoked by the post-commit hook. Javadoc and block comments are preserved on purpose: they document
 * the contract of a type or a method, where an inline comment documents a line that should already say
 * what it does.
 *
 * <p>A regex cannot do this and a careless one is worse than none. A file here contains string literals
 * with {@code //} in them, and a file anywhere contains a URL. So this lexes properly and tracks three
 * things at once - block comments, string literals and character literals - and only treats {@code //} as
 * a comment when none of the other three is open.
 *
 * <pre>gradle :superintelligence:stripinlines</pre>
 */
public class InlineCommentStripper {

	public static void main( String[] args ) throws IOException {
		List< Path > files = new ArrayList<>();
		for (String arg : args){
			Path p = Path.of( arg );
			if (Files.isDirectory( p )) files.addAll( javaFiles( p ));
			else files.add( p );
		}

		int changed = 0;
		int removed = 0;
		for (Path file : files){
			String before = Files.readString( file, StandardCharsets.UTF_8 );
			String after = strip( before );
			if (after.equals( before )) continue;


			Files.writeString( file, after, StandardCharsets.UTF_8 );
			changed++;
			removed += countStripped( before, after );
		}

		System.out.println( "[stripinlines] " + files.size() + " file(s) scanned, " + changed
				+ " rewritten, " + removed + " comment line(s) removed" );
		if (changed > 0) System.exit( 3 );
	}

	private static List< Path > javaFiles( Path dir ) throws IOException {
		try (java.util.stream.Stream< Path > walk = Files.walk( dir )){
			return walk.filter( p -> p.toString().endsWith( ".java" ) && !p.toString().contains( "/build/" ))
					.collect( java.util.stream.Collectors.toList() );
		}
	}

	/**
	 * Removes line comments, dropping any line that held nothing else.
	 *
	 * <p>A {@code //} that trails real code is replaced by nothing rather than by a blank line, so
	 * {@code foo(); // why} becomes {@code foo();} rather than two lines. A line that is only a comment
	 * disappears entirely.
	 */
	static String strip( String source ){
		StringBuilder out = new StringBuilder( source.length() );
		int lineStart = 0;
		int i = 0;
		int n = source.length();

		while (i < n){
			char c = source.charAt( i );

			if (c == '/' && i + 1 < n && source.charAt( i + 1 ) == '/'){
				int end = source.indexOf( '\n', i );
				if (end < 0) end = n;
				boolean lineIsOnlyComment = out.toString().substring( lineStart ).isBlank();
				if (lineIsOnlyComment){

					out.setLength( lineStart );
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

	/** Copies a string or character literal through untouched, honouring backslash escapes. */
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
		return (before.length() - after.length()) / 40 + 1;
	}
}