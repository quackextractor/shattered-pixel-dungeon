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

/**
 * Owns the worker processes: launching them, the pipes to them, and the stall watchdog.
 *
 * Split out of {@link Trainer} because process lifetime is a separate concern from the learning loop,
 * and because the watchdog is the one piece of the trainer that touches threads it does not own.
 *
 * Workers speak a length-prefixed binary protocol on stdin/stdout; their human-readable log goes to
 * stderr, inherited, so a stray print to stdout cannot desynchronise the stream.
 */
public class WorkerPool {

	/** Bumped by the trainer whenever a worker message completes. Watched by the watchdog. */
	private volatile long lastProgressNanos = System.nanoTime();

	/** Quiet period before the watchdog declares a stall. */
	private long stallTimeoutMs = 180_000;

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

	/** Root of the per-worker scratch directories. */
	private static File workerDir(){
		return new File( System.getProperty( "java.io.tmpdir" ), "spd-train" );
	}

	/**
	 * Starts {@code count} worker JVMs and hands each the current policy.
	 *
	 * The handshake is per worker and must complete before the next one starts: a worker that cannot
	 * be reached is a configuration error, and finding out at generation 40 with 20 live processes is
	 * far worse than finding out at launch.
	 */
	public void launch( int count, String javaHome, String classpath, ParamWriter params )
			throws IOException {

		String java = (javaHome == null || javaHome.isEmpty())
				? join( System.getProperty( "java.home" ), "bin", "java" )
				: join( javaHome, "bin", "java" );

		for (int i = 0; i < count; i++){
			WorkerHandle handle = new WorkerHandle( java, classpath, "worker" + i );

			//nothing to close here: handle.dataOut is a buffer over this process's stdin pipe and
			//stays open for the life of the worker. Closing the raw stream first left the buffered
			//writer writing into a closed pipe, so the handshake below failed immediately.
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

	/** Called from the dispatch threads whenever a worker message completes. */
	public void progress(){
		lastProgressNanos = System.nanoTime();
}

	/** Quiet period before the watchdog declares a stall. */
	public void stallTimeoutMs( long ms ){
		stallTimeoutMs = ms;
	}

	public long stallTimeoutMs(){
		return stallTimeoutMs;
	}

	/**
	 * Fails the run if a worker goes quiet for too long.
	 *
	 * A desynchronised protocol does not crash: the trainer blocks reading a pipe the worker will
	 * never write to, both processes sit at zero CPU, and the run looks like it is still working.
	 * That is the worst possible failure mode for a job that is meant to take hours, so a stalled
	 * worker is turned into a loud error with a stack dump rather than silence.
	 *
	 * This is the one timer in the trainer. It costs nothing - it checks a volatile long once a
	 * second - and it exists precisely so that nothing else has to.
	 */
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

		//a thread dump of the trainer is the only useful diagnostic here, and the blocked frames are
		//exactly where the protocol went wrong
		for (Thread t : Thread.getAllStackTraces().keySet()){
			if (t.getName().startsWith( "dispatch-" )){
				System.err.println( "  " + t.getName() + " " + t.getState() );
				for (StackTraceElement el : t.getStackTrace()){
					System.err.println( "      at " + el );
				}
			}
		}

		Runtime.getRuntime().halt( 3 );
	}

	public void shutdown(){
		for (WorkerHandle worker : workers){
			try {
				worker.process.destroy();
			} catch (RuntimeException e){
				//a worker that already exited is fine
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

	/**
	 * One worker process and the pipes to it.
	 *
	 * The worker reads its commands from stdin and writes its results to stdout. Worker stderr is
	 * inherited so its warnings land in the trainer's log.
	 *
	 * The stream buffers are sized for the frames that actually cross them rather than left at the
	 * 8 KB default. A policy push is ~14 MB and a generation's transitions are tens of MB; the
	 * default turns those into thousands of syscalls per worker per generation.
	 */
	public static class WorkerHandle {

		private static final int BUFFER = 1 << 20;

		final Process process;
		final DataInputStream dataIn;
		final DataOutputStream dataOut;
		final String name;

		WorkerHandle( String java, String classpath, String name ) throws IOException {
			this.name = name;

			ProcessBuilder pb = new ProcessBuilder(
					java,
					"-Xms256m", "-Xmx1536m",
					"-XX:+UseParallelGC", "-XX:MaxGCPauseMillis=200",
					"-cp", classpath,
					"com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.WorkerMain",
					"--worker",
					//its own save directory, so workers cannot collide on one temp file
					"--work-dir", new File( workerDir(), name ).getAbsolutePath() );

			pb.redirectError( ProcessBuilder.Redirect.INHERIT );

			//ProcessBuilder resolves its working directory before the process starts, so the worker
			//cannot create its own - it fails with "The directory name is invalid" instead
			File dir = new File( workerDir(), name );
			if (!dir.isDirectory() && !dir.mkdirs()){
				throw new IOException( "could not create worker directory " + dir );
			}
			pb.directory( dir );

			this.process = pb.start();
			this.dataOut = new DataOutputStream( new BufferedOutputStream( process.getOutputStream(), BUFFER ) );
			this.dataIn = new DataInputStream( new BufferedInputStream( process.getInputStream(), BUFFER ) );
		}
	}
}