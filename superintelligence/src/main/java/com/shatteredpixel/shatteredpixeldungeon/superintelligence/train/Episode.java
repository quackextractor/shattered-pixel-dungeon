package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.StallCause;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;


public class Episode {



	public static final int TERM_COUNT = RewardTerm.count();

	String seed = "";
	String heroClass = "WARRIOR";
	public double score;
	public int depth;
	public int turns;
	public boolean endedNaturally;
	public String reason = "";
	public int stallCause = StallCause.NONE.ordinal();
	public double objectiveScore;
	public final double[] termTotals = new double[ TERM_COUNT ];
	public final int[] termCounts = new int[ TERM_COUNT ];
	public Replay replay;


	List<Transition> transitions;


	double workerCpuTotal = -1;


	double workerHeapMb = -1;


	String workerName = "";

	public static void write( DataOutputStream out, Episode e ) throws IOException {
		out.writeDouble( e.score );
		out.writeInt( e.depth );
		out.writeInt( e.turns );
		out.writeBoolean( e.endedNaturally );
		out.writeUTF( e.reason );
		out.writeDouble( e.workerCpuTotal );
		out.writeDouble( e.workerHeapMb );

		out.writeInt( e.stallCause );
		out.writeDouble( e.objectiveScore );
		for (double total : e.termTotals) out.writeDouble( total );
		for (int count : e.termCounts) out.writeInt( count );
	}


	static List<Transition> readTransitions( DataInputStream in, EnvConfig config, int stateSize )
			throws IOException {

		int message = in.readInt();
		if (message != Protocol.MSG_TRANSITIONS){
			throw new IOException( "expected a transition frame, got message " + message );
		}

		return TransitionCodec.read( in, config, stateSize );
	}

	public static Episode read( DataInputStream in ) throws IOException {
		Episode e = new Episode();
		e.score = in.readDouble();
		e.depth = in.readInt();
		e.turns = in.readInt();
		e.endedNaturally = in.readBoolean();
		e.reason = in.readUTF();
		e.workerCpuTotal = in.readDouble();
		e.workerHeapMb = in.readDouble();

		e.stallCause = in.readInt();
		e.objectiveScore = in.readDouble();
		if (e.stallCause < 0 || e.stallCause >= StallCause.values().length){
			throw new IOException( "a worker reported stall cause " + e.stallCause + ", which this"
					+ " build does not know; the trainer and its workers are probably from different"
					+ " versions" );
		}
		for (int i = 0; i < TERM_COUNT; i++) e.termTotals[ i ] = in.readDouble();
		for (int i = 0; i < TERM_COUNT; i++) e.termCounts[ i ] = in.readInt();
		return e;
	}
}