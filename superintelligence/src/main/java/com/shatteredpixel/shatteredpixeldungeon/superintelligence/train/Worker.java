package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayIO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

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

	/** Protocol version, checked on connect so a mismatched pair fails loudly. */
	public static final int PROTOCOL_VERSION = 1;

private static final int MSG_HELLO        = 1;
	/** Public because the trainer asserts on it when pushing a policy. */
	public static final int MSG_PARAMS       = 2;
	public static final int MSG_EPISODE      = 3;
	private static final int MSG_DONE         = 4;
	private static final int MSG_BYE          = 5;

	private final DataInputStream in;
	private final DataOutputStream out;

	private EnvConfig config;
	private Network network;
	private PPO ppo;
	private SPDEnv env;

	private final ReplayRecorder recorder = new ReplayRecorder();

	public Worker( DataInputStream in, DataOutputStream out ){
		this.in = in;
		this.out = out;
	}

	/** Serves the trainer until it says goodbye. */
	public void serve() throws IOException {
		if (in.readInt() != MSG_HELLO || in.readInt() != PROTOCOL_VERSION){
			throw new IOException( "worker: trainer protocol mismatch" );
		}

		out.writeInt( MSG_HELLO );
		out.writeInt( PROTOCOL_VERSION );
		out.flush();

		while (true){
			int message = in.readInt();
			if (message == MSG_BYE) break;

			switch (message) {
				case MSG_PARAMS:
					readParams();
					break;
				case MSG_EPISODE:
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

		HeadlessGame game = HeadlessGame.install();
		env = new SPDEnv( config, game );

		ppo = new PPO( config, new java.util.Random( in.readLong() ) );
		readWeights( in, ppo.network );

		out.writeInt( MSG_PARAMS );
		out.flush();
	}

/** Runs one episode and reports its outcome, with an optional replay attached. */
	private void runEpisode( String seed, HeroClass heroClass, boolean wantReplay ) throws IOException {
		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), seed.hashCode() );
		recorder.begin( seed, heroClass.name(), 0, config.turnLimitPerFloor );

		env.reset( seed, heroClass );

		int[] slot = new int[ 1 ];

		//started after the reset so the CPU figure is the episode and not level generation
		ResourceStats.Interval work = ResourceStats.start();

		while (env.running()){
			Action a = policy.choose( env, slot );
			recorder.record( a, slot[ 0 ], env.mode() );
			float reward = (float) env.step( a, slot[ 0 ] );
			recorder.afterStep( env.heroPosition(), reward );
		}

		work.stop();

		recorder.end( env.ledger().total(), env.depth(), env.turnsTotal(), 0 );
		Replay replay = recorder.replay();

		out.writeInt( MSG_EPISODE );
		out.writeDouble( env.ledger().total() );
		out.writeInt( env.depth() );
		out.writeInt( env.turnsTotal() );
		out.writeBoolean( env.endedNaturally() );
		out.writeUTF( env.endReason().name() );

		//Cumulative rather than per-episode. OS process CPU counters have roughly millisecond
		//granularity, and an episode here is often only tens of milliseconds, so differencing the
		//counter across each one lost most of the signal and under-reported the pool by about half.
		//A monotonic total let the trainer difference between reports instead.
		out.writeDouble( ResourceStats.processCpuSecondsTotal() );
		out.writeDouble( work.to().heapUsedMb() );

		out.writeInt( replay.length() );

		if (wantReplay){
			writeReplay( replay );
		}

		out.flush();
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