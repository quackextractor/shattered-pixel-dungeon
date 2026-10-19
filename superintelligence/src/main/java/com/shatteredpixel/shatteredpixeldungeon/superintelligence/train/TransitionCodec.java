package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.HeroEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class TransitionCodec {

	private TransitionCodec() {}

	public static void write( DataOutputStream out, List<Transition> sampled ) throws IOException {
		out.writeInt( Protocol.MSG_TRANSITIONS );
		writeBody( out, sampled );
	}

	public static void writeBody( DataOutputStream out, List<Transition> sampled ) throws IOException {
		out.writeInt( sampled.size() );

		for (Transition t : sampled){
			out.write( t.grid );
			writeFloats( out, t.inventory );
			writeFloats( out, t.hero );
			writeFloats( out, t.actionMask );
			writeFloats( out, t.slotMask );
			writeFloats( out, t.targetMask );

			out.writeInt( t.liveHead );
			out.writeInt( t.episodeId );
			out.writeInt( t.stepIndex );
			out.writeInt( t.actionIndex );
			out.writeInt( t.slotIndex );

			out.writeFloat( t.oldLogProbability );
			out.writeFloat( t.advantage );
			out.writeFloat( t.returnValue );
			out.writeBoolean( t.terminal );

			writeState( out, t.recurrentState );
		}
	}

	private static void writeState( DataOutputStream out, float[] state ) throws IOException {
		if (state == null){
			out.writeInt( 0 );
			return;
		}
		out.writeInt( state.length );
		for (float v : state) out.writeFloat( v );
	}

	private static float[] readState( DataInputStream in, int expected ) throws IOException {
		int length = in.readInt();
		if (length == 0) return null;

		if (length != expected){
			throw new IOException( "the frame carries a recurrent state of " + length
					+ " floats but this network's is " + expected + ". The worker and trainer are"
					+ " built differently." );
		}

		float[] state = new float[ length ];
		for (int i = 0; i < length; i++) state[ i ] = in.readFloat();
		return state;
	}

	public static List<Transition> read( DataInputStream in, EnvConfig config,
			int stateSize ) throws IOException {
		return readBody( in, config, stateSize );
	}

	public static List<Transition> readBody( DataInputStream in, EnvConfig config,
			int stateSize ) throws IOException {
		int count = in.readInt();

int gridSize = config.spatialChannels() * config.gridWidth * config.gridWidth;
		int gridCells = gridSize;
		int packedGrid = com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Transition
				.packedGridBytes( gridCells );
		int inventorySize = config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT;
		int heroSize = HeroEncoder.FEATURES;

		List<Transition> into = new ArrayList<>( count );

		for (int i = 0; i < count; i++){
			Transition t = new Transition();
			t.gridCells = gridCells;
			t.grid = new byte[ packedGrid ];
			t.inventory = new float[ inventorySize ];
			t.hero = new float[ heroSize ];
			t.actionMask = new float[ ActionCount.size ];
			t.slotMask = new float[ config.maxSlots ];
			t.targetMask = new float[ TargetCount.value ];

			readFully( in, t.grid );
			readFloats( in, t.inventory );
			readFloats( in, t.hero );
			readFloats( in, t.actionMask );
			readFloats( in, t.slotMask );
			readFloats( in, t.targetMask );

			t.liveHead = in.readInt();
			t.episodeId = in.readInt();
			t.stepIndex = in.readInt();
			t.actionIndex = in.readInt();
			t.slotIndex = in.readInt();

			t.oldLogProbability = in.readFloat();
			t.advantage = in.readFloat();
			t.returnValue = in.readFloat();
			t.terminal = in.readBoolean();

			t.recurrentState = readState( in, stateSize );

			into.add( t );
		}

		return into;
	}

	private static void writeFloats( DataOutputStream out, float[] values ) throws IOException {
		for (float v : values) out.writeFloat( v );
	}

	private static void readFloats( DataInputStream in, float[] into ) throws IOException {
		for (int i = 0; i < into.length; i++) into[ i ] = in.readFloat();
	}

	private static void readFully( DataInputStream in, byte[] into ) throws IOException {
		in.readFully( into );
	}

	private static final class ActionCount {
		static final int size =
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.size();
	}

	private static final class TargetCount {
		static final int value =
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper.TARGET_COUNT;
	}
}
