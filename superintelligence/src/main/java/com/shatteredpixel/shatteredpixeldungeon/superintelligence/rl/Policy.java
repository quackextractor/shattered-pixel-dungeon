package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.Random;


public class Policy {


	public static final int HEAD_ACTION = 0;
	public static final int HEAD_SLOT   = 1;
	public static final int HEAD_TARGET = 2;

	private Policy() {}


	public static int sample( float[] logits, float[] mask, Random rng ){
		float max = Float.NEGATIVE_INFINITY;
		boolean any = false;

		for (int i = 0; i < logits.length; i++){
			if (mask[ i ] > 0.5f ){
				any = true;
				if (logits[ i ] > max ) max = logits[ i ];
			}
		}
		if (!any) return 0;

		double total = 0;
		for (int i = 0; i < logits.length; i++){
			if (mask[ i ] > 0.5f ) total += Math.exp( logits[ i ] - max );
		}

		double target = rng.nextDouble() * total;
		double cumulative = 0;
		for (int i = 0; i < logits.length; i++){
			if (mask[ i ] <= 0.5f ) continue;
			cumulative += Math.exp( logits[ i ] - max );
			if (cumulative >= target ) return i;
		}

		for (int i = logits.length - 1; i >= 0; i--){
			if (mask[ i ] > 0.5f ) return i;
		}
		return 0;
	}


	public static void probabilities( float[] logits, float[] mask, float[] out ){
		float max = Float.NEGATIVE_INFINITY;
		for (int i = 0; i < logits.length; i++){
			if (mask[ i ] > 0.5f && logits[ i ] > max ) max = logits[ i ];
		}

		double total = 0;
		for (int i = 0; i < logits.length; i++){
			if (mask[ i ] > 0.5f ) total += Math.exp( logits[ i ] - max );
		}

		java.util.Arrays.fill( out, 0f );
		if (total <= 0) return;

		for (int i = 0; i < logits.length; i++){
			if (mask[ i ] > 0.5f ){
				out[ i ] = (float) (Math.exp( logits[ i ] - max ) / total );
			}
		}
	}


	public static float entropy( float[] probs ){
		double h = 0;
		for (float p : probs){
			if (p > 1e-12f) h -= p * Math.log( p );
		}
		return (float) h;
	}


	public static boolean accumulatePolicyGradient( float[] probabilities, float[] mask,
			int chosenIndex, float oldLogProbability, float advantage,
			float epsilon, float entropyCoeff, float[] outGradient ){

		float newProbability = Math.max( probabilities[ chosenIndex ], 1e-8f );
		float ratio = (float) Math.exp( (float) Math.log( newProbability ) - oldLogProbability );

		float low = 1f - epsilon;
		float high = 1f + epsilon;
		boolean clipBinding = ratio < low || ratio > high;
		boolean gradientStopped = (advantage > 0f && ratio > high) || (advantage < 0f && ratio < low);

		float dLogProbability = gradientStopped ? 0f : advantage * ratio;

		for (int i = 0; i < probabilities.length; i++){
			if (mask[ i ] <= 0.5f ) continue;
			float oneHot = i == chosenIndex ? 1f : 0f;
			outGradient[ i ] -= dLogProbability * (oneHot - probabilities[ i ]);
		}

		if (entropyCoeff != 0f){
			float h = entropy( probabilities );
			for (int i = 0; i < probabilities.length; i++){
				if (mask[ i ] <= 0.5f ) continue;
				float p = probabilities[ i ];
				if (p <= 1e-8f) continue;

				outGradient[ i ] -= entropyCoeff * ( -p * ( (float) Math.log( p ) + h ) );
			}
		}

		return clipBinding;
	}


	public static float logProbability( float[] probabilities, int index ){
		return (float) Math.log( Math.max( probabilities[ index ], 1e-8f ) );
	}


	public static float valueGradient( float predicted, float target, float maxResidual ){
		float delta = predicted - target;
		if (maxResidual > 0f) return Math.max( -maxResidual, Math.min( maxResidual, delta ));
		return delta;
	}


	public static float valueLoss( float predicted, float target, boolean huber ){
		float delta = predicted - target;
		if (!huber) return 0.5f * delta * delta;

		float magnitude = Math.abs( delta );
		return magnitude <= 1f ? 0.5f * delta * delta : magnitude - 0.5f;
	}


	public static void computeEpisodeAdvantages( float[] rewards, float[] values, float[] terminal,
			int count, float finalValue, float gamma, float lambda,
			float[] advantages, float[] returns ){

		float lastGae = 0f;
		for (int i = count - 1; i >= 0; i--){
			float nextValue = (i == count - 1) ? finalValue : values[ i + 1 ];
			float nonTerminal = terminal[ i ] != 0f ? 0f : 1f;
			float delta = rewards[ i ] + gamma * nextValue * nonTerminal - values[ i ];
			lastGae = delta + gamma * lambda * nonTerminal * lastGae;
			advantages[ i ] = lastGae;
			returns[ i ] = lastGae + values[ i ];
		}
	}
}
