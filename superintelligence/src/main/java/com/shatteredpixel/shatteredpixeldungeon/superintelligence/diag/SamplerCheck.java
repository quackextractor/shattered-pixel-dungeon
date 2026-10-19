package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.ChunkReservoir;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.ContiguousChunkSampler;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeCollector;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.EpisodeRecord;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SamplerCheck {

	private static final int CHECKS = 8;

	private static final int CHUNK = 16;

	private static final int STEPS = 20000;

	public static void main( String[] args ){
		checkEveryRetainedStepIsPartOfAContiguousRun();
		checkTheRetainedFractionIsTheRequestedRate();
		checkTheTailIsRetainedWhateverTheRate();
		checkTheCapIsHonoured();
		checkZeroAndOneAreSettingsRatherThanAnError();
		checkTheTrunkIsDownsampledBeforeTheDenseMatrix();
		checkTheCollectorKeepsContiguousRunsAcrossARealEpisode();
		checkTheCapResamplesWholeChunksUnbiasedly();

		System.exit( GateReport.report( "sampling and trunk", CHECKS ));
	}

	private static void fail( String message ){
		GateReport.fail( message );
	}


	private static void checkTheCapResamplesWholeChunksUnbiasedly(){
		int episodes = 1000;
		int stepsPerEpisode = 20;
		int cap = 2000;

		ChunkReservoir reservoir = new ChunkReservoir( cap / CHUNK, CHUNK, 20261016L );
		boolean[] touched = new boolean[ episodes ];

		for (int episode = 0; episode < episodes; episode++){
			List< Transition > collected = new ArrayList<>();
			for (int step = 0; step < stepsPerEpisode; step++){
				Transition t = new Transition();
				t.episodeId = episode;
				t.stepIndex = step;
				collected.add( t );
			}
			reservoir.offer( collected );
		}

		if (reservoir.offered() != (long) episodes * stepsPerEpisode){
			fail( "the reservoir was offered " + reservoir.offered() + " steps where "
					+ ((long) episodes * stepsPerEpisode) + " were produced" );
			return;
		}

		for (List< Transition > chunk : reservoir.retainedChunks()){
			int episode = chunk.get( 0 ).episodeId;
			if (episode < 0 || episode >= episodes){
				fail( "a retained chunk names episode " + episode + ", which is not one of the "
						+ episodes + " offered" );
				return;
			}
			for (int i = 1; i < chunk.size(); i++){
				if (chunk.get( i ).episodeId != episode
						|| chunk.get( i ).stepIndex != chunk.get( i - 1 ).stepIndex + 1){
					fail( "a retained chunk is not contiguous: episode " + episode + " step "
							+ chunk.get( i - 1 ).stepIndex + " then episode " + chunk.get( i ).episodeId
							+ " step " + chunk.get( i ).stepIndex + ". A cap that cuts a chunk hands the"
							+ " update runs of one and the recurrent gradient is lost again" );
					return;
				}
			}
			touched[ episode ] = true;
		}

		int capChunks = cap / CHUNK;
		if (reservoir.chunkCount() > capChunks){
			fail( "the reservoir holds " + reservoir.chunkCount() + " chunks against a cap of "
					+ capChunks );
			return;
		}

		int represented = 0;
		for (boolean t : touched) if (t) represented++;

		int[] perDecile = new int[ 10 ];
		for (int episode = 0; episode < episodes; episode++) if (touched[ episode ]) perDecile[ episode * 10 / episodes ]++;

		float expected = (float) represented / perDecile.length;
		for (int decile = 0; decile < perDecile.length; decile++){
			if (perDecile[ decile ] < expected / 3f || perDecile[ decile ] > expected * 2f){
				fail( "decile " + decile + " of the generation holds " + perDecile[ decile ] + " of "
						+ represented + " represented episodes, against " + expected + " for an unbiased"
						+ " sample. A cap that keeps the newest (or the oldest) chunks trains on one end of"
						+ " the generation and calls it a sample: " + java.util.Arrays.toString( perDecile ));
				return;
			}
		}

		System.out.println( "  " + episodes + " episodes of " + stepsPerEpisode + " steps into a "
				+ capChunks + "-chunk reservoir: " + reservoir.chunkCount() + " chunks retained, "
				+ reservoir.replacements() + " replaced, " + represented + " episodes represented, "
				+ java.util.Arrays.toString( perDecile ) + " per decile, every retained chunk contiguous" );
	}


	private static void checkEveryRetainedStepIsPartOfAContiguousRun(){
		ContiguousChunkSampler sampler = new ContiguousChunkSampler();
		Random rng = new Random( 20261016 );

		boolean[] kept = new boolean[ STEPS ];
		int retained = 0;
		for (int step = 0; step < STEPS; step++){
			kept[ step ] = sampler.shouldSample( step, 0, 0, 0.05f, CHUNK, retained,
					Integer.MAX_VALUE, rng );
			retained += kept[ step ] ? 1 : 0;
		}

		List< Integer > runs = runs( kept );
		int longest = 0;
		int singletons = 0;
		for (int run : runs){
			if (run > longest) longest = run;
			if (run == 1) singletons++;
		}

		if (longest < CHUNK ){
			fail( "the longest contiguous run of retained steps was " + longest + " at 5% with"
					+ " rl.chunk_length=" + CHUNK + ", so no backpropagated run is even half the length"
					+ " the setting asks for" );
			return;
		}
		if (singletons > 1 ){
			fail( retained + " retained steps group into " + runs.size() + " contiguous runs and "
					+ singletons + " of them are a single step. A block that begins on the episode's"
					+ " last step is the only run of length one that block sampling can produce; more"
					+ " than one means the sampler is still drawing per step" );
			return;
		}
		if (runs.size() < 5 ){
			fail( "5% of " + STEPS + " steps produced " + runs.size() + " contiguous runs, which is"
					+ " too few to call a property: the case would pass on a sampler that almost"
					+ " never samples" );
			return;
		}

		System.out.println( "  " + retained + " retained steps group into " + runs.size()
				+ " contiguous runs, longest " + longest + ", no interior gaps" );
	}


	private static List< Integer > runs( boolean[] kept ){
		List< Integer > runs = new ArrayList<>();
		int run = 0;
		for (int i = 0; i < kept.length; i++){
			if (kept[ i ]) run++;
			else if (run > 0){ runs.add( run ); run = 0; }
		}
		if (run > 0) runs.add( run );
		return runs;
	}


	private static void checkTheRetainedFractionIsTheRequestedRate(){
		for (float rate : new float[]{ 0.01f, 0.05f, 0.25f }){
			ContiguousChunkSampler sampler = new ContiguousChunkSampler();
			Random rng = new Random( 99 );

			int retained = 0;
			for (int step = 0; step < STEPS; step++){
				if (sampler.shouldSample( step, 0, 0, rate, CHUNK, retained, Integer.MAX_VALUE, rng )) retained++;
			}

			float actual = (float) retained / STEPS;
			float ceiling = rate;
			float floorBound = rate / (1f + rate );

			if (actual > ceiling || actual < 0.9f * floorBound ){
				fail( "a requested sample rate of " + rate + " retained " + actual
						+ " of the episode, outside [" + (0.9f * floorBound) + ", " + ceiling + "]. A"
						+ " block start is drawn at rate/L and occupies L steps, so the retained"
						+ " fraction converges on rate/(1+rate) and can never exceed the rate itself."
						+ " Anything else means the sampler is not drawing whole blocks" );
				return;
			}
		}

		System.out.println( "  block sampling retains between rate/(1+rate) and the rate itself:"
				+ " 1%, 5% and 25% all inside that band over " + STEPS + " steps" );
	}


	private static void checkTheTailIsRetainedWhateverTheRate(){
		ContiguousChunkSampler sampler = new ContiguousChunkSampler();
		Random rng = new Random( 7 );

		int total = 500;
		int tail = 20;
		int retained = 0;
		boolean[] kept = new boolean[ total + 1 ];

		for (int step = 0; step < total; step++){
			boolean want = sampler.shouldSample( step, total, tail, 0.0001f, CHUNK, retained,
					Integer.MAX_VALUE, rng );
			if (want) kept[ step ] = true;
			retained += want ? 1 : 0;
		}

		for (int step = total - tail; step < total; step++){
			if (!kept[ step ]){
				fail( "step " + step + " is inside the last " + tail + " of a " + total + "-step"
						+ " episode and was not retained at a sample rate of 0.0001. The death penalty"
						+ " and the depth reward each land on one step, and under a rate that small"
						+ " almost no episode would contribute a terminal signal at all" );
				return;
			}
		}

		System.out.println( "  the last " + tail + " steps of an episode are retained whatever the rate" );
	}


	private static void checkTheCapIsHonoured(){
		ContiguousChunkSampler sampler = new ContiguousChunkSampler();
		Random rng = new Random( 3 );

		int cap = 40;
		int retained = 0;
		for (int step = 0; step < STEPS; step++){
			if (sampler.shouldSample( step, 0, 0, 1f, CHUNK, retained, cap, rng )) retained++;
		}

		if (retained != cap ){
			fail( "an uncapped request retained " + retained + " steps against a cap of " + cap );
			return;
		}

		System.out.println( "  a cap of " + cap + " is honoured exactly, so one long episode cannot"
				+ " fill a worker's heap" );
	}


	private static void checkZeroAndOneAreSettingsRatherThanAnError(){
		ContiguousChunkSampler off = new ContiguousChunkSampler();
		ContiguousChunkSampler all = new ContiguousChunkSampler();
		Random rng = new Random( 5 );

		for (int step = 0; step < 100; step++){
			if (off.shouldSample( step, 0, 0, 0f, CHUNK, 0, Integer.MAX_VALUE, rng )){
				fail( "a sample rate of 0 retained step " + step + ". Zero is a real setting and not"
						+ " 'off': it is what makes two runs of one configuration comparable" );
				return;
			}
			if (!all.shouldSample( step, 0, 0, 1f, CHUNK, 0, Integer.MAX_VALUE, rng )){
				fail( "a sample rate of 1 dropped step " + step );
				return;
			}
		}

		System.out.println( "  a rate of 0 retains nothing and a rate of 1 retains everything" );
	}


	private static void checkTheTrunkIsDownsampledBeforeTheDenseMatrix(){
		EnvConfig config = new EnvConfig();
		Network network = new Network( config, new Random( 11 ));

		long convFeatures = network.conv.outputSize();
		long reduceFeatures = network.reduce.outputSize();
		long trunkIn = network.trunk.in;
		long extra = config.maxSlots
				* com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder.FEATURES_PER_SLOT
				+ com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder.FEATURES;

		if (reduceFeatures >= convFeatures ){
			fail( "the reduction convolution feeds " + reduceFeatures + " features to the trunk against"
					+ " the " + convFeatures + " it consumed, so nothing was downsampled and the"
					+ " flattened grid is still the trunk's whole input" );
			return;
		}
		if (trunkIn != reduceFeatures + extra ){
			fail( "the trunk takes " + trunkIn + " inputs, which is neither the reduced grid ("
					+ reduceFeatures + ") plus the inventory and hero scalars (" + extra + "), nor"
					+ " anything else" );
			return;
		}

		long trunkWeights = (long) network.trunk.W.size();
		if (trunkWeights > 2_000_000L ){
			fail( "the trunk alone holds " + trunkWeights + " weights. A grid flattened straight into"
					+ " a dense layer put 3.25M of a 3.58M-parameter network in one matrix, which is"
					+ " both the update's dominant cost and the gradient reduction's bandwidth" );
			return;
		}

		System.out.println( "  the grid is reduced before the dense trunk: " + convFeatures
				+ " -> " + reduceFeatures + " features, " + trunkWeights + " trunk weights, "
				+ String.format( "%,d", network.parameterCount() ) + " parameters in total" );
	}


	private static void checkTheCollectorKeepsContiguousRunsAcrossARealEpisode(){
		HeadlessServices.install( Scratch.dir( "samplercheck" ));
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 300;
		config.turnLimitTotal = 300;
		config.stallLimit = 300;
		config.freeStepBudget = 300;

		int[] shortest = collect( config, 1 );
		int[] chunked = collect( config, CHUNK );

		if (shortest[ 0 ] < 8 || chunked[ 0 ] < 8 ){
			fail( "the fixture retained " + shortest[ 0 ] + " steps at chunk length 1 and "
					+ chunked[ 0 ] + " at chunk length " + CHUNK + ", so it is too short for the"
					+ " comparison to mean anything" );
			return;
		}

		if (shortest[ 1 ] >= CHUNK ){
			fail( "a collector asked for chunks of 1 retained steps in runs of up to " + shortest[ 1 ]
					+ ", so the chunk length it was given is not the chunk length it applied" );
			return;
		}

		if (chunked[ 1 ] < CHUNK ){
			fail( "collected " + chunked[ 0 ] + " transitions at chunk length " + CHUNK
					+ " and the longest contiguous run is " + chunked[ 1 ]
					+ ". PPO backpropagates a run as one chunk, so this is the length-one truncation"
					+ " the recurrent gradient was supposed to have left" );
			return;
		}

		System.out.println( "  a collector built with chunk length 1 retains short runs ("
				+ shortest[ 0 ] + " retained, longest run " + shortest[ 1 ] + ") and one built with " + CHUNK
				+ " retains whole blocks (" + chunked[ 0 ] + " retained, longest run " + chunked[ 1 ] + ")" );
	}


	private static int[] collect( EnvConfig config, int chunkLength ){
		Random policySeed = new Random( 4242 );
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		Network network = new Network( config, policySeed );

		EpisodeCollector collector = new EpisodeCollector( config, network, policySeed,
				new Random( 777 ), 0.99f, 0.95f, 0.25f, 4096, chunkLength );
		collector.tailSteps( 0 );

		EpisodeRecord record = collector.run( env, "SAMPLERCHECK-A",
				com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass.WARRIOR );

		List< Transition > kept = new ArrayList<>( record.sampledTransitions() );
		kept.sort( ( a, b ) -> Integer.compare( a.stepIndex, b.stepIndex ));

		int longest = 0;
		int run = 0;
		for (int i = 0; i < kept.size(); i++){
			boolean adjacent = i > 0 && kept.get( i ).episodeId == kept.get( i - 1 ).episodeId
					&& kept.get( i ).stepIndex == kept.get( i - 1 ).stepIndex + 1;
			run = adjacent ? run + 1 : 1;
			longest = Math.max( longest, run );
		}

		int[] out = { record.sampledCount(), longest };
		record.release();
		return out;
	}
}