package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

/**
 * The learning hyperparameters, held in one value rather than spread across {@link PPO}, {@link
 * com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer} and a command line.
 *
 * <p><b>Why this exists.</b> Every one of these numbers was a bare field with a default written into
 * the source of whichever class happened to need it: {@code learningRate} appeared on both
 * {@code Network} and {@code PPO}, {@code epochs} and {@code minibatchSize} on {@code PPO} and again on
 * {@code TrainOptions}, and the stall timeout was a literal in {@code WorkerPool} with a different
 * default in {@code TrainOptions}. That is three problems at once. A hyperparameter sweep needs to
 * name its values in one place to be reproducible. Two of the duplicates are load-bearing - Adam's
 * learning rate must agree between the network that steps and the one that reports it, and the
 * per-episode sample cap must agree between the trainer that requests it and the worker that applies
 * it, or the two halves of a generation describe different work. And a default that appears twice
 * drifts the first time either copy is edited, silently, because nothing compares them.
 *
 * <p><b>It holds, it does not apply.</b> {@link PPO} keeps its public fields and {@link Trainer} keeps
 * writing to them. This is a source of values and a destination for the binder, not a replacement
 * mechanism: the update path is the part of this framework that replay verification depends on, and
 * routing it through a new object would be a refactor of the learner for no behavioural gain.
 *
 * <p><b>Not every default lives here.</b> Values that are derived from the architecture rather than
 * chosen - the observation size, the head widths - belong to {@link
 * com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig} or to the network that
 * owns them, and putting a copy here would create exactly the drift this class exists to prevent. The
 * line is: if a human picked it, it belongs here; if it follows from something else, it does not.
 */
public class PpoHyperparameters {

	/** Discount factor. Encodes how far ahead a reward counts. */
	public float gamma = 0.99f;

	/** GAE trace decay. The bias/variance dial of the advantage estimator. */
	public float lambda = 0.95f;

	/**
	 * PPO's surrogate clip range.
	 *
	 * <p>Bounds how far one update may move the policy on a single sample, which is the whole mechanism
	 * that stops a good outlier from wrecking the policy. {@link #clipEpsilon} is measured against the
	 * ratio, not against an advantage - see {@link #clipEpsilonMeasuredAgainstRatio}.
	 */
	public float clipEpsilon = 0.2f;

	/**
	 * How the clip figure is reported, stated because the two are indistinguishable in the output.
	 *
	 * <p>The reported fraction is samples whose <em>ratio</em> fell outside the clip range. It was
	 * counting samples whose <em>|advantage|</em> exceeded the clip value on a normalised advantage,
	 * which is {@code P(|N(0,1)| > 0.2)} = 0.8415 for every batch ever seen - measured 0.81 to 0.90
	 * across real generations, which is that constant.
	 */
	public boolean clipEpsilonMeasuredAgainstRatio = true;

	/** Entropy bonus coefficient. The pressure keeping exploration alive. */
	public float entropyCoeff = 0.01f;

	/** Adam step size. Must match the value {@code Network} steps with. */
	public float learningRate = 3e-4f;

	/** Samples per minibatch, which sets the gradient reduction size in a parallel update. */
	public int minibatchSize = 32;

	/**
	 * Passes over the buffer per update.
	 *
	 * <p>Two, not four. The update's dominant cost is forward and backward, so epochs scale it
	 * linearly, and four would be 300 Adam steps over a 2,400-sample batch - far more optimiser movement
	 * than a batch that size supports.
	 */
	public int epochs = 2;

	/** Threads for the update. Zero or one is the serial path, exactly. */
	public int updateThreads = 1;

	/**
	 * Fraction of an episode's transitions whose observation reaches the trainer.
	 *
	 * <p>A knob because the update cost is linear in it. Clamped to [0,1] at the point of use, and 0 is
	 * a real setting rather than "off": the collector always retains an episode's last
	 * {@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector#TAIL_STEPS},
	 * so that is how a zero is expressed.
	 */
	public double sampleRate = 0.05;

	/**
	 * Ceiling on one episode's retained observations, ~98 MB at 2048.
	 *
	 * <p>This bounds a worker. {@code rolloutCap} used to bound the trainer's buffer instead, which was
	 * the wrong end: an episode of 40,000 turns at ~49 KB a step is ~1.9 GB against a 1536m worker heap,
	 * and the cap never applied to collection anyway.
	 */
	public int maxSampledPerEpisode = 2048;

	/** Ceiling on one generation's transitions in the update buffer, ~397 MB at 48.5 KB a step. */
	public int maxSamplesPerGeneration = 8192;

	/**
	 * Seconds a worker may be silent before the run is failed as a protocol desync.
	 *
	 * <p>A blocked pipe costs no CPU, so without this a stall is indistinguishable from a long episode -
	 * and a desynchronised protocol does not crash, it hangs with both processes idle, which is the
	 * worst failure mode for a job meant to run for hours.
	 */
	public long stallSeconds = 180;

	/**
	 * Rejects values that would make the update meaningless or the process unstable.
	 *
	 * <p>Called once, after every source has been merged, rather than at each assignment. A value that
	 * is transiently invalid mid-merge is fine; one that survives every source is a mistake worth
	 * refusing, and refusing it at the boundary means the failure names the setting instead of showing
	 * up as a silent divergence or an out-of-memory much later.
	 */
	public void validate(){
		require( gamma > 0f && gamma <= 1f, "rl.gamma must be in (0, 1], was " + gamma );
		require( lambda >= 0f && lambda <= 1f, "rl.gae_lambda must be in [0, 1], was " + lambda );
		require( clipEpsilon > 0f, "rl.clip_epsilon must be positive, was " + clipEpsilon );
		require( learningRate > 0f, "rl.learning_rate must be positive, was " + learningRate );
		require( entropyCoeff >= 0f, "rl.entropy_coefficient must be non-negative, was " + entropyCoeff );
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
	}

	private static void require( boolean condition, String message ){
		if (!condition) throw new IllegalArgumentException( message );
	}

	/** An independent copy, so a binder can fill one without touching a live run's settings. */
	public PpoHyperparameters copy(){
		PpoHyperparameters p = new PpoHyperparameters();
		p.gamma = gamma;
		p.lambda = lambda;
		p.clipEpsilon = clipEpsilon;
		p.clipEpsilonMeasuredAgainstRatio = clipEpsilonMeasuredAgainstRatio;
		p.entropyCoeff = entropyCoeff;
		p.learningRate = learningRate;
		p.minibatchSize = minibatchSize;
		p.epochs = epochs;
		p.updateThreads = updateThreads;
		p.sampleRate = sampleRate;
		p.maxSampledPerEpisode = maxSampledPerEpisode;
		p.maxSamplesPerGeneration = maxSamplesPerGeneration;
		p.stallSeconds = stallSeconds;
		return p;
	}
}