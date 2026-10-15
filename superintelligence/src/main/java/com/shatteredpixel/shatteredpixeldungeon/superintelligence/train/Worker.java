package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeRecord;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * A single training worker.
 *
 * Each worker is its own JVM, and that is not a performance detail - it is a requirement. The
 * game's simulation state is almost entirely static: {@code Dungeon.hero}, {@code Dungeon.level},
 * {@code Actor.now}, {@code Level.visited} and the whole actor registry. Two environments cannot
 * coexist in one process without corrupding each other. research.md's "1,000 simulations at once"
 * therefore means 1,000 processes, not 1,000 threads.
 *
 * Communication with the trainer is a length-prefixed binary protocol on stdin and stdout, kept
 * strictly separate from the human-readable log which goes to stderr. Anything written to stdout
 * that is not a protocol frame would desynchronise the stream.
 */
public class Worker {

	private final DataInputStream in;
	private final DataOutputStream out;

private EnvConfig config;
	private Network network;
	private EpisodeCollector collector;
	private SPDEnv env;

	private final ReplayRecorder recorder = new ReplayRecorder();

	public Worker( DataInputStream in, DataOutputStream out ){
		this.in = in;
		this.out = out;
}

	/** Serves the trainer until it says goodbye. */
	public void serve() throws IOException {
		if (in.readInt() != Protocol.MSG_HELLO || in.readInt() != Protocol.VERSION){
			throw new IOException( "worker: trainer protocol mismatch" );
		}

		out.writeInt( Protocol.MSG_HELLO );
		out.writeInt( Protocol.VERSION );
		out.flush();

		while (true){
			int message = in.readInt();
			if (message == Protocol.MSG_BYE) break;

			switch (message) {
				case Protocol.MSG_PARAMS:
					readParams();
					break;
				case Protocol.MSG_EPISODE:
					runEpisode( in.readUTF(), HeroClass.valueOf( in.readUTF() ), in.readBoolean() );
					break;
				default:
					throw new IOException( "worker: unexpected message " + message );
			}
		}
	}

	private void readParams() throws IOException {
		if (config == null){
			config = new EnvConfig();
		}

		//the whole configuration, from the one list in EnvConfig. This used to be a second hand-kept
		//list of ten fields beside the one in Trainer, and the twenty they omitted were never sent -
		//so this process silently kept its own defaults for freeStepBudget, damagePenalty, stallPenalty
		//and seventeen others, whatever the trainer had been configured with.
		config.readFrom( in );

		//The advantages are computed here, so these have to come from the trainer rather than from
		//this side's defaults. Previously gamma and lambda were PPO fields that both sides happened
		//to agree on - identical defaults, which is coincidence rather than configuration, and it
		//would drift the first time either side was tuned.
		float gamma = in.readFloat();
		float lambda = in.readFloat();

		String traceWorld = in.readUTF();
		float sampleRate = in.readFloat();
		int maxSampledPerEpisode = in.readInt();

		Random policyRng = new Random( in.readLong() );

		HeadlessGame game = HeadlessGame.install();
		env = new SPDEnv( config, game );

		network = new Network( config, policyRng );

		//a separate stream for sampling, so that changing the sample rate does not change which
		//actions a run takes. Otherwise every worker would behave differently at a different rate and
		//no two sample rates could be compared
		collector = new EpisodeCollector( config, network, policyRng, new Random( policyRng.nextLong() ),
				gamma, lambda, sampleRate, maxSampledPerEpisode );
		if (traceWorld != null && !traceWorld.isEmpty()) collector.trace( traceWorld );
		readWeights( in, network );

		out.writeInt( Protocol.MSG_PARAMS );
		out.flush();
	}

/**
	 * Runs one episode and reports its outcome, with an optional replay attached.
	 *
	 * The episode is played by the real policy now, not by the scripted heuristic, and the record it
	 * produces is kept: the scalars for every step, the sampled subset's observations, and the
	 * advantages computed over the complete episode before anything was discarded. The frame written
	 * at the end is the summary only; the transitions follow in their own message.
	 */
	private void runEpisode( String seed, HeroClass heroClass, boolean wantReplay ) throws IOException {
		//started after the reset so the CPU figure is the episode and not level generation
		ResourceStats.Interval work = ResourceStats.start();

		//The collector plays the episode, so the recorder is told about each step as it happens
		//rather than driving it. The seed arrives through onEpisodeStart rather than being passed in
		//here, because this side of the call only has the seed that was *requested* and an empty one
		//means the game drew another - recording that request would produce a file no verify could
		//reproduce, since verify would reset onto a fresh draw.
		collector.listener( new EpisodeCollector.Listener() {
			@Override public void onEpisodeStart( String seedText, String hero ){
				//the config too, so the header says how wide the slot head was - a recorded
				//slot index means nothing without it
				recorder.begin( seedText, hero, 0, config.turnLimitPerFloor, config );
			}

			/**
			 * The recorder is told here, before the step.
			 *
			 * <p>{@code ReplayRecorder.record} pins the quickslot bindings at the moment the slot index is
			 * chosen, and said so in its own comment - an equip or unequip in this very step rebinds a
			 * slot, so the binding captured afterwards describes a different world. It was called from
			 * {@code onStep} instead, which is after the step by definition, so every trainer recording
			 * carried the bindings its own step produced rather than the ones its index was chosen
			 * against. A {@code DROP} is the action that unbinds its own item, so that is the step where
			 * it showed: on {@code MSU-OTL-OAT}, {@code DROP/1} in {@code SLOT} dropped a ThrowingSpike
			 * headlessly and a Food in the viewer, because the ThrowingSpike's binding to quickslot 1
			 * was gone from the recording before the viewer ever read it.
			 */
			@Override public void onDecision( EnvMode mode, Action action, int secondary ){
				recorder.record( action, secondary, mode );
			}

			@Override public void onStep( int heroPosition, float reward ){
				recorder.afterStep( heroPosition, reward );
			}
		} );

		EpisodeRecord record = collector.run( env, seed, heroClass );

		work.stop();

		recorder.end( env.ledger().total(), env.deepestDepth(), env.turnsTotal(), 0, env.endReason().name() );
		Replay replay = recorder.replay();

		out.writeInt( Protocol.MSG_EPISODE );

		//written through the shared codec rather than field by field here, because the trainer reads
		//it with the mirror method. Two lists of the same seven fields is two chances to disagree, and
		//a disagreement here reads a plausible number rather than failing.
		Episode summary = new Episode();
		summary.score = env.ledger().total();
		summary.depth = env.deepestDepth();
		summary.turns = env.turnsTotal();
		summary.endedNaturally = env.endedNaturally();
		summary.reason = env.endReason().name();

		//Cumulative rather than per-episode. OS process CPU counters have roughly millisecond
		//granularity, and an episode here is often only tens of milliseconds, so differencing the
		//counter across each one lost most of the signal and under-reported the pool by about half.
		//A monotonic total let the trainer difference between reports instead.
		summary.workerCpuTotal = ResourceStats.processCpuSecondsTotal();
		summary.workerHeapMb = work.to().heapUsedMb();

		Episode.write( out, summary );

//The replay keeps its place on this frame for now. It rides on the summary rather than the
		//transition channel, which is where the plan wants it, and at one replay every third episode
		//it is a small share of a generation's traffic.
		out.writeInt( replay.length() );

		if (wantReplay){
			writeReplay( replay );
		}

		//after the summary and any replay, so a trainer reading this frame in the documented order
		//stays in step. The advantages in here were computed over the whole episode, so the trainer
		//must not try to recompute them.
		TransitionCodec.write( out, record.sampledTransitions() );

		out.flush();

		//back to the pool now that the frame is written. Holding them until the next episode would
		//pin ~98MB per worker for no reason.
		record.release();
	}

