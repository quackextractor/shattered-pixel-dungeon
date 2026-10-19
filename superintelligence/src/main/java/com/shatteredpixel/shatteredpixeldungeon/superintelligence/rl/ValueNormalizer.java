package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.List;

public class ValueNormalizer {

	public float momentum = 0.01f;

	private static final float MIN_MOMENTUM = 1e-3f;

	private float mean;
	private float std = 1f;
	private boolean ready;

	public float mean(){ return mean; }
	public float std(){ return std; }

	public boolean ready(){ return ready; }

	public void update( List<Transition> batch ){
		if (batch.isEmpty()) return;

		double sum = 0;
		for (Transition t : batch) sum += t.returnValue;
		double batchMean = sum / batch.size();

		double sq = 0;
		for (Transition t : batch){
			double d = t.returnValue - batchMean;
			sq += d * d;
		}
		double batchStd = Math.max( 1e-4, Math.sqrt( sq / batch.size() ) );

		if (!ready || momentum < MIN_MOMENTUM){
			mean = (float) batchMean;
			std = (float) batchStd;
			ready = true;
			return;
		}

		mean = (1f - momentum) * mean + momentum * (float) batchMean;
		std = (1f - momentum) * std + momentum * (float) batchStd;
		if (std < 1e-3f) std = 1e-3f;
	}

	public float normalize( float returnValue ){
		return (returnValue - mean) / std;
	}

	public void restore( float mean, float std, boolean ready ){
		this.mean = mean;
		this.std = std;
		this.ready = ready;
	}

	public void disable(){
		ready = false;
		mean = 0f;
		std = 1f;
	}
}