package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class WorkerPool {

	private volatile long lastProgressNanos = System.nanoTime();

	private long stallTimeoutMs = 180_000;

	private volatile Runnable beforeHalt = () -> {};

	private final List<WorkerHandle> workers = new ArrayList<>();

	public interface ParamWriter {
		void write( DataOutputStream out ) throws IOException;
	}

	public int size(){
		return workers.size();
	}

	public List<WorkerHandle> workers(){
		return workers;
	}


	private static File workerDir(){
		return Trainer.outDirDefault();
	}


	public static int bufferBytes = 1 << 20;


	public static String heapMin = "256m";
	public static String heapMax = "1536m";
	public static int gcPauseMs = 200;


	public void launch( int count, String javaHome, String classpath, ParamWriter params )
			throws IOException {

		String java = (javaHome == null || javaHome.isEmpty())
				? join( System.getProperty( "java.home" ), "bin", "java" )
				: join( javaHome, "bin", "java" );

		for (int i = 0; i < count; i++){
			WorkerHandle handle = new WorkerHandle( java, classpath, "worker" + i,
					heapMin, heapMax, gcPauseMs );

			DataOutputStream out = handle.dataOut;
			out.writeInt( Protocol.MSG_HELLO );
			out.writeInt( Protocol.VERSION );
			params.write( out );
			out.flush();

			DataInputStream reply = handle.dataIn;
			if (reply.readInt() != Protocol.MSG_HELLO || reply.readInt() != Protocol.VERSION){
				throw new IOException( "worker " + i + " protocol mismatch" );
			}
			if (reply.readInt() != Protocol.MSG_PARAMS){
				throw new IOException( "worker " + i + " did not acknowledge the policy push" );
			}

			workers.add( handle );
		}

		System.out.println( Ansi.wrap( "[OK]", Ansi.GREEN ) + "   started " + workers.size()
				+ " worker processes" );
	}

	public void progress(){
		lastProgressNanos = System.nanoTime();
}

	public void stallTimeoutMs( long ms ){
		stallTimeoutMs = ms;
	}

	public long stallTimeoutMs(){
		return stallTimeoutMs;
	}

	public void beforeHalt( Runnable action ){
		beforeHalt = action;
	}

	public void startWatchdog(){
		Thread watchdog = new Thread( () -> {
			while (true){
				try {
					Thread.sleep( 1000 );
				} catch (InterruptedException e){
					return;
				}

				long quietMs = (System.nanoTime() - lastProgressNanos) / 1_000_000;
				if (quietMs > stallTimeoutMs) onStall( quietMs );
			}
		}, "watchdog" );
		watchdog.setDaemon( true );
		watchdog.start();
	}

	private void onStall( long quietMs ){
		System.err.println( "[ERROR] no worker progress for " + (quietMs / 1000)
				+ "s. A worker and the trainer are most likely waiting on each"
				+ " other over a desynchronised protocol." );
		for (WorkerHandle worker : workers){
			System.err.println( "  worker pid " + worker.process.pid()
					+ " alive=" + worker.process.isAlive() );
		}

		for (Thread t : Thread.getAllStackTraces().keySet()){
			if (t.getName().startsWith( "dispatch-" )){
				System.err.println( "  " + t.getName() + " " + t.getState() );
				for (StackTraceElement el : t.getStackTrace()){
					System.err.println( "      at " + el );
				}
			}
		}

		try {
			beforeHalt.run();
		} catch (RuntimeException e){
			System.err.println( "[ERROR] saving before the halt failed: " + e );
		}
		Runtime.getRuntime().halt( 3 );
	}

	public void shutdown(){
		for (WorkerHandle worker : workers){
			try {
				worker.process.destroy();
			} catch (RuntimeException e){

			}
		}
		workers.clear();
	}

	private static String join( String... parts ){
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.length; i++){
			if (i > 0) sb.append( File.separatorChar );
			sb.append( parts[ i ] );
		}
		return sb.toString();
	}

public static class WorkerHandle {

		final Process process;
	final DataInputStream dataIn;
	final DataOutputStream dataOut;
	final String name;

		WorkerHandle( String java, String classpath, String name,
				String heapMin, String heapMax, int gcPauseMs ) throws IOException {
			this.name = name;

			ProcessBuilder pb = new ProcessBuilder(
					java,
					"-Xms" + heapMin, "-Xmx" + heapMax,
					"-XX:+UseParallelGC", "-XX:MaxGCPauseMillis=" + gcPauseMs,

					"--enable-native-access=ALL-UNNAMED",
					"-cp", classpath,
					"com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.WorkerMain",
					"--worker",

					"--work-dir", new File( workerDir(), name ).getAbsolutePath() );

			pb.redirectError( ProcessBuilder.Redirect.INHERIT );

			File dir = new File( workerDir(), name );
			if (!dir.isDirectory() && !dir.mkdirs()){
				throw new IOException( "could not create worker directory " + dir );
			}
			pb.directory( dir );

			this.process = pb.start();
			this.dataOut = new DataOutputStream( new BufferedOutputStream( process.getOutputStream(), bufferBytes ) );
			this.dataIn = new DataInputStream( new BufferedInputStream( process.getInputStream(), bufferBytes ) );
		}
	}
}