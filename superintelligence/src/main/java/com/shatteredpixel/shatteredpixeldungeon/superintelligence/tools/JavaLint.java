package com.shatteredpixel.shatteredpixeldungeon.superintelligence.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public class JavaLint {

	public static void main( String[] args ){
		File root = new File( args.length > 0 ? args[ 0 ] : "src/main/java" );
		if (!root.isDirectory()){
			System.err.println( "[ERROR] javaclint: " + root + " is not a directory" );
			System.exit( 2 );
		}

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null){

			System.err.println( "[WARN]  javaclint: no system java compiler available, skipping" );
			return;
		}

		List< File > sources = new ArrayList<>();
		collect( root, sources );

		DiagnosticCollector< JavaFileObject > diagnostics = new DiagnosticCollector<>();
		try (StandardJavaFileManager files = compiler.getStandardFileManager( diagnostics, null, null )) {
			Iterable< ? extends JavaFileObject > units =
					files.getJavaFileObjectsFromFiles( sources );
			List< String > options = new ArrayList<>( List.of(
					"-Xlint:all",
					"-Werror",
					"-proc:none",
					"-d", com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Scratch.dir( "javaclint" ).getAbsolutePath(),
					"-classpath", System.getProperty( "java.class.path" ) ) );
			boolean ok = compiler.getTask( null, files, diagnostics, options, null, units ).call();
			report( diagnostics );
			boolean uncommented = reportComments( sources );
			System.exit( ok && uncommented ? 0 : 1 );
		} catch (java.io.IOException e){
			System.err.println( "[ERROR] javaclint: " + e.getMessage() );
			System.exit( 2 );
		}
	}

	private static void collect( File dir, List< File > out ){
		File[] entries = dir.listFiles();
		if (entries == null ) return;
		for (File f : entries){
			if (f.isDirectory()) collect( f, out );
			else if (f.getName().endsWith( ".java" )) out.add( f );
		}
	}

	private static boolean reportComments( List< File > sources ) throws java.io.IOException {
		boolean clean = true;
		for (File f : sources){
			String source = java.nio.file.Files.readString( f.toPath() );
			if (InlineCommentStripper.strip( source, true ).equals( source )) continue;
			System.out.println( "[ERROR] javaclint: " + f + " carries a // or /** comment" );
			clean = false;
		}
		return clean;
	}

	private static void report( DiagnosticCollector< JavaFileObject > diagnostics ){
		int errors = 0;
		int warnings = 0;
		for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()){
			if (d.getKind() == Diagnostic.Kind.ERROR) errors++;
			else warnings++;
			System.out.println( "  " + d.getKind().toString().toLowerCase() + " "
					+ d.getSource() + ":" + d.getLineNumber() + " " + d.getMessage( null ) );
		}
		if (errors == 0 && warnings == 0){
			System.out.println( "[OK]     javac lint: no warnings across -Xlint:all" );
		}
	}
}