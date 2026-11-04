package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.shared.ProcessQos;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GateReport.fail;

public class QosCheck {

	private static final int CHECKS = 2;

	public static void main( String[] args ){
		if (!ProcessQos.supported()){
			System.out.println( "[WARN]   process qos: not Windows, " + CHECKS + " checks skipped" );
			System.exit( 0 );
		}
		if (ProcessQos.exempt()) fail( "a fresh process already reads as exempt, so the next case would prove nothing" );
		else System.out.println( "  a fresh process is left to the throttling heuristics" );

		if (!ProcessQos.exemptFromThrottling() || !ProcessQos.exempt()){
			fail( "the opt-out did not take: Windows does not report this process exempt from execution-speed throttling" );
		} else {
			System.out.println( "  after the opt-out Windows reports the process exempt from execution-speed throttling" );
		}
		System.exit( GateReport.report( "process qos", CHECKS ));
	}
}
