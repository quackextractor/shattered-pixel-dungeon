package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DocCheck {

	private static final int CHECKS = 4;

	private static final Pattern GATE_COUNT = Pattern.compile(
			"\\*\\*([A-Za-z-]+) gates, one invocation\\.\\*\\*" );

	private static final Pattern JAVA_PATH = Pattern.compile(
			"[A-Za-z0-9_$]+(?:/[A-Za-z0-9_$]+)*\\.java" );

	private static final Pattern JAVA_CLASS_NAME = Pattern.compile(
			"com\\.(?:shatteredpixel\\.shatteredpixeldungeon|watabou)\\.[A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)+" );

	private static final String[] SCANNED_DOCS = {
			"superintelligence/research.md",
			"superintelligence/docs.md",
			"superintelligence/README.md",
			"superintelligence/ENGINE-CHANGES.md",
			"docs/documentation.md"
	};

	public static void main( String[] args ){
		checkNoMarkdownCarriesAControlByte();
		checkEveryGateIsDispatchable();
		checkTheDocumentedGateCountIsTheRealOne();
		checkMarkdownJavaPathsExist();

		System.exit( GateReport.report( "documentation", CHECKS ));
	}

	private static void fail( String message ){
		GateReport.fail( message );
	}


	private static void checkNoMarkdownCarriesAControlByte(){
		List< String > damaged = new ArrayList<>();

		for (Path file : markdownUnder( moduleDir() )){
			byte[] bytes;
			try {
				bytes = Files.readAllBytes( file );
			} catch (IOException e){
				damaged.add( file + " could not be read: " + e.getMessage() );
				continue;
			}

			StringBuilder where = new StringBuilder();
			for (int i = 0; i < bytes.length; i++){
				int b = bytes[ i ] & 0xff;
				if (b == '\n' || b == '\r' || b == '\t' ) continue;
				if (b >= 0x20 || b == 0x7f ) continue;
				where.append( " 0x" ).append( String.format( "%02X", b ) )
						.append( " at byte " ).append( i );
			}

			if (where.length() > 0){
				damaged.add( file.getFileName() + ":" + where );
			}
		}

		if (!damaged.isEmpty()){
			fail( damaged.size() + " markdown file(s) carry a control byte, which is how the"
					+ " backticked names in README.md lost their first characters: "
					+ String.join( "; ", damaged ) + ". A file with a NUL or a backspace in it is read"
					+ " as binary by grep, so the pre-commit hook's badge check silently skipped it and"
					+ " reported a mismatch it could not fix - and then rewrote the badge with grep's own"
					+ " 'Binary file ... matches' line. Every byte below 0x20 except tab, CR and LF is a"
					+ " failure here, NUL included." );
			return;
		}

		System.out.println( "  no markdown under " + moduleDir().getName()
				+ " carries a control byte, so every file reads as text" );
	}


	private static void checkEveryGateIsDispatchable(){
		Path build = moduleDir().toPath().resolve( "build.gradle" );
		Path main = moduleDir().toPath().resolve( "src/main/java/com/shatteredpixel/shatteredpixeldungeon"
				+ "/superintelligence/Main.java" );

		String script;
		String dispatcher;
		try {
			script = Files.readString( build );
			dispatcher = Files.readString( main );
		} catch (IOException e){
			fail( "could not read build.gradle or Main.java: " + e.getMessage() );
			return;
		}

		List< String > unreachable = new ArrayList<>();
		for (String gate : gatesOf( script )){
			if (dispatcher.contains( "\"" + gate + "\"" )) continue;
			if (hasOwnMainClass( script, gate )) continue;
			unreachable.add( gate );
		}

		if (!unreachable.isEmpty()){
			fail( unreachable.size() + " gate(s) are in the `gates` task and are reachable neither"
					+ " through Main's dispatch nor through a mainClass of their own: "
					+ String.join( ", ", unreachable ) + ". Gradle would run them, they would print the"
					+ " usage and exit 0, and the gate suite would report a pass it never earned." );
			return;
		}

		List< String > cliWithoutCase = new ArrayList<>();
		Matcher cli = Pattern.compile( "cliTask\\(\\s*'([A-Za-z]+)'" ).matcher( script );
		while (cli.find()){
			if (!dispatcher.contains( "\"" + cli.group( 1 ) + "\"" )){
				cliWithoutCase.add( cli.group( 1 ) );
			}
		}

		if (!cliWithoutCase.isEmpty()){
			fail( cliWithoutCase.size() + " task(s) are registered through cliTask and have no case in"
					+ " Main: " + String.join( ", ", cliWithoutCase ) + ". cliTask prepends the task"
					+ " name and relies on Main knowing it, so one without a case is a task that runs the"
					+ " wrong command." );
			return;
		}

		System.out.println( "  all " + gatesOf( script ).size() + " gates are reachable, and every"
				+ " cliTask has a case in Main" );
	}


	private static boolean hasOwnMainClass( String script, String task ){
		Matcher m = Pattern.compile( "(?s)tasks\\.register\\(\\s*'" + Pattern.quote( task )
				+ "'.*?mainClass\\s*=" ).matcher( script );
		return m.find();
	}


	private static void checkTheDocumentedGateCountIsTheRealOne(){
		Path build = moduleDir().toPath().resolve( "build.gradle" );
		Path readme = moduleDir().toPath().resolve( "README.md" );

		String script;
		String prose;
		try {
			script = Files.readString( build );
			prose = Files.readString( readme );
		} catch (IOException e){
			fail( "could not read build.gradle or README.md: " + e.getMessage() );
			return;
		}

		int actual = gatesOf( script ).size();

		Matcher claimed = GATE_COUNT.matcher( prose );
		if (!claimed.find()){
			fail( "README.md no longer says how many gates there are, in the form"
					+ " '**Thirty-one gates, one invocation.**'. The number it used to carry was the only"
					+ " place a reader could compare the list against reality." );
			return;
		}

		int stated = spelledNumber( claimed.group( 1 ));
		if (stated != actual ){
			fail( "README.md says '" + claimed.group( 1 ) + " gates, one invocation' and build.gradle's"
					+ " `gates` task lists " + actual + ". One of the two is now wrong, and the README is"
					+ " the one a reader counts from." );
			return;
		}

		System.out.println( "  README.md's gate count matches the `gates` task: " + actual );
	}


	private static int spelledNumber( String word ){
		switch (word.toLowerCase()){
			case "twenty": return 20;
			case "twenty-one": return 21;
			case "twenty-two": return 22;
			case "twenty-three": return 23;
			case "twenty-four": return 24;
			case "twenty-five": return 25;
			case "twenty-six": return 26;
			case "twenty-seven": return 27;
			case "twenty-eight": return 28;
			case "twenty-nine": return 29;
			case "thirty": return 30;
			case "thirty-one": return 31;
			case "thirty-two": return 32;
			case "thirty-three": return 33;
			case "thirty-four": return 34;
			default:
				try {
					return Integer.parseInt( word );
				} catch (NumberFormatException e){
					return -1;
				}
		}
	}


	private static List< String > gatesOf( String script ){
		java.util.LinkedHashSet< String > out = new java.util.LinkedHashSet<>();
		Matcher m = Pattern.compile( "(?s)tasks\\.register\\(\\s*'gates'\\s*\\)(.*?)\\n\\s*group\\s*=" )
				.matcher( script );
		if (!m.find()) return List.of();

		Matcher names = Pattern.compile( "'([A-Za-z]+)'" ).matcher( m.group( 1 ));
		while (names.find()) out.add( names.group( 1 ));
		return new ArrayList<>( out );
	}


	private static File moduleDir(){
		File here = new File( "." ).getAbsoluteFile();
		for (int i = 0; i < 6 && here != null; i++){
			if (new File( here, "superintelligence/build.gradle" ).isFile()){
				return new File( here, "superintelligence" );
			}
			if (new File( here, "build.gradle" ).isFile()
					&& new File( here, "superintelligence.properties" ).isFile()){
				return here;
			}
			here = here.getParentFile();
		}
		return new File( "." ).getAbsoluteFile();
	}


	private static void checkMarkdownJavaPathsExist(){
		File repo = repoRoot();
		java.util.Set< String > sources = javaSources( repo );
		if (sources.isEmpty()){
			fail( "no Java sources found under " + repo + ": the path check has nothing to resolve"
					+ " against, so it would pass over every reference in every document." );
			return;
		}

		List< String > broken = new ArrayList<>();
		int checked = 0;

		for (String doc : SCANNED_DOCS){
			Path file = repo.toPath().resolve( doc.replace( '/', java.io.File.separatorChar ) );
			String text = read( file );
			if (text.isEmpty()){
				fail( "could not read " + doc + ", so its Java references are unchecked" );
				return;
			}

			for (String reference : javaReferences( text )){
				checked++;
				if (resolves( sources, reference )) continue;
				broken.add( doc + " names " + reference );
			}
		}

		if (!broken.isEmpty()){
			fail( broken.size() + " Java reference(s) in the documentation name a file that does not"
					+ " exist: " + String.join( "; ", broken ) + ". A document that names a class where"
					+ " it no longer lives reads as though the reader should go and find it, and a"
					+ " package that moved in an upstream merge is exactly how that happens: the"
					+ " check resolves each reference against core/src/main/java,"
					+ " SPD-classes/src/main/java, desktop/src/main/java and"
					+ " superintelligence/src/main/java." );
			return;
		}

		System.out.println( "  " + checked + " Java reference(s) across " + SCANNED_DOCS.length
				+ " document(s) resolve against " + sources.size() + " source files" );
	}


	private static List< String > javaReferences( String text ){
		java.util.LinkedHashSet< String > out = new java.util.LinkedHashSet<>();

		Matcher paths = JAVA_PATH.matcher( text );
		while (paths.find()) out.add( paths.group() );

		Matcher names = JAVA_CLASS_NAME.matcher( text );
		while (names.find()){
			String name = names.group();
			boolean isFile = name.endsWith( ".java" );
			if (isFile) name = name.substring( 0, name.length() - ".java".length() );
			out.add( name.replace( '.', '/' ) + (isFile ? ".java" : ".java") );
		}
		return new ArrayList<>( out );
	}


	private static boolean resolves( java.util.Set< String > sources, String reference ){
		String slash = reference.replace( '\\', '/' );
		return sources.contains( slash ) || sources.contains( slash.replace( ".java", ".java" ) )
				|| sources.stream().anyMatch( key -> key.endsWith( "/" + slash ) );
	}


	private static java.util.Set< String > javaSources( File repo ){
		java.util.LinkedHashSet< String > out = new java.util.LinkedHashSet<>();
		String[] roots = {
				"core/src/main/java",
				"SPD-classes/src/main/java",
				"desktop/src/main/java",
				"superintelligence/src/main/java"
		};

		for (String root : roots){
			Path base = repo.toPath().resolve( root.replace( '/', java.io.File.separatorChar ) );
			if (!Files.isDirectory( base )) continue;
			try (java.util.stream.Stream< Path > walk = Files.walk( base )){
				walk.filter( p -> p.getFileName().toString().endsWith( ".java" ) )
						.forEach( p -> {
							String fromRoot = base.relativize( p ).toString().replace( '\\', '/' );
							out.add( fromRoot );
							out.add( root + "/" + fromRoot );
							out.add( fromRoot.substring( fromRoot.lastIndexOf( '/' ) + 1 ));
						});
			} catch (IOException e){
				fail( "could not walk " + root + ": " + e.getMessage() );
			}
		}
		return out;
	}


	private static File repoRoot(){
		File here;
		try {
			here = moduleDir().getCanonicalFile();
		} catch (IOException e){
			here = moduleDir().getAbsoluteFile();
		}
		for (int i = 0; i < 6 && here != null; i++){
			if (new File( here, "superintelligence/build.gradle" ).isFile()
					&& new File( here, "docs" ).isDirectory()){
				return here;
			}
			here = here.getParentFile();
		}
		return moduleDir().getAbsoluteFile();
	}


	private static List< Path > markdownUnder( File dir ){
		List< Path > out = new ArrayList<>();
		if (dir == null || !dir.isDirectory()) return out;

		try (java.util.stream.Stream< Path > walk = Files.walk( dir.toPath() )){
			walk.filter( p -> p.getFileName().toString().endsWith( ".md" ) )
					.filter( p -> !p.toString().replace( '\\', '/' ).contains( "/out/" ) )
					.forEach( out::add );
		} catch (IOException e){
			fail( "could not walk " + dir + ": " + e.getMessage() );
		}
		return out;
	}


	private static String read( Path path ){
		try {
			return new String( Files.readAllBytes( path ), StandardCharsets.UTF_8 );
		} catch (IOException e){
			return "";
		}
	}
}