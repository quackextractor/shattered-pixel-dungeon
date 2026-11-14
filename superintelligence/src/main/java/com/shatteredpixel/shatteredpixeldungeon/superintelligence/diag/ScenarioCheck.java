package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.Scenario;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ScenarioCatalog;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ScenarioReport;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ScenarioRunner;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ScenarioSetup;
import com.watabou.utils.Random;
import com.watabou.utils.RandomTrace;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ScenarioCheck {

	private static volatile String inFlight = "self-tests";

	public static void main( String[] args ){
		File out = new File( option( args, "--out", "out/scenarios" ));
		String only = option( args, "--only", null );
		long budget = Long.parseLong( option( args, "--budget-seconds", "60" ));

		HeadlessServices.install( Scratch.dir( "scenariocheck" ));
		HeadlessServices.disableSaving( true );
		watchdog( budget );
		checkLoadingTheCatalogDrawsNothing();

		List< Scenario > chosen = new ArrayList<>();
		for (Scenario scenario : ScenarioCatalog.all()){
			if (only == null || only.equals( scenario.id )) chosen.add( scenario );
		}
		if (chosen.isEmpty()){
			System.out.println( "[ERROR]  scenarios: no scenario is called " + only );
			System.exit( 1 );
		}
		if (only == null) ScenarioReport.clean( out );

		checkASetupLeavesTheGameplayStreamAlone();

		ScenarioRunner runner = new ScenarioRunner( new EnvConfig(), out );
		List< ScenarioRunner.Result > results = new ArrayList<>();
		for (Scenario scenario : chosen){
			inFlight = scenario.id;
			results.add( runner.run( scenario ));
			if (scenario.dirtyEnd) ScenarioReport.leakCheck( results.get( results.size() - 1 ), results );
		}
		inFlight = "re-verification";

		checkABlankScenarioDoesNotVerify( results );
		ScenarioReport.reverify( results );
		ScenarioReport.write( out, results );

		System.exit( ScenarioReport.print( results, GateReport.failures() ));
	}

	private static void checkLoadingTheCatalogDrawsNothing(){
		boolean wasOn = RandomTrace.enabled();
		RandomTrace.reset();
		RandomTrace.enable();
		int scenarios = ScenarioCatalog.all().size();
		long draws = RandomTrace.draws();
		if (!wasOn) RandomTrace.disable();
		if (draws != 0){
			GateReport.fail( "self-test: loading the catalogue of " + scenarios + " scenarios drew " + draws + " values. The"
					+ " viewer loads it on its first step, after the floor is built, so a game object made at load time"
					+ " moves the viewer's stream and not the trainer's" );
		} else {
			System.out.println( "  loading the catalogue of " + scenarios + " scenarios draws nothing" );
		}
	}

	private static void checkASetupLeavesTheGameplayStreamAlone(){
		SPDEnv env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );
		env.reset( "SCN-A", HeroClass.WARRIOR );
		Scenario drawing = new Scenario( "selftest.rng", "self-test", HeroClass.WARRIOR,
				( world, kit ) -> {
					for (int i = 0; i < 16; i++) Random.Int( 1000 );
				}, d -> {} );
		boolean wasOn = RandomTrace.enabled();
		RandomTrace.reset();
		RandomTrace.enable();
		ScenarioSetup.hookFor( drawing ).build();
		long base = RandomTrace.baseDraws(), all = RandomTrace.draws();
		if (!wasOn) RandomTrace.disable();
		if (all < 16){
			GateReport.fail( "self-test: a setup that draws 16 values recorded " + all + " draws, so the base-draw"
					+ " check below measured nothing" );
		} else if (base != 0){
			GateReport.fail( "self-test: a setup moved the gameplay stream by " + base + " base draws. Setup must run"
					+ " inside a pushed generator, or every step after it reads a different stream from the viewer's" );
		} else {
			System.out.println( "  a setup's " + all + " draws stay off the gameplay stream" );
		}
	}

	private static void checkABlankScenarioDoesNotVerify( List< ScenarioRunner.Result > results ){
		for (ScenarioRunner.Result result : results){
			if (!"melee".equals( result.scenario.id ) || result.replay == null) continue;
			Replay replay = result.replay;
			replay.scenario = "";
			ReplayIO.Verification verified = ScenarioRunner.verify( replay );
			replay.scenario = "melee";
			if (verified.passed()){
				GateReport.fail( "self-test: melee verified with its scenario= blanked, so verify never rebuilt"
						+ " the world and every scenario passes on the bare floor" );
			} else {
				System.out.println( "  melee with scenario= blanked does not verify: " + ScenarioRunner.describe( verified ));
			}
		}
	}

	private static void watchdog( long seconds ){
		Thread watchdog = new Thread( () -> {
			try {
				Thread.sleep( seconds * 1000L );
			} catch (InterruptedException e){
				return;
			}
			System.out.println( "[ERROR]  scenarios: still running after " + seconds + "s, in " + inFlight );
			System.out.flush();
			Runtime.getRuntime().halt( 1 );
		}, "scenario-watchdog" );
		watchdog.setDaemon( true );
		watchdog.start();
	}

	private static String option( String[] args, String name, String fallback ){
		for (int i = 0; i + 1 < args.length; i++) if (args[ i ].equals( name )) return args[ i + 1 ];
		return fallback;
	}
}
