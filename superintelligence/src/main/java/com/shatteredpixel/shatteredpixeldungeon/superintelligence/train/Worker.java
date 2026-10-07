package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
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

		config.maxSlots = in.readInt();
		config.gridWidth = in.readInt();
		config.gridHeight = in.readInt();
		config.turnLimitPerFloor = in.readInt();
		config.turnLimitTotal = in.readInt();
		config.depthReward = in.readFloat();
		config.turnCost = in.readFloat();
		config.deathPenalty = in.readFloat();
		config.victoryReward = in.readFloat();
config.stallLimit = in.readInt();

		//The advantages are computed here, so these have to come from the trainer rather than from
		//this side's defaults. Previously gamma and lambda were PPO fields that both sides happened
		//to agree on - identical defaults, which is coincidence rather than configuration, and it
		//would drift the first time either side was tuned.
		float gamma = in.readFloat();
		float lambda = in.readFloat();
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
		recorder.begin( seed, heroClass.name(), 0, config.turnLimitPerFloor );

		//started after the reset so the CPU figure is the episode and not level generation
		ResourceStats.Interval work = ResourceStats.start();

		//the collector plays the episode, so the recorder is told about each step as it happens
		//rather than driving it
		collector.listener( ( mode, action, secondary, heroPosition, reward ) -> {
			recorder.record( action, secondary, mode );
			recorder.afterStep( heroPosition, reward );
		} );

		EpisodeRecord record = collector.run( env, seed, heroClass );

		work.stop();

		recorder.end( env.ledger().total(), env.depth(), env.turnsTotal(), 0 );
		Replay replay = recorder.replay();

		out.writeInt( Protocol.MSG_EPISODE );

		//written through the shared codec rather than field by field here, because the trainer reads
		//it with the mirror method. Two lists of the same seven fields is two chances to disagree, and
		//a disagreement here reads a plausible number rather than failing.
		Episode summary = new Episode();
		summary.score = env.ledger().total();
		summary.depth = env.depth();
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

	private void writeReplay( Replay replay ) throws IOException {
		out.writeUTF( replay.seedText );
		out.writeUTF( replay.heroClass );
		out.writeDouble( replay.score );
		out.writeInt( replay.depth );
		out.writeInt( replay.turns );
		for (Replay.Step step : replay.steps){
			out.writeUTF( step.action );
			out.writeInt( step.slot );
			out.writeUTF( step.mode );
			out.writeInt( step.heroPos );
		}
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