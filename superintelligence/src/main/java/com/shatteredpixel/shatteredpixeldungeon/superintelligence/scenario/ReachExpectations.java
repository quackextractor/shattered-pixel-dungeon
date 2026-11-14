package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ReachExpectations {

	private ReachExpectations(){}

	private static final Map< String, String > EXPECTED = new HashMap<>();

	private static final Map< String, String > KNOWN_FAILURES = new HashMap<>();

	static {
		EXPECTED.put( "drop-pickup", "G1" );
		for (String mode : new String[]{ "SLOT", "INVENTORY", "TALENT", "ALCHEMY", "MENU" }){
			EXPECTED.put( "cancel." + mode, "G2" );
		}
		EXPECTED.put( "picker-cancel", "G2" );
		EXPECTED.put( "aim-empty", "G3" );
		EXPECTED.put( "aim-no-enemy", "G3" );
		EXPECTED.put( "interact-direction", "G4" );
		EXPECTED.put( "unequip", "G5" );
		EXPECTED.put( "artifact.Cloak", "G5" );
		EXPECTED.put( "artifact.EtherealChains", "G6" );
		EXPECTED.put( "shop-buy", "G7" );
		EXPECTED.put( "throw-only", "G8" );
		EXPECTED.put( "potion-unknown-throw", "G8" );
		for (String avoided : new String[]{ "well.Health", "well.Awareness", "chasm", "trap.WornDart", "trap.PoisonDart",
				"trap.Burning", "trap.Toxic", "trap.Teleportation", "trap.Summoning", "trap.Alarm", "trap.Gripping",
				"trap.Disintegration", "trap.Rockfall", "trap.Pitfall" }){
			EXPECTED.put( avoided, "G9" );
		}
		KNOWN_FAILURES.put( "aim-no-enemy", "issues.md 44, an adjacent aim index throws the aimed item itself" );
	}

	public static String knownFailure( String id ){
		return KNOWN_FAILURES.get( id );
	}

	public static String expected( String id ){
		return EXPECTED.get( id );
	}

	static void judge( ScenarioRunner.Result result, List< String > gaps, boolean assertsOnly ){
		String expected = expected( result.scenario.id );
		String known = knownFailure( result.scenario.id );
		result.reach = gaps.isEmpty() ? "POLICY" : "GAP(" + (expected == null ? "?" : expected) + ") " + gaps.get( 0 );
		if (known != null && assertsOnly){
			result.status = "KNOWN";
			result.failure = known + ": " + result.failure;
			return;
		}
		if (known != null && "OK".equals( result.status )){
			ScenarioRunner.fail( result, "ReachExpectations lists a known failure (" + known + ") and the scenario"
					+ " passed: the fault is fixed, so remove it from the list" );
			return;
		}
		if (!"OK".equals( result.status )) return;
		if (!gaps.isEmpty() && expected != null){
			result.status = "GAP";
		} else if (!gaps.isEmpty()){
			ScenarioRunner.fail( result, "a step the policy cannot emit, which ReachExpectations does not list: "
					+ gaps.get( 0 ));
		} else if (expected != null){
			ScenarioRunner.fail( result, "ReachExpectations lists GAP(" + expected + ") and every step was one the"
					+ " policy can emit: the gap is closed, so remove it from the list" );
		}
	}
}