	/**
	 * Ships a recorded episode to the trainer.
	 *
	 * <p><b>All nine step fields, and the header fields that decide what a recorded index means.</b> This
	 * used to send four — action, slot, mode and hero position — and the trainer rebuilt the rest from
	 * nothing. So every replay the trainer has ever written carried {@code heroHp = -1},
	 * {@code turn = -1.0} and an empty inventory, which are the readers' sentinels for *not recorded*.
	 *
	 * <p>That is invisible rather than broken, and it is worth being precise about why.
	 * {@code ReplayIO.verify} guards every comparison with its own sentinel — {@code if (step.heroHp >=
	 * 0 && …)}, {@code if (!step.inventory.isEmpty() && …)} — so a recording that carries no health and
	 * no engine time verifies on **position alone** and reports {@code [OK]}. Measured on a run that
	 * really had reached depth 2: 655 steps, no divergence, and a recording that could not have detected
	 * a starving hero or a one-turn drift. {@code ReplayRecorder} carries a comment saying exactly that
	 * failure is why health and turn are recorded at all — *"both of those played back cleanly while
	 * being wrong"* — and this path threw them away on the way to disk.
	 *
	 * <p>The viewer is affected the same way: no per-step score, no health divergence, no turn drift.
	 *
	 * <p>Written as a private static pair of methods rather than inline at the call site so that the
	 * encoder and the decoder are one change and cannot drift the way they did. They are still a third
	 * implementation of "write a replay" alongside {@code ReplayIO.write} and the text format, which is
	 * the duplication {@code PLAN-replay-parity.md} warns about; the version byte below is what makes
	 * that survivable.
	 */
	private void writeReplay( Replay replay ) throws IOException {
		ReplayCodec.write( out, replay );
	}

	// --------------------------------------------------------------------------- weights

	/** Sends the current policy to the worker. */
	public static void writeWeights( DataOutputStream out, Network net ) throws IOException {
		Network.Layer[] layers = net.layers();
		out.writeInt( layers.length );
		for (Network.Layer layer : layers){
			out.writeUTF( layer.name );
			out.writeInt( layer.in );
			out.writeInt( layer.out );
			out.writeInt( layer.weights.length );
			for (float v : layer.weights) out.writeFloat( v );
			out.writeInt( layer.bias.length );
			for (float v : layer.bias) out.writeFloat( v );
		}
	}

	/** Reads a policy sent by {@link #writeWeights}. */
	public static void readWeights( DataInputStream in, Network net ) throws IOException {
		int count = in.readInt();
		for (int i = 0; i < count; i++){
			String name = in.readUTF();
			int rows = in.readInt();
			int cols = in.readInt();

			int weightCount = in.readInt();
			float[] weights = new float[ weightCount ];
			for (int w = 0; w < weightCount; w++) weights[ w ] = in.readFloat();

			int biasCount = in.readInt();
			float[] bias = new float[ biasCount ];
			for (int b = 0; b < biasCount; b++) bias[ b ] = in.readFloat();

			net.loadLayer( new Network.Layer( name, rows, cols, weights, bias ) );
		}
	}
}
