package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;

public class Curriculum {

	private float fadeStartDepth = 3f;

	private float fadeEndDepth = 10f;

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
		shapingScale = scaleForDepth( deepestSeen );
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

	public float fadeStartDepth(){ return fadeStartDepth; }

	public float fadeEndDepth(){ return fadeEndDepth; }

	public float scaleForDepth( int depth ){
		if (depth <= fadeStartDepth) return 1f;
		if (depth >= fadeEndDepth)   return 0f;

		float t = (depth - fadeStartDepth) / (fadeEndDepth - fadeStartDepth);
		float s = t * t * (3f - 2f * t);
		return 1f - s;
	}

	public static float meanScale( int[] deepestDepths ){
		if (deepestDepths == null || deepestDepths.length == 0) return 1f;

		float total = 0f;
		for (int depth : deepestDepths) total += new Curriculum().scaleForDepth( depth );
		return total / deepestDepths.length;
	}
}