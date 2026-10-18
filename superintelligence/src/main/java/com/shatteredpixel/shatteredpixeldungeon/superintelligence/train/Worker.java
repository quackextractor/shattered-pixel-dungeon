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
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeRecord;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;


public class Worker {

	private final DataInputStream in;
	private final DataOutputStream out;

private EnvConfig config;
	private Network network;
	private EpisodeCollector collector;
	private SPDEnv env;
	private String rngTraceDir = "";
	private final List< String > generation = new ArrayList<>();
	private final List< String > generationPushes = new ArrayList<>();

	private final ReplayRecorder recorder = new ReplayRecorder();

	public Worker( DataInputStream in, DataOutputStream out ){
		this.in = in;
		this.out = out;
}


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

		config.readFrom( in );

		float gamma = in.readFloat();
		float lambda = in.readFloat();

		String traceWorld = in.readUTF();
		rngTraceDir = in.readUTF();
		float sampleRate = in.readFloat();
		int maxSampledPerEpisode = in.readInt();
		int tailSteps = in.readInt();
		int chunkLength = in.readInt();

		Random policyRng = new Random( in.readLong() );

		HeadlessGame game = HeadlessGame.install();
		env = new SPDEnv( config, game );

		network = new Network( config, policyRng );

		collector = new EpisodeCollector( config, network, policyRng, new Random( policyRng.nextLong() ),
				gamma, lambda, sampleRate, maxSampledPerEpisode, chunkLength );
		collector.tailSteps( tailSteps );
		if (traceWorld != null && !traceWorld.isEmpty()) collector.trace( traceWorld );
		readWeights( in, network );

		out.writeInt( Protocol.MSG_PARAMS );
		out.flush();
	}


	private void runEpisode( String seed, HeroClass heroClass, boolean wantReplay ) throws IOException {

		ResourceStats.Interval work = ResourceStats.start();

		boolean tracing = rngTraceDir != null && !rngTraceDir.isEmpty();

		if (tracing){
			com.watabou.utils.RandomTrace.enable();
			com.watabou.utils.RandomTrace.reset();
			com.watabou.utils.RandomTrace.attribute();
		}

		collector.listener( new EpisodeCollector.Listener() {
			@Override public void onEpisodeStart( String seedText, String hero ){

				if (tracing){
					generation.clear();
					generation.addAll( com.watabou.utils.RandomTrace.siteLog() );
					generationPushes.clear();
					generationPushes.addAll( com.watabou.utils.RandomTrace.generatorLog() );
				}

				recorder.begin( seedText, hero, 0, config.turnLimitPerFloor, config );
			}


			@Override public void onDecision( EnvMode mode, Action action, int secondary ){
				recorder.record( action, secondary, mode );
			}

			@Override public void onStep( int heroPosition, float reward, boolean accepted ){
				recorder.afterStep( heroPosition, reward, accepted ? 1 : 0 );
			}
		} );

		EpisodeRecord record = collector.run( env, seed, heroClass );

		if (tracing) writeOrderedLogs( env.seedText() );

		work.stop();

		recorder.end( env.ledger().total(), env.deepestDepth(), env.turnsTotal(), 0, env.endReason().name() );
		Replay replay = recorder.replay();

		out.writeInt( Protocol.MSG_EPISODE );

		Episode summary = new Episode();
		summary.score = env.ledger().total();
		summary.depth = env.deepestDepth();
		summary.turns = env.turnsTotal();
		summary.endedNaturally = env.endedNaturally();
		summary.reason = env.endReason().name();
		summary.stallCause = env.stallCause().ordinal();

		summary.objectiveScore = env.ledger().objectiveScore();
		for (RewardTerm term : RewardTerm.values()){
			summary.termTotals[ term.ordinal() ] = env.ledger().termTotal( term );
			summary.termCounts[ term.ordinal() ] = env.ledger().notes( term );
		}

		summary.workerCpuTotal = ResourceStats.processCpuSecondsTotal();
		summary.workerHeapMb = work.to().heapUsedMb();

		Episode.write( out, summary );

		out.writeInt( replay.length() );

		if (wantReplay){
			writeReplay( replay );
		}

		TransitionCodec.write( out, record.sampledTransitions() );

		out.flush();

		record.release();
	}


	private void writeReplay( Replay replay ) throws IOException {
		ReplayCodec.write( out, replay );
	}


	private void writeOrderedLogs( String seed ){
		java.io.File dir = new java.io.File( rngTraceDir );
		if (!dir.isDirectory() && !dir.mkdirs()){
			System.err.println( "[WARN] worker: cannot create " + dir.getAbsolutePath()
					+ ", so this episode's ordered draw logs are not written" );
			return;
		}

		String prefix = new java.io.File( dir, seed.replaceAll( "[^A-Za-z0-9._-]", "_" )
				+ ".worker" ).getAbsolutePath();

		try {
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.RngOrderLog
					.write( prefix + ".gen", generation, generationPushes );
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.RngOrderLog
					.writeCurrent( prefix + ".all" );
		} catch (IOException e){
			System.err.println( "[WARN] worker: cannot write the ordered draw logs to " + prefix
					+ " (" + e.getClass().getSimpleName() + ")" );
		}
	}


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
