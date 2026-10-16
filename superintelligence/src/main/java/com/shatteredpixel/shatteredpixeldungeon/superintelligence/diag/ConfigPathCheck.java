package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli.CliOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ConfigDefaults.fail;

public final class ConfigPathCheck {

	private ConfigPathCheck(){}

	public static void checkTheBuildScriptReadsTheHeapItDocuments(){
		Path build;
		try {
			build = Path.of( "superintelligence/build.gradle" );
			if (!Files.isReadable( build )) build = Path.of( "build.gradle" );
		} catch (RuntimeException e){
			fail( "could not name the module's build script from "
					+ new File( "." ).getAbsolutePath() + ": " + e.getMessage() );
			return;
		}
		if (!Files.isReadable( build )){
			fail( "could not read the build script at " + build + ". It is a committed file; this check"
					+ " cannot pass without it, and failing is better than skipping." );
			return;
		}

		String script;
		try {
			script = Files.readString( build );
		} catch (IOException e){
			fail( "could not read " + build + ": " + e.getMessage() );
			return;
		}

		for (String setting : new String[]{ "SPD_WORKER_HEAP_MIN", "SPD_WORKER_HEAP_MAX",
				"SPD_WORKER_GC_PAUSE_MS" }){
			if (script.contains( setting )) continue;
			fail( "the build script does not read " + setting + ", so the heap the trainer JVM is"
					+ " launched with is a literal in a Gradle file while worker.heap_min,"
					+ " worker.heap_max and worker.gc_pause_ms are documented settings that do not"
					+ " reach it. Two places that decide one run's heap, one of them unreachable." );
			return;
		}

		for (String literal : new String[]{ "-Xmx1536m", "-Xms256m", "MaxGCPauseMillis=200" }){
			if (!script.contains( literal )) continue;
			fail( "the build script still carries the literal " + literal + " alongside the"
					+ " SPD_WORKER_* names, so the literal wins whenever they disagree" );
			return;
		}

		System.out.println( "  the trainer JVM's heap and GC pause come from SPD_WORKER_HEAP_MIN,"
				+ " SPD_WORKER_HEAP_MAX and SPD_WORKER_GC_PAUSE_MS, not from literals" );
	}


	public static void checkEveryPathIsReportedAbsolute(){
		File relative = new File( "superintelligence/out/run/metrics.csv" );

		String reported = com.shatteredpixel.shatteredpixeldungeon.superintelligence.train
				.Trainer.describe( relative );

		if (!new File( reported ).isAbsolute()){
			fail( "the trainer reports " + reported + " for a path it was given as '"
					+ relative.getPath() + "'. A relative path is resolved against the process's"
					+ " working directory, which is the module directory under gradle and the shell's"
					+ " directory under a plain java -cp, so the same flag writes somewhere different"
					+ " depending on how the run was launched - and the report said the other one."
					+ " Measured: a run logged 'superintelligence/out/run/metrics.csv' and wrote"
					+ " superintelligence/superintelligence/out/run/metrics.csv." );
			return;
		}

		if (!reported.endsWith( "superintelligence" + File.separator + "out" + File.separator
				+ "run" + File.separator + "metrics.csv" )
				&& !reported.endsWith( "superintelligence/out/run/metrics.csv" )){
			fail( "the trainer reported " + reported + " for the path it was given, so the reported"
					+ " location is not the requested one" );
			return;
		}

		if (com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer.describe( null )
				.equals( reported )){
			fail( "the trainer reports the same string for a real path and for none at all" );
			return;
		}

		System.out.println( "  every path the trainer reports is canonical and absolute, so the log"
				+ " cannot name a different file from the one that was written" );
	}


	public static void checkARelativePathResolvesAgainstTheRepository(){
		File module = com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer
				.replayDirDefault();
		File root = module.getParentFile();
		if (root == null){
			fail( "the module directory " + module + " has no parent, so there is nowhere to resolve"
					+ " a relative path against and this check cannot say what the rule is" );
			return;
		}

		CliOptions options = CliOptions.parse( new String[]{
				"--save", "trainer-replays/probe.replay",
				"--out", "superintelligence/out/run",
				"--config", "superintelligence/superintelligence.properties" } );

		if ( !options.saveTo.isAbsolute() || !options.outDir.isAbsolute()
				|| !options.configFile.isAbsolute() ){
			fail( "a relative path on the command line is still relative after parsing: saveTo="
					+ options.saveTo + ", outDir=" + options.outDir + ", configFile="
					+ options.configFile + ". A relative path is resolved against the process's working"
					+ " directory, which is the module directory under gradle and the shell's directory"
					+ " under a plain java -cp, so one flag writes to two different places." );
			return;
		}

		if ( !options.saveTo.getPath().startsWith( root.getPath() ) ){
			fail( "'--save trainer-replays/probe.replay' resolved to " + options.saveTo
					+ ", which is not under the repository root " + root + ". The rule this check holds"
					+ " is that a relative path on the command line means a path from the repository"
					+ " root, whatever directory the process was started in." );
			return;
		}

		if ( !options.configFile.toFile().isFile() ){
			fail( "'--config superintelligence/superintelligence.properties' resolved to "
					+ options.configFile + ", which does not exist, so the rule above is not the one in"
					+ " force" );
			return;
		}

		System.out.println( "  a relative path on the command line resolves from the repository root ("
				+ root + "), not from whichever directory the process was started in" );
	}
}