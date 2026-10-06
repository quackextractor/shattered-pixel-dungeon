package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;

/**
 * Fades dense shaping rewards out as the agent becomes competent.
 *
 * research.md: "Phase 1 (Dense Rewards): Start with your highly specific reward system... Phase 2
 * (Sparse Rewards): As the AI gets competent, those specific micro rewards can actually cause bad
 * habits (like hoarding items instead of progressing). You can slowly fade out the micro rewards
 * and transition to your alternative plan: rewarding the AI purely for reaching deeper floors and
 * punishing it for taking too many turns."
 *
 * The fade is driven by depth reached rather than by a wall-clock schedule, because depth is the
 * thing being learned. A time-based curriculum would keep micro rewards alive for an agent that
 * has stalled and kill them for one that has learned fast.
 *
 * Advancing is deliberately not automatic: the trainer owns the schedule, and
 * {@link #observe} only reports what happened.
 */
public class Curriculum {

	/** Depth at which shaping has fully faded. Research.md's "beat the first boss" milestone. */
	private float fadeStartDepth = 3f;

	/** Depth at which shaping is gone and the reward is depth plus turn cost only. */
	private float fadeEndDepth = 10f;

	/** Floor below which shaping is never faded. */
	private float minDepth = 0f;

	private float shapingScale = 1f;
	private int deepestSeen = 1;

	public Curriculum() {}

	public Curriculum( float fadeStartDepth, float fadeEndDepth ){
		this.fadeStartDepth = fadeStartDepth;
		this.fadeEndDepth = fadeEndDepth;
	}

	public void milestones( float start, float end ){
		this.fadeStartDepth = start;
		this.fadeEndDepth = end;
	}

	/** Reports the deepest floor this agent has reached, which advances the fade. */
	public void observe( int depth ){
		if (depth > deepestSeen) deepestSeen = depth;
		shapingScale = computeScale( deepestSeen );
	}

	/** Forces the scale directly, for a trainer that wants to drive the schedule itself. */
	public void shapingScale( float scale ){
		this.shapingScale = scale;
	}

	/** Current multiplier applied to shaping terms. 1 is full dense reward, 0 is sparse only. */
	public float shapingScale(){
		return shapingScale;
	}

	public int deepestSeen(){
		return deepestSeen;
	}

	public void reset(){
		deepestSeen = 1;
		shapingScale = 1f;
	}

	/**
	 * Smoothstep from 1 to 0 between the two milestone depths.
	 *
	 * A linear ramp would change the reward distribution sharply around each milestone, which
	 * shows up as a drop in measured score that is really just the shaping being switched off.
	 * Smoothstep removes that artefact.
	 */
	private float computeScale( int depth ){
		if (depth <= fadeStartDepth) return 1f;
		if (depth >= fadeEndDepth)   return 0f;

		float t = (depth - fadeStartDepth) / (fadeEndDepth - fadeStartDepth);
		float s = t * t * (3f - 2f * t);
		return 1f - s;
	}
}