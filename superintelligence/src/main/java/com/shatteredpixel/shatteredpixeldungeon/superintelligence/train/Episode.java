package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * The scalar result of one episode, as the worker reports it.
 *
 * The replay is deliberately not part of this: it is a per-step list of UTF strings and is orders of
 * magnitude larger than the summary, so it rides its own frame. See {@link Protocol#MSG_REPLAY}.
 *
 * {@code workerCpuTotal} is cumulative rather than per-episode, and {@code workerName} is what makes
 * it usable: OS process CPU counters have roughly millisecond granularity and an episode here is
 * often only tens of milliseconds, so differencing the counter across each one lost most of the
 * signal and under-reported the pool by about half. A monotonic total lets the trainer difference
 * consecutive reports per worker instead.
 */
class Episode {

	String seed = "";
	String heroClass = "WARRIOR";
	double score;
	int depth;
	int turns;
	boolean endedNaturally;
	String reason = "";
	Replay replay;

	/**
	 * Sampled transitions from this episode, with advantages computed by the worker.
	 *
	 * Empty until the workers return transitions. Roughly 8 steps at the default 5% sample rate on a
	 * 150-turn episode, so the list is short and the observation payloads are what cost.
	 */
	List<Transition> transitions;

	/** Cumulative CPU seconds a worker reported, or -1 if it could not measure. */
	double workerCpuTotal = -1;

	/** Heap the worker was holding at the end of the episode, in MB. */
	double workerHeapMb = -1;

	/** Which worker ran it, so the CPU delta can be attributed per worker. */
	String workerName = "";

	static void write( DataOutputStream out, Episode e ) throws IOException {
		out.writeDouble( e.score );
		out.writeInt( e.depth );
		out.writeInt( e.turns );
		out.writeBoolean( e.endedNaturally );
		out.writeUTF( e.reason );
		out.writeDouble( e.workerCpuTotal );
		out.writeDouble( e.workerHeapMb );
	}

	/**
	 * Reads the message header and its transition frame.
	 *
	 * Split from {@link #read} because the header's scalars are useful to the report even if the
	 * transitions are refused, and because a caller that wants only one of the two should not have to
	 * stream past megabytes of the other to get it.
	 *
	 * @return the sampled transitions, with advantages already computed by the worker
	 */
	static List<Transition> readTransitions( DataInputStream in, EnvConfig config, int stateSize )
			throws IOException {

		int message = in.readInt();
		if (message != Protocol.MSG_TRANSITIONS){
			throw new IOException( "expected a transition frame, got message " + message );
		}

		return TransitionCodec.read( in, config, stateSize );
	}

	static Episode read( DataInputStream in ) throws IOException {
		Episode e = new Episode();
		e.score = in.readDouble();
		e.depth = in.readInt();
		e.turns = in.readInt();
		e.endedNaturally = in.readBoolean();
		e.reason = in.readUTF();
		e.workerCpuTotal = in.readDouble();
		e.workerHeapMb = in.readDouble();
		return e;
	}
}