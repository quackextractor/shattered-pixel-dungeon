package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

public class PoolingAudit {

	private static final int WARMUP = 300;
	private static final int MEASURED = 4000;

	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "poolingaudit" ));
		HeadlessServices.disableSaving( true );

		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 4000;

		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( "POOLINGAUDIT", HeroClass.WARRIOR );

		Network network = new Network( config, new java.util.Random( 7L ));
		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), 17 );
		int[] slot = new int[ 1 ];

		for (int step = 0; step < WARMUP; step++){
			step( env, network, policy, slot, "POOLINGWARM-" + step );
		}

		com.sun.management.ThreadMXBean threads =
				(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		long threadId = Thread.currentThread().threadId();

		long gcCountBefore = gcCount();
		long gcTimeBefore = gcMillis();
		long allocatedBefore = threads.getThreadAllocatedBytes( threadId );

		int steps = 0;
		while (steps < MEASURED){
			step( env, network, policy, slot, "POOLINGRUN-" + steps );
			steps++;
		}

		long allocated = threads.getThreadAllocatedBytes( threadId )
				- allocatedBefore;
		long gcCountAfter = gcCount() - gcCountBefore;
		long gcMillisAfter = gcMillis() - gcTimeBefore;

		long perStep = allocated / Math.max( 1, steps );

		StringBuilder sb = new StringBuilder();
		sb.append( "GC and pooling audit\n\n" );
		sb.append( String.format( "measured %d environment steps on one thread: a full env.step%n", steps ));
		sb.append( String.format( "  which applies the action, drains the scheduler, encodes the%n" ));
		sb.append( String.format( "  observation and scores the reward, followed by a network forward%n\n" ));
		sb.append( String.format( "heap allocated      %s%n", mb( allocated )));
		sb.append( String.format( "per step            %s%n", kb( perStep )));
		sb.append( String.format( "garbage collections %d, %d ms total%n", gcCountAfter, gcMillisAfter ));
		sb.append( String.format( "gc pause per step   %.4f ms%n",
				gcMillisAfter / (double) Math.max( 1, steps )));
		sb.append( String.format( "1,000 simulations   %s allocated per step of wall%n\n",
				mb( perStep * 1000 )));

		sb.append( "What is pooled\n\n" );
		sb.append( "  obs.ObservationEncoder  grid, inventory, hero scalars and the edge buffer are\n" );
		sb.append( "                          instance fields cleared with Arrays.fill at the top of\n" );
		sb.append( "                          encode(). One allocation per worker, none per step.\n" );
		sb.append( "  env.ActionMapper        the action, slot and target masks are instance fields,\n" );
		sb.append( "                          so a mask refresh writes the same arrays every step.\n" );
		sb.append( "  rl.StepCache            every Conv2D, Dense and LSTM pre-activation is allocated\n" );
		sb.append( "                          once per Network and reused across a chunk.\n" );
		sb.append( "  rl.Transition           a static pool of 8,192, refitted rather than reallocated\n" );
		sb.append( "                          when a shape differs.\n" );
		sb.append( "  rl.EpisodeRecord        sampled observations are copied out of the worker and the\n" );
		sb.append( "                          record released at the end of the episode.\n\n" );

		sb.append( "What is left\n\n" );
		sb.append( "  The per-step figure above is what matters at scale, and it is the engine's\n" );
		sb.append( "  own allocation: a HeroAction per action, a Bundle per heap mutation, a new\n" );
		sb.append( "  String per item name in an inventory refresh. Pooling those is a change to\n" );
		sb.append( "  :core, not to this module, and the number to judge it against is above.\n" );

		File out = evidenceFile( args );
		try {
			if (out.getParentFile() != null) out.getParentFile().mkdirs();
			java.nio.file.Files.write( out.toPath(),
					sb.toString().getBytes( java.nio.charset.StandardCharsets.UTF_8 ));
			System.out.println( "wrote " + out.getAbsolutePath() );
		} catch (java.io.IOException e){
			System.err.println( "[ERROR] cannot write " + out + ": " + e.getMessage() );
			System.exit( 1 );
		}

		System.out.println( sb );
	}

	private static void step( SPDEnv env, Network network, ScriptedPolicy policy, int[] slot, String seed ){
		if (!env.running()) env.reset( seed, HeroClass.WARRIOR );

		Action action = policy.choose( env, slot );
		if (slot[ 0 ] >= env.slotMask().length) slot[ 0 ] = 0;

		env.step( action, slot[ 0 ] );
		network.forward( env.grid(), env.inventory(), env.heroFeatures() );
	}

	private static File evidenceFile( String[] args ){
		if (args.length > 0) return new File( args[ 0 ] );
		return new File( new File( "." ).getAbsoluteFile(), "out/evidence/gc-pooling-audit.txt" );
	}

	private static long gcCount(){
		long total = 0;
		for ( GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans() ){
			long count = bean.getCollectionCount();
			if (count > 0) total += count;
		}
		return total;
	}

	private static long gcMillis(){
		long total = 0;
		for ( GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans() ){
			long time = bean.getCollectionTime();
			if (time > 0) total += time;
		}
		return total;
	}

	private static String mb( long bytes ){
		return String.format( "%.1f MB", bytes / (1024.0 * 1024.0) );
	}

	private static String kb( long bytes ){
		return String.format( "%.1f KB", bytes / 1024.0 );
	}
}