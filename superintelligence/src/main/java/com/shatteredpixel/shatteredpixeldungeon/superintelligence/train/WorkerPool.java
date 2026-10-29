package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ResourceStats;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
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

	public static boolean inProcess = false;


	public void launch( int count, String javaHome, String classpath, ParamWriter params )
			throws IOException {

		String java = (javaHome == null || javaHome.isEmpty())
				? join( System.getProperty( "java.home" ), "bin", "java" )
				: join( javaHome, "bin", "java" );

		for (int i = 0; i < count; i++){
			WorkerHandle handle = inProcess ? new WorkerHandle( "worker" + i )
					: new WorkerHandle( java, classpath, "worker" + i, heapMin, heapMax, gcPauseMs );

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
				+ ( inProcess ? " in-process workers, one classloader each" : " worker processes" ));
	}

	public void progress(){
		lastProgressNanos = System.nanoTime();
}

	private volatile boolean trainerBusy;

	public void trainerBusy( boolean busy ){
		trainerBusy = busy;
		progress();
	}

	public boolean stalled(){
		return !trainerBusy && (System.nanoTime() - lastProgressNanos) / 1_000_000 > stallTimeoutMs;
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

				if (stalled()) onStall( (System.nanoTime() - lastProgressNanos) / 1_000_000 );
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
			System.err.println( "  " + worker.name + " " + worker.describe() );
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
				worker.stop();
			} catch (RuntimeException | IOException e){

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
	final Thread thread;
	final DataInputStream dataIn;
	final DataOutputStream dataOut;
	final String name;

		WorkerHandle( String name ) throws IOException {
			this.name = name;
			this.process = null;
			File dir = new File( workerDir(), name );
			Pipe toWorker = Pipe.open(), fromWorker = Pipe.open();
			this.dataOut = new DataOutputStream( new BufferedOutputStream( Channels.newOutputStream( toWorker.sink() ), bufferBytes ));
			this.dataIn = new DataInputStream( new BufferedInputStream( Channels.newInputStream( fromWorker.source() ), bufferBytes ));
			InputStream workerIn = Channels.newInputStream( toWorker.source() );
			OutputStream workerOut = Channels.newOutputStream( fromWorker.sink() );
			Method serve;
			try {
				serve = Class.forName( WorkerMain.class.getName(), true, new IsolatedLoader( WorkerPool.class.getClassLoader() ))
						.getMethod( "serveStreams", InputStream.class, OutputStream.class, File.class );
			} catch (ReflectiveOperationException e){
				throw new IOException( "could not load an isolated worker", e );
			}
			this.thread = new Thread( () -> {
				try {
					serve.invoke( null, workerIn, workerOut, dir );
				} catch (ReflectiveOperationException e){
					System.err.println( "[ERROR] " + name + ": " + ( e.getCause() != null ? e.getCause() : e ));
				} finally {
					try {
						workerOut.close();
					} catch (IOException ignored){

					}
				}
			}, name );
			this.thread.setDaemon( true );
			this.thread.start();
		}

		String describe(){
			return process != null ? "pid " + process.pid() + " alive=" + process.isAlive()
					: "thread " + thread.getState();
		}

		void stop() throws IOException {
			if (process != null) process.destroy();
			else dataOut.close();
		}

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

			this.thread = null;
			this.process = pb.start();
			this.dataOut = new DataOutputStream( new BufferedOutputStream( process.getOutputStream(), bufferBytes ) );
			this.dataIn = new DataInputStream( new BufferedInputStream( process.getInputStream(), bufferBytes ) );
		}
	}
}