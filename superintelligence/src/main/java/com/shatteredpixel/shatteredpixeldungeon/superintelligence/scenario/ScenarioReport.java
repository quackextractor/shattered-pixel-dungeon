package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class ScenarioReport {

	private ScenarioReport(){}

	public static void clean( File out ){
		File[] old = out.listFiles( ( dir, name ) -> name.endsWith( ".replay" ));
		if (old != null) for (File file : old) file.delete();
	}

	public static void reverify( List< ScenarioRunner.Result > results ){
		for (ScenarioRunner.Result result : results){
			if (result.replay == null || result.failure.contains( "verify: " )) continue;
			ReplayIO.Verification again = ScenarioRunner.verify( result.replay );
			if (!again.passed()){
				ScenarioRunner.fail( result, "verified alone but not after the whole catalogue: "
						+ ScenarioRunner.describe( again ));
			}
		}
	}

	public static void leakCheck( ScenarioRunner.Result leak, List< ScenarioRunner.Result > earlier ){
		for (ScenarioRunner.Result result : earlier){
			if (result == leak || result.replay == null || result.failure.contains( "verify: " )) continue;
			ReplayIO.Verification again = ScenarioRunner.verify( result.replay );
			if (!again.passed()){
				ScenarioRunner.fail( leak, "after it, " + result.scenario.id + " no longer verifies: "
						+ ScenarioRunner.describe( again ));
				return;
			}
		}
	}

	public static void write( File out, List< ScenarioRunner.Result > results ){
		List< String > lines = new ArrayList<>();
		lines.add( "id,mechanic,status,reach,seed,steps,ms,failure" );
		for (ScenarioRunner.Result r : results){
			lines.add( String.join( ",", r.scenario.id, csv( r.scenario.mechanic ), r.status, csv( r.reach ), r.seed,
					Integer.toString( r.steps ), Long.toString( r.ms ), csv( r.failure )));
		}
		try {
			out.mkdirs();
			Files.write( new File( out, "report.csv" ).toPath(), lines, StandardCharsets.UTF_8 );
		} catch (IOException e){
			System.out.println( "[WARN]   could not write report.csv: " + e.getMessage() );
		}
	}

	public static int print( List< ScenarioRunner.Result > results, List< String > selfTests ){
		int failed = 0;
		long ms = 0;
		for (ScenarioRunner.Result r : results){
			ms += r.ms;
			boolean ok = "OK".equals( r.status ) || "GAP".equals( r.status ) || "KNOWN".equals( r.status );
			if (!ok) failed++;
			System.out.println( (ok ? "  [OK]    " : "  [ERROR] ") + r.scenario.id + "  " + r.status
					+ "  " + r.steps + " steps, " + r.ms + " ms" + ("POLICY".equals( r.reach ) ? "" : "  " + r.reach )
					+ (r.failure.isEmpty() ? "" : "  " + r.failure ));
		}
		for (String f : selfTests) System.out.println( "  [ERROR] " + f );
		if (failed == 0 && selfTests.isEmpty()){
			System.out.println( "[OK]     scenarios: " + results.size() + " recorded, verified twice and written, "
					+ count( results, "GAP" ) + " expected reach gaps, " + count( results, "KNOWN" ) + " known failures (" + ms + " ms)" );
			return 0;
		}
		System.out.println( "[ERROR]  scenarios: " + failed + " of " + results.size() + " failed, "
				+ selfTests.size() + " self-test(s) failed" );
		return 1;
	}

	private static long count( List< ScenarioRunner.Result > results, String status ){
		return results.stream().filter( r -> status.equals( r.status )).count();
	}

	private static String csv( String text ){
		return "\"" + text.replace( "\"", "'" ).replace( '\n', ' ' ) + "\"";
	}
}
