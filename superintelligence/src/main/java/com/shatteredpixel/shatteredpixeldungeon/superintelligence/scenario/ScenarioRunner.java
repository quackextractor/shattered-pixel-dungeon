package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;

import java.io.File;
import java.io.IOException;

public final class ScenarioRunner {

	public static final int TAIL_STEPS = 6;

	public static final class Result {
		public final Scenario scenario;
		public String status = "NOFIXTURE";
		public String failure = "";
		public String seed = "";
		public String reach = "POLICY";
		public int steps;
		public long ms;
		public Replay replay;

		Result( Scenario scenario ){
			this.scenario = scenario;
		}
	}

	private final EnvConfig config;
	private final File out;

	public ScenarioRunner( EnvConfig config, File out ){
		this.config = config;
		this.out = out;
	}

	public Result run( Scenario scenario ){
		long started = System.nanoTime();
		Result result = new Result( scenario );
		StringBuilder missing = new StringBuilder();
		for (String seed : scenario.seeds){
			if (attempt( scenario, seed, result, missing )) break;
		}
		if ("NOFIXTURE".equals( result.status )) result.failure = "no seed gave a fixture:" + missing;
		result.ms = (System.nanoTime() - started) / 1_000_000L;
		return result;
	}

	private boolean attempt( Scenario scenario, String seed, Result result, StringBuilder missing ){
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		ReplayRecorder recorder = new ReplayRecorder();
		Driver driver = new Driver( env, recorder );
		String stopped = null;
		try {
			env.reset( seed, scenario.hero, ScenarioSetup.hookFor( scenario ));
			recorder.begin( env.seedText(), scenario.hero.name(), 0, config.turnLimitPerFloor, config );
			scenario.script.run( driver );
			if (!scenario.dirtyEnd) tail( driver );
		} catch (NoFixture e){
			missing.append( ' ' ).append( seed ).append( '=' ).append( e.getMessage() );
			return false;
		} catch (Driver.Stop e){
			stopped = e.getMessage();
		} catch (RuntimeException e){
			stopped = "threw " + e + " at " + topFrame( e );
		}

		recorder.end( env.ledger().total(), Dungeon.depth, env.turnsTotal(), 0,
				env.running() ? "" : env.endReason().name() );
		Replay replay = recorder.replay();
		replay.scenario = scenario.id;
		replay.group = "scenario";
		result.replay = replay;
		result.seed = env.seedText();
		result.steps = replay.length();

		if (stopped != null) fail( result, stopped );
		else if (!driver.failures().isEmpty()) fail( result, String.join( "; ", driver.failures() ));
		else result.status = "OK";
		ReachExpectations.judge( result, driver.gaps(), stopped == null && !driver.failures().isEmpty() );

		ReplayIO.Verification verified = verify( replay );
		if (!verified.passed()){
			fail( result, "verify: " + describe( verified ));
			return true;
		}
		write( result );
		return true;
	}

	private void tail( Driver driver ){
		for (int i = 0; i < TAIL_STEPS && driver.env().running(); i++){
			driver.act( driver.env().mode() == EnvMode.WORLD ? Action.WAIT : Action.CANCEL );
		}
	}

	static void fail( Result result, String why ){
		result.status = "FAIL";
		result.failure = result.failure.isEmpty() ? why : result.failure + "; " + why;
	}

	private void write( Result result ){
		if (out == null) return;
		try {
			ReplayIO.write( result.replay, new File( out, result.scenario.id + ".replay" ));
		} catch (IOException e){
			fail( result, "cannot write the recording: " + e.getMessage() );
		}
	}

	public static ReplayIO.Verification verify( Replay replay ){
		try {
			return ReplayIO.verify( replay, new SPDEnv( ReplayIO.configFor( replay ), HeadlessGame.install() ));
		} catch (RuntimeException e){
			ReplayIO.Verification threw = new ReplayIO.Verification();
			threw.diverged = true;
			threw.divergence = "threw " + e + " at " + topFrame( e );
			return threw;
		}
	}

	public static String describe( ReplayIO.Verification verified ){
		return verified.diverged ? "diverged at step " + verified.divergedAt + " - " + verified.divergence
				: "unwatchable at step " + verified.unwatchableAt + " - " + verified.unwatchable;
	}

	static String topFrame( Throwable t ){
		for (StackTraceElement frame : t.getStackTrace()){
			if (frame.getClassName().startsWith( "com.shatteredpixel." )){
				return frame.getClassName().substring( frame.getClassName().lastIndexOf( '.' ) + 1 )
						+ "." + frame.getMethodName() + ":" + frame.getLineNumber();
			}
		}
		return "(no frame in this project)";
	}
}
