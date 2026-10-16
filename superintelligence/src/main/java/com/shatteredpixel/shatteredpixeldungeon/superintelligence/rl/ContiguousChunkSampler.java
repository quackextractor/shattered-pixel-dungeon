package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.Random;

public final class ContiguousChunkSampler {

	private int remainingInBlock;

	public void reset(){
		remainingInBlock = 0;
	}

	public int remainingInBlock(){
		return remainingInBlock;
	}

	public boolean shouldSample( int stepIndex, int totalSteps, int tailSteps,
			float sampleRate, int chunkLength,
			int retainedCount, int maxSampledPerEpisode,
			Random rng ){

		if (retainedCount >= maxSampledPerEpisode) return false;

		if (totalSteps > 0 && stepIndex >= totalSteps - tailSteps) return true;

		if (sampleRate <= 0f) return false;
		if (sampleRate >= 1f) return true;

		if (remainingInBlock > 0){
			remainingInBlock--;
			return true;
		}

		int effectiveChunk = Math.max( 1, chunkLength );
		float blockStartProb = Math.min( 1f, sampleRate / effectiveChunk );

		if (rng.nextFloat() < blockStartProb){
			remainingInBlock = effectiveChunk - 1;
			return true;
		}
		return false;
	}
}