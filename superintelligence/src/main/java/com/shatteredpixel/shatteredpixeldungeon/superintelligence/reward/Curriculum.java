package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;

public class Curriculum {

	private float fadeStartDepth = 3f;

	private float fadeEndDepth = 10f;

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

	public void observe( int depth ){
		if (depth > deepestSeen) deepestSeen = depth;
		shapingScale = computeScale( deepestSeen );
	}

	public void shapingScale( float scale ){
		this.shapingScale = scale;
	}

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

	private float computeScale( int depth ){
		if (depth <= fadeStartDepth) return 1f;
		if (depth >= fadeEndDepth)   return 0f;

		float t = (depth - fadeStartDepth) / (fadeEndDepth - fadeStartDepth);
		float s = t * t * (3f - 2f * t);
		return 1f - s;
	}
}