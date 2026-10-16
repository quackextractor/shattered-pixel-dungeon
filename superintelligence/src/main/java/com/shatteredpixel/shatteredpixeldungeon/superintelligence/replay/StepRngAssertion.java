package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

public final class StepRngAssertion {

	private StepRngAssertion(){}

	public static String checkRngParity( int stepIndex, long recordedDraws, long replayedDraws ){
		if (recordedDraws >= 0 && recordedDraws != replayedDraws ){
			return String.format(
					"rng baseDraws diverged at step %d: replayed %d, recorded %d (delta %+d)",
					stepIndex, replayedDraws, recordedDraws, (replayedDraws - recordedDraws) );
		}
		return null;
	}

	public static String checkFingerprintParity( int stepIndex, long recordedDraws,
			long recordedFingerprint, long replayedFingerprint ){

		if (recordedDraws < 0 ) return null;
		if (recordedFingerprint == replayedFingerprint ) return null;

		return String.format(
				"rng baseFingerprint diverged at step %d over %d draw(s): replayed %016x, recorded"
						+ " %016x. The same number of values came out of the generator and they were not"
						+ " the same values, so the streams diverged and re-converged on the count.",
				stepIndex, recordedDraws, replayedFingerprint, recordedFingerprint );
	}
}