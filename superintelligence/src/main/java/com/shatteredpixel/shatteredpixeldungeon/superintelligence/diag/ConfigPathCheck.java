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

		for (String setting : new String[]{ "'worker.heap_min'", "'worker.heap_max'",
				"'worker.gc_pause_ms'", "'SPD_'", "file('.env')", "file('superintelligence.properties')" }){
			if (script.contains( setting )) continue;
			fail( "the build script does not read " + setting + ", so the heap the trainer JVM is"
					+ " launched with does not follow worker.heap_min, worker.heap_max and"
					+ " worker.gc_pause_ms through process environment, .env and the properties file." );
			return;
		}

		for (String literal : new String[]{ "-Xmx1536m", "-Xms256m", "MaxGCPauseMillis=200" }){
			if (!script.contains( literal )) continue;
			fail( "the build script still carries the literal " + literal + " alongside the"
					+ " SPD_WORKER_* names, so the literal wins whenever they disagree" );
			return;
		}

		System.out.println( "  the trainer JVM's heap and GC pause come from the worker.* settings via"
				+ " SPD_ environment, .env and the properties file, not from literals" );
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

		if ( !resolvesTo( options.saveTo, root, "trainer-replays/probe.replay" ) ){
			fail( "'--save trainer-replays/probe.replay' resolved to " + options.saveTo
					+ ", which is not that path under the repository root " + root + ". The rule this check holds"
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


	public static void checkTheTrainerResolvesItsOwnPathsToo(){
		File root = com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer
				.replayDirDefault().getParentFile();

		com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TrainOptions options;
		try {
			options = com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TrainOptions
					.parse( new String[]{
							"--out", "superintelligence/out/evidence/probe",
							"--replay-dir", "superintelligence/trainer-replays/probe",
							"--metrics", "superintelligence/out/evidence/probe/metrics.csv",
							"--save", "superintelligence/out/evidence/probe/weights.bin",
							"--resume", "superintelligence/out/evidence/probe/weights.bin" } );
		} catch (IOException e){
			fail( "the trainer's own option parser threw on five well-formed relative paths ("
					+ e.getMessage() + "), so this case cannot say what it resolves them to" );
			return;
		}

		java.util.List< String > wrong = new java.util.ArrayList<>();
		if ( !resolvesTo( options.workDir, root, "superintelligence/out/evidence/probe" ))
			wrong.add( "--out -> " + options.workDir );
		if ( !resolvesTo( options.replayDir, root, "superintelligence/trainer-replays/probe" ))
			wrong.add( "--replay-dir -> " + options.replayDir );
		if ( !resolvesTo( options.metricsCsv, root, "superintelligence/out/evidence/probe/metrics.csv" ))
			wrong.add( "--metrics -> " + options.metricsCsv );
		if ( !resolvesTo( options.save, root, "superintelligence/out/evidence/probe/weights.bin" ))
			wrong.add( "--save -> " + options.save );
		if ( !resolvesTo( options.resume, root, "superintelligence/out/evidence/probe/weights.bin" ))
			wrong.add( "--resume -> " + options.resume );

		if (!wrong.isEmpty()){
			fail( "the trainer's own option parser left these paths relative, so they resolve against "
					+ "the process working directory - the module directory under gradle: "
					+ String.join( ", ", wrong ) + ". The rule is one rule and the rollout command's "
					+ "parser already follows it (checkARelativePathResolvesAgainstTheRepository); the "
					+ "trainer's did not, and a documented invocation naming "
					+ "'superintelligence/out/...' wrote it to 'superintelligence/superintelligence/out/...'. "
					+ " Measured on a two-generation run." );
			return;
		}

		System.out.println( "  all five of the trainer's path flags resolve from the repository root ("
				+ root + "), not only the rollout command's" );
	}


	private static boolean resolvesTo( File path, File root, String relative ){
		if (path == null || !path.isAbsolute() || root == null) return false;
		return path.toPath().normalize().equals( new File( root, relative ).toPath().normalize() );
	}
}