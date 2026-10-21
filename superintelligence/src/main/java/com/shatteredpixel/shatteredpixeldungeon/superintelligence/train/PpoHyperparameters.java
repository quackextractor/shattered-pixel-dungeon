package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

public class PpoHyperparameters {

	public float gamma = 0.99f;

	public float lambda = 0.95f;

	public float clipEpsilon = 0.2f;

	public boolean clipEpsilonMeasuredAgainstRatio = true;

	public float entropyCoeff = 0.01f;

	public float teacherWeight = 1f;

	public float teacherHalfLife = 10f;

	public float learningRate = 3e-4f;

	public int minibatchSize = 8;

	public int epochs = 4;

	public int updateThreads = 1;

	public double sampleRate = 0.25;

	public int maxSampledPerEpisode = 2048;

	public int maxSamplesPerGeneration = 8192;

	public long stallSeconds = 180;

	public int chunkLength = 16;

	public int tailSteps = 20;

	public boolean normaliseValues = true;

	public float valueNormMomentum = 0.01f;

	public float valueLossCoefficient = 0.5f;

	public float valueGradientClip = 10f;

	public boolean huberValues = true;

	public float maxGradNorm = 0.5f;

	public float adamBeta1 = 0.9f;

	public float adamBeta2 = 0.999f;

	public float adamEps = 1e-8f;

	public double randomSeedRate = 0.10;

	public int checkpointEvery = 25;

	public int recordEvery = 1;

	public int pipeBufferBytes = 1024 * 1024;

	public int pushChunkBytes = 32 * 1024;


	public String workerHeapMin = "256m";

	public String workerHeapMax = "1536m";

	public int workerGcPauseMs = 200;

	public void validate(){
		require( gamma > 0f && gamma <= 1f, "rl.gamma must be in (0, 1], was " + gamma );
		require( lambda >= 0f && lambda <= 1f, "rl.gae_lambda must be in [0, 1], was " + lambda );
		require( clipEpsilon > 0f, "rl.clip_epsilon must be positive, was " + clipEpsilon );
		require( learningRate > 0f, "rl.learning_rate must be positive, was " + learningRate );
		require( entropyCoeff >= 0f, "rl.entropy_coefficient must be non-negative, was " + entropyCoeff );
		require( teacherWeight >= 0f, "rl.teacher_weight must be non-negative, was " + teacherWeight );
		require( teacherHalfLife > 0f, "rl.teacher_half_life must be positive, was " + teacherHalfLife );
		require( minibatchSize >= 1, "rl.minibatch_size must be at least 1, was " + minibatchSize );
		require( epochs >= 1, "rl.epochs must be at least 1, was " + epochs );
		require( updateThreads >= 0, "rl.update_threads must be zero or more, was " + updateThreads );
		require( sampleRate >= 0.0 && sampleRate <= 1.0,
				"rl.sample_rate must be in [0, 1], was " + sampleRate );
		require( maxSampledPerEpisode >= 1,
				"rl.max_sampled_per_episode must be at least 1, was " + maxSampledPerEpisode );
		require( maxSamplesPerGeneration >= 1,
				"rl.max_samples_per_generation must be at least 1, was " + maxSamplesPerGeneration );
		require( stallSeconds > 0, "rl.stall_seconds must be positive, was " + stallSeconds );
		require( chunkLength >= 1, "rl.chunk_length must be at least 1, was " + chunkLength );
		require( tailSteps >= 0, "rl.tail_steps must be zero or more, was " + tailSteps );
		require( valueNormMomentum >= 0f && valueNormMomentum <= 1f,
				"rl.value_norm_momentum must be in [0, 1], was " + valueNormMomentum );
		require( valueLossCoefficient >= 0f,
				"rl.value_loss_coefficient must be non-negative, was " + valueLossCoefficient );
		require( valueGradientClip > 0f,
				"rl.value_gradient_clip must be positive, was " + valueGradientClip );
		require( maxGradNorm > 0f, "rl.max_grad_norm must be positive, was " + maxGradNorm );
		require( adamBeta1 >= 0f && adamBeta1 < 1f,
				"rl.adam_beta1 must be in [0, 1), was " + adamBeta1 );
		require( adamBeta2 >= 0f && adamBeta2 < 1f,
				"rl.adam_beta2 must be in [0, 1), was " + adamBeta2 );
		require( adamEps > 0f, "rl.adam_eps must be positive, was " + adamEps );
		require( randomSeedRate >= 0.0 && randomSeedRate <= 1.0,
				"train.random_seed_rate must be in [0, 1], was " + randomSeedRate );
		require( checkpointEvery >= 1, "train.checkpoint_every must be at least 1, was " + checkpointEvery );
		require( recordEvery >= 1, "train.record_every must be at least 1, was " + recordEvery );
		require( pipeBufferBytes > 0, "ipc.pipe_buffer_bytes must be positive, was " + pipeBufferBytes );
		require( pushChunkBytes > 0, "ipc.push_chunk_bytes must be positive, was " + pushChunkBytes );
		require( workerGcPauseMs > 0, "worker.gc_pause_ms must be positive, was " + workerGcPauseMs );
		require( !workerHeapMin.isBlank(), "worker.heap_min must not be blank" );
		require( !workerHeapMax.isBlank(), "worker.heap_max must not be blank" );
	}

	private static void require( boolean condition, String message ){
		if (!condition) throw new IllegalArgumentException( message );
	}

	public PpoHyperparameters copy(){
		PpoHyperparameters p = new PpoHyperparameters();
		p.gamma = gamma;
		p.lambda = lambda;
		p.clipEpsilon = clipEpsilon;
		p.clipEpsilonMeasuredAgainstRatio = clipEpsilonMeasuredAgainstRatio;
		p.entropyCoeff = entropyCoeff;
		p.teacherWeight = teacherWeight;
		p.teacherHalfLife = teacherHalfLife;
		p.learningRate = learningRate;
		p.minibatchSize = minibatchSize;
		p.epochs = epochs;
		p.updateThreads = updateThreads;
		p.sampleRate = sampleRate;
		p.maxSampledPerEpisode = maxSampledPerEpisode;
		p.maxSamplesPerGeneration = maxSamplesPerGeneration;
		p.stallSeconds = stallSeconds;
		p.chunkLength = chunkLength;
		p.tailSteps = tailSteps;
		p.normaliseValues = normaliseValues;
		p.valueNormMomentum = valueNormMomentum;
		p.valueLossCoefficient = valueLossCoefficient;
		p.valueGradientClip = valueGradientClip;
		p.huberValues = huberValues;
		p.maxGradNorm = maxGradNorm;
		p.adamBeta1 = adamBeta1;
		p.adamBeta2 = adamBeta2;
		p.adamEps = adamEps;
		p.randomSeedRate = randomSeedRate;
		p.checkpointEvery = checkpointEvery;
		p.recordEvery = recordEvery;
		p.pipeBufferBytes = pipeBufferBytes;
		p.pushChunkBytes = pushChunkBytes;
		p.workerHeapMin = workerHeapMin;
		p.workerHeapMax = workerHeapMax;
		p.workerGcPauseMs = workerGcPauseMs;
		return p;
	}
}