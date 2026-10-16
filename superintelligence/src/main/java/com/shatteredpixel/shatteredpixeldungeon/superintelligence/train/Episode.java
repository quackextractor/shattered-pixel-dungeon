package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;


class Episode {

	String seed = "";
	String heroClass = "WARRIOR";
	double score;
	int depth;
	int turns;
	boolean endedNaturally;
	String reason = "";
	Replay replay;


	List<Transition> transitions;


	double workerCpuTotal = -1;


	double workerHeapMb = -1;


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