package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.PPO;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.WorkerPool.WorkerHandle;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The channel between the trainer and its workers: what to send, how to send it, and how the reply
 * comes back.
 *
 * <p>Split out of {@link Trainer} because it is the half that talks to processes rather than to the
 * learning problem. {@code Trainer} decides <em>what</em> work exists - which seed, which hero, how
 * many episodes, when to update - and this decides <em>how</em> a worker hears about it and how the
 * answer is decoded. Keeping them apart means the scheduling logic can be read without a pipe in
 * sight, and the protocol logic can be read without a seed schedule.
 *
 * <p><b>Everything crossing a pipe is here.</b> If a field is added to the frame or an episode's
 * summary, this is the only file that reads it, and {@link TransitionCodec} is the only one that
 * writes it. Two lists of the same seven fields is two chances to disagree, and a disagreement here
 * reads as a plausible number rather than failing.
 *
 * <p><b>Why the worker pool is a field rather than passed around.</b> The pool is the thing being
 * talked to. Handing a caller a {@code WorkerHandle} would let it write to a pipe outside this class,
 * and the ordering between a request and its reply is the whole of what keeps the two processes in
 * step - the watchdog exists precisely because a desynchronised protocol does not crash, it hangs with
 * both processes idle.
 */
public class TrainerWorkers {

	private final EnvConfig config;
	private final PPO ppo;
	private final WorkerPool pool = new WorkerPool();

	/**
	 * How the policy frame is built.
	 *
	 * <p>A callback rather than a method call because the frame is the trainer's to define - it carries
	 * the seed schedule's RNG and the sampling rates, which are trainer state - and this class only
	 * needs to know that something can write one. {@link WorkerPool.ParamWriter} is the same seam, so
	 * the launch handshake and every later push go through one definition of the frame.
	 */
	private WorkerPool.ParamWriter paramWriter = out -> {};

	public TrainerWorkers( EnvConfig config, PPO ppo ){
		this.config = config;
		this.ppo = ppo;
	}

	/** One episode request, resolved before dispatch so workers never touch shared state. */
	public static class Job {
		final WorkerHandle worker;
		final String seed;
		final HeroClass heroClass;
		final boolean wantReplay;

		Job( WorkerHandle worker, String seed, HeroClass heroClass, boolean wantReplay ){
			this.worker = worker;
			this.seed = seed;
			this.heroClass = heroClass;
			this.wantReplay = wantReplay;
		}
	}

	// --------------------------------------------------------------------------- lifecycle

	/**
	 * Starts the worker JVMs and hands each the current policy.
	 *
	 * <p>The handshake is per worker and completes before the next one starts: a worker that cannot be
	 * reached is a configuration error, and finding out at generation 40 with 20 live processes is far
	 * worse than finding out at launch.
	 */
	public void launch( int count, String javaHome, String classpath, WorkerPool.ParamWriter params )
			throws IOException {
		this.paramWriter = params;
		pool.launch( count, javaHome, classpath, params );
	}

	public void shutdown(){
		pool.shutdown();
	}

	public int size(){
		return pool.size();
	}

	/** The live worker handles, so a caller can plan a generation against them. */
	public List<WorkerHandle> workers(){
		return pool.workers();
	}

	/** Quiet period before the watchdog declares a stall. */
	public void stallTimeoutMs( long ms ){
		pool.stallTimeoutMs( ms );
	}

	public long stallTimeoutMs(){
		return pool.stallTimeoutMs();
	}

	public void startWatchdog(){
		pool.startWatchdog();
	}

	/** A new job for one worker. Public because the trainer decides the schedule and this does not. */
	public Job job( WorkerHandle worker, String seed, HeroClass heroClass, boolean wantReplay ){
		return new Job( worker, seed, heroClass, wantReplay );
	}

	// --------------------------------------------------------------------------- episodes

	/**
	 * Runs every job at once, one thread per worker.
	 *
	 * This was the difference between a full machine and one core. The previous loop asked each
	 * worker for its whole share in turn, so with eight workers seven of them were blocked on a
	 * pipe read while one simulated - the pool bought process isolation and nothing else, and the
	 * measured throughput was indistinguishable from a single worker.
	 *
	 * One thread per worker rather than a shared pool: each blocks on its own pipe for the whole
	 * episode, so the tasks are I/O-bound in the sense that matters here and there is nothing to
	 * gain from more threads than workers. Joining on all of them is what bounds the generation.
	 */
	public List<Episode> dispatch( List<Job> jobs ) throws IOException {
		Map<WorkerHandle, List<Job>> byWorker = new LinkedHashMap<>();
		for (Job job : jobs){
			byWorker.computeIfAbsent( job.worker, k -> new ArrayList<>() ).add( job );
		}

		List<Thread> threads = new ArrayList<>();
		List<List<Episode>> results = new ArrayList<>();
		List<IOException> failures = Collections.synchronizedList( new ArrayList<>() );

		for (Map.Entry<WorkerHandle, List<Job>> entry : byWorker.entrySet()){
			final WorkerHandle worker = entry.getKey();
			final List<Job> mine = entry.getValue();
			final List<Episode> collected = new ArrayList<>();

			Thread thread = new Thread( () -> {
				try {
					collected.addAll( runOn( worker, mine ) );
				} catch (IOException e){
					failures.add( e );
				}
			}, "dispatch-" + worker.name );

			threads.add( thread );
			results.add( collected );
			thread.start();
		}

		//join, rather than poll on a timer: nothing here needs a cadence, and a fixed sleep would
		//only add latency proportional to however often it fired
		InterruptedException interrupted = null;
		for (Thread thread : threads){
			try {
				thread.join();
			} catch (InterruptedException e){
				interrupted = e;
			}
		}
		if (interrupted != null) Thread.currentThread().interrupt();

		if (!failures.isEmpty()) throw failures.get( 0 );

		List<Episode> episodes = new ArrayList<>();
		for (List<Episode> collected : results) episodes.addAll( collected );
		return episodes;
	}

	/** Runs one worker's share of a generation. Called only from a dispatch thread. */
	private List<Episode> runOn( WorkerHandle worker, List<Job> jobs ) throws IOException {
		List<Episode> episodes = new ArrayList<>();

		DataOutputStream out = worker.dataOut;
		DataInputStream in = worker.dataIn;

		for (Job job : jobs){
			out.writeInt( Protocol.MSG_EPISODE );
			out.writeUTF( job.seed );
			out.writeUTF( job.heroClass.name() );
			out.writeBoolean( job.wantReplay );
			out.flush();

			int reply = in.readInt();
			if (reply != Protocol.MSG_EPISODE){
				throw new IOException( worker.name + " replied to an episode request with message "
						+ reply );
			}

			Episode episode = Episode.read( in );
			episode.seed = job.seed;
			episode.heroClass = job.heroClass.name();
			episode.workerName = worker.name;

			int steps = in.readInt();
			//the worker sends the step count always but the body only when a replay was requested,
			//so the body must only be read in that case. Reading it unconditionally deadlocked the
			//trainer against a worker that was already waiting for its next command.
			if (job.wantReplay) episode.replay = readReplay( in, steps );

			//the sampled transitions, decoded into this thread's own list and merged on the trainer
			//thread once every worker has joined. Nothing shared is touched from here.
			episode.transitions = Episode.readTransitions( in, config, ppo.network.stateSize() );

			//only after the whole frame has been consumed, so the watchdog sees progress rather
			//than a thread that has merely started reading
			pool.progress();

			episodes.add( episode );
		}

		return episodes;
	}

	private Replay readReplay( DataInputStream in, int steps ) throws IOException {
		Replay replay = new Replay();
		replay.seedText = in.readUTF();
		replay.heroClass = in.readUTF();
		replay.score = in.readDouble();
		replay.depth = in.readInt();
		replay.turns = in.readInt();

		for (int i = 0; i < steps; i++){
			Replay.Step step = new Replay.Step();
			step.action = in.readUTF();
			step.slot = in.readInt();
			step.mode = in.readUTF();
			step.heroPos = in.readInt();
			replay.steps.add( step );
		}
		return replay;
	}

	// --------------------------------------------------------------------------- policy push

	/**
	 * Pushes the updated policy to every worker.
	 *
	 * Sent as a full MSG_PARAMS frame, not a bare weight blob, and acknowledged. The two problems
	 * that fixes are worth naming because both were silent: the frame went out on the process's raw
	 * output stream while the episode path used a buffer over the same pipe, so buffered bytes and
	 * raw bytes could interleave out of order; and the blob carried no message header, so the
	 * worker parsed the first four bytes of a float as a message type and died with
	 * "unexpected message". The ack also means a push that a worker failed to absorb stops the run
	 * here instead of at some later, unrelated read.
	 */
	public void pushWeights() throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		DataOutputStream tmp = new DataOutputStream( buffer );
		paramWriter.write( tmp );
		tmp.flush();
		final byte[] payload = buffer.toByteArray();

		//in parallel. A policy is ~14MB of floats, so pushing it to twenty workers one after
		//another means every worker sits at zero CPU for the whole barrier while the trainer
		//shuttles 280MB down pipes. Each worker owns its own pipe, so all of them can take it at
		//once and the barrier collapses to the slowest single transfer.
		List<Thread> threads = new ArrayList<>();
		List<IOException> failures = Collections.synchronizedList( new ArrayList<>() );

		for (final WorkerHandle worker : pool.workers()){
			Thread thread = new Thread( () -> {
				try {
					worker.dataOut.write( payload );
					worker.dataOut.flush();
					int reply = worker.dataIn.readInt();
					if (reply != Protocol.MSG_PARAMS){
						throw new IOException( worker.name + " rejected a policy push, replied with "
								+ "message " + reply );
					}
				} catch (IOException e){
					failures.add( e );
				}
			}, "push-" + worker.name );
			threads.add( thread );
			thread.start();
		}

		InterruptedException interrupted = null;
		for (Thread thread : threads){
			try {
				thread.join();
			} catch (InterruptedException e){
				interrupted = e;
			}
		}
		if (interrupted != null) Thread.currentThread().interrupt();
		if (!failures.isEmpty()) throw failures.get( 0 );

		pool.progress();
	}
}