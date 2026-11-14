package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.MechanicLedger;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.Scenario;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ScenarioReport;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ScenarioRunner;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.WorldSweep;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class WorldSweepCheck {

	public static void main( String[] args ){
		File out = new File( option( args, "--out", "out/sweep" ));
		long budgetMs = Long.parseLong( option( args, "--budget-seconds", "45" )) * 1000L;

		HeadlessServices.install( Scratch.dir( "worldsweep" ));
		HeadlessServices.disableSaving( true );
		ScenarioReport.clean( out );

		MechanicLedger ledger = new MechanicLedger();
		List< Scenario > sweep = WorldSweep.all( ledger );
		ScenarioRunner runner = new ScenarioRunner( new EnvConfig(), out );
		List< ScenarioRunner.Result > results = new ArrayList<>();
		long started = System.currentTimeMillis();
		for (Scenario scenario : sweep){
			if (System.currentTimeMillis() - started > budgetMs) break;
			results.add( runner.run( scenario ));
		}

		ScenarioReport.reverify( results );
		ScenarioReport.write( out, results );
		ledger.write( out );
		if (results.size() < sweep.size()){
			System.out.println( "[WARN]   worldsweep: the " + (budgetMs / 1000) + " s budget ran " + results.size() + " of "
					+ sweep.size() + " worlds" );
		}
		System.out.println( "  coverage.csv: " + ledger.never() + " actions or modes never reached" );
		System.exit( ScenarioReport.print( results, GateReport.failures() ));
	}

	private static String option( String[] args, String name, String fallback ){
		for (int i = 0; i + 1 < args.length; i++) if (args[ i ].equals( name )) return args[ i + 1 ];
		return fallback;
	}
}
