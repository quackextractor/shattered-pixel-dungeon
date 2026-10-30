package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.BatchedInference;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.KernelProfile;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.RemoteNetwork;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.shared.InferenceBroker;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.shared.ThreadRouted;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.IsolatedLoader;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ThroughputBench {

	private static final double BUSY_LOAD = 0.10;

	private static final float GRID_DENSITY = 0.014f;

	private static int rounds = 4, envs = 16, episodes = 4, forwards = 2000, samples = 2048;
	private static String label = "current", legs = "env,inference,update";
	private static boolean batched;
	private static File csv = new File( "out/evidence/throughputbench.csv" );
	private static final Map< String, List< Double > > results = new HashMap<>();

	private ThroughputBench(){}

	public static void main( String[] args ){
		try {
			run( args );
		} catch (Exception e){
			e.printStackTrace();
			System.out.println( "[ERROR] throughputbench: " + e );
			System.exit( 1 );
		}
		System.exit( 0 );
	}

	private static void run( String[] args ) throws Exception {
		for (int i = 0; i + 1 < args.length; i += 2){
			switch (args[ i ]){
			case "--rounds": rounds = Integer.parseInt( args[ i + 1 ] ); break;
			case "--envs": envs = Integer.parseInt( args[ i + 1 ] ); break;
			case "--episodes": episodes = Integer.parseInt( args[ i + 1 ] ); break;
			case "--forwards": forwards = Integer.parseInt( args[ i + 1 ] ); break;
			case "--samples": samples = Integer.parseInt( args[ i + 1 ] ); break;
			case "--label": label = args[ i + 1 ]; break;
			case "--legs": legs = args[ i + 1 ]; break;
			case "--env-inference": batched = args[ i + 1 ].equals( "batched" ); break;
			case "--csv": csv = new File( args[ i + 1 ] ); break;
			case "--kernels":
				if (Boolean.parseBoolean( args[ i + 1 ] )) System.setProperty( "spd.clprofile", "true" );
				break;
			default: throw new IllegalArgumentException( "unknown option " + args[ i ] );
			}
		}
		List< String > chosen = Arrays.asList( legs.split( "," ));
		EnvLeg env = chosen.contains( "env" ) ? new EnvLeg() : null;
		for (int round = 0; round < rounds; round++){
			if (env != null) record( batched ? "collect" : "env", round, "turns/s", env.round() );
			if (chosen.contains( "inference" )) record( "inference", round, "forwards/s", inference() );
			if (chosen.contains( "update" )) record( "update", round, "us/sample-pass", update() );
		}
		if (batched) chosen = new ArrayList<>( chosen );
		if (batched) chosen.replaceAll( l -> l.equals( "env" ) ? "collect" : l );
		for (String leg : chosen){
			List< Double > v = results.get( leg );
			if (v == null || v.size() < 2) continue;
			List< Double > timed = new ArrayList<>( v.subList( 1, v.size() ));
			timed.sort( null );
			System.out.println( String.format( Locale.ROOT, "  %-9s median of rounds 1-%d: %,.1f", leg, v.size() - 1, timed.get( timed.size() / 2 )));
		}
		System.out.println( "  round 0 warms the JIT and is left out of the median; results appended to " + csv.getPath() );
	}

	private static double idleLoad(){
		com.sun.management.OperatingSystemMXBean os =
				(com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
		os.getCpuLoad();
		try {
			Thread.sleep( 1000 );
		} catch (InterruptedException e){
			Thread.currentThread().interrupt();
		}
		return Math.max( 0, os.getCpuLoad() - os.getProcessCpuLoad() );
	}

	private static void record( String leg, int round, String unit, Measurement m ) throws IOException {
		results.computeIfAbsent( leg, k -> new ArrayList<>() ).add( m.value );
		String flag = m.load > BUSY_LOAD ? "  [WARN] machine was " + percent( m.load ) + " busy with other processes before this leg" : "";
		System.out.println( String.format( Locale.ROOT, "  %-9s round %d: %,.1f %s%s", leg, round, m.value, unit, flag ));
		csv.getAbsoluteFile().getParentFile().mkdirs();
		boolean header = !csv.isFile();
		try (PrintWriter w = new PrintWriter( new FileWriter( csv, true ))){
			if (header) w.println( "time,label,leg,round,value,unit,load_before" );
			w.println( String.format( Locale.ROOT, "%s,%s,%s,%d,%.3f,%s,%.3f", Instant.now(), label, leg, round, m.value, unit, m.load ));
		}
	}

	private static String percent( double v ){
		return String.format( Locale.ROOT, "%.0f%%", v * 100 );
	}

	private static final class Measurement {
		final double value, load;

		Measurement( double value, double load ){
			this.value = value;
			this.load = load;
		}
	}

	private static final class EnvLeg {
		final Method[] runs = new Method[ envs ];
		final ExecutorService[] threads = new ExecutorService[ envs ];

		EnvLeg() throws Exception {
			ThreadRouted.enable();
			if (batched) InferenceBroker.install( new BatchedInference( new Network( new EnvConfig(), new Random( 1 ))));
			else InferenceBroker.install( new InferenceBroker.Server() {
				@Override public int open(){ return 0; }
				@Override public void reset( int slot ){}
				@Override public void save( int slot, float[] into ){}
				@Override public void load( int slot, float[] from ){}
				@Override public void forward( int slot, byte[] grid, float[] inventory, float[] hero, float[] out ){
					Arrays.fill( out, 0f );
				}
			} );
			List< Future< ? > > ready = new ArrayList<>();
			for (int i = 0; i < envs; i++){
				Class< ? > slot = Class.forName( BenchSlot.class.getName(), true, new IsolatedLoader( ThroughputBench.class.getClassLoader() ));
				Method init = slot.getMethod( "init", String.class );
				runs[ i ] = slot.getMethod( "run", String.class, int.class );
				String dir = Scratch.dir( "throughputbench" ).getPath();
				Method run = runs[ i ];
				threads[ i ] = Executors.newSingleThreadExecutor( r -> {
					Thread t = new Thread( r, "bench-env" );
					t.setDaemon( true );
					return t;
				} );
				ready.add( threads[ i ].submit( () -> {
					init.invoke( null, dir );
					return run.invoke( null, "BENCH-WARM", 0 );
				} ));
			}
			for (Future< ? > f : ready) f.get();
			InferenceBroker.install( null );
		}

		Measurement round() throws Exception {
			double load = idleLoad();
			List< Future< Long > > out = new ArrayList<>();
			long start = System.nanoTime();
			for (int i = 0; i < envs; i++){
				Method run = runs[ i ];
				int id = i;
				out.add( threads[ i ].submit( () -> {
					long turns = 0;
					for (int e = 0; e < episodes; e++) turns += (Long) run.invoke( null, "BENCH-" + id + "-" + e, e % 4 );
					return turns;
				} ));
			}
			long turns = 0;
			for (Future< Long > f : out) turns += f.get();
			return new Measurement( turns / ( ( System.nanoTime() - start ) / 1e9 ), load );
		}
	}

	private static Measurement inference() throws InterruptedException {
		double load = idleLoad();
		EnvConfig config = new EnvConfig();
		BatchedInference server = new BatchedInference( new Network( config, new Random( 1 )));
		try {
			Thread[] threads = new Thread[ envs ];
			for (int e = 0; e < envs; e++){
				RemoteNetwork net = new RemoteNetwork( config, new Random( 2 ), server, -1 );
				Random rng = new Random( 3 + e );
				float[] grid = new float[ net.gridLength() ];
				for (int i = 0; i < grid.length; i++) if (rng.nextFloat() < 0.014f) grid[ i ] = 1f;
				float[] inventory = new float[ config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT ];
				float[] hero = new float[ HeroEncoder.FEATURES ];
				threads[ e ] = new Thread( () -> {
					for (int s = 0; s < forwards; s++) net.forward( grid, inventory, hero );
				} );
			}
			long start = System.nanoTime();
			for (Thread t : threads) t.start();
			for (Thread t : threads) t.join();
			return new Measurement( (double) envs * forwards / ( ( System.nanoTime() - start ) / 1e9 ), load );
		} finally {
			server.close();
		}
	}

	private static Measurement update(){
		double load = idleLoad();
		EnvConfig config = new EnvConfig();
		PPO ppo = new PPO( config, new Random( 3 ));
		try {
			ppo.useDevice( "auto" );
			ppo.threads = 8;
			ppo.epochs = 4;
			ppo.minibatchSize = 8;
			ppo.chunkLength = 16;
			List< Transition > batch = ParallelFixtures.batch( ppo.network, config, samples, 4242L );
			for (int i = 0; i < batch.size(); i++){
				batch.get( i ).episodeId = i / 64;
				batch.get( i ).stepIndex = i;
			}
			Random rng = new Random( 9 );
			for (Transition t : batch){
				float[] grid = new float[ t.gridCells ];
				for (int c = 0; c < grid.length; c++) if (rng.nextFloat() < GRID_DENSITY) grid[ c ] = 1f;
				t.packGrid( grid );
			}
			ppo.buffer().addAll( batch );
			ppo.update();
			ppo.buffer().addAll( batch );
			KernelProfile.reset();
			long start = System.nanoTime();
			ppo.update();
			Measurement m = new Measurement( ( System.nanoTime() - start ) / 1e3 / samples / ppo.epochs, load );
			if (Boolean.getBoolean( "spd.clprofile" )){
				System.out.println( "  [WARN] --kernels waits on every launch, so this update time is not comparable; GPU time by kernel:" );
				System.out.print( KernelProfile.report() );
			}
			return m;
		} finally {
			ppo.close();
		}
	}
}
