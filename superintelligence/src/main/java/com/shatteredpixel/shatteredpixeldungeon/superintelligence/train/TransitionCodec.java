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

/**
 * The transition frame: what a worker sends back for an episode.
 *
 * One frame per episode, not per transition, so the trainer's read is a single loop bounded by a
 * count rather than a message header per sample. At ~48.5 KB a step, a frame is a few MB and the
 * worker writes it in one pass.
 *
 * What is in it is chosen by what the update actually needs. The advantage and return arrive
 * computed, so the worker no longer needs to send {@code reward}, {@code value} or {@code nextValue}:
 * the gradient needs an observation, the decision that was taken from it, and how well that decision
 * turned out. Everything else would be 12 bytes a step of pure overhead. The reasoning for
 * {@code oldLogProbability} not travelling with the value is the same as in {@link Transition}: an
 * exact recurrent gradient would need every timestep's activations retained, so the update replays
 * the observation instead.
 */
/**
 * Public so {@code diag.GaeCheck} can round-trip a transition through it.
 *
 * The class is internal to the trainer, but the codec is the one place where a dropped or transposed
 * field would not fail loudly - it would arrive as a plausible advantage and quietly train the policy
 * on noise. That is worth a public entry point for a check, and nothing else should call it.
 */
public final class TransitionCodec {

	private TransitionCodec() {}

	/**
	 * Bytes one sampled transition costs, from the config rather than from a decoded sample.
	 *
	 * Used to size {@code --max-samples} against the memory it will actually take. The report measures
	 * the real figure off what arrived; this is the prediction, and the two agreeing is the point.
	 */
	public static long wireBytesPerStep( EnvConfig config ){
		int grid = config.spatialChannels() * config.gridWidth * config.gridWidth;
		int inventory = config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT;
		int hero = HeroEncoder.FEATURES;

		//grid as bytes, everything else as fp32, plus three ints and three floats and a flag
		return grid
				+ 4L * (inventory + hero + ActionCount.size + config.maxSlots + TargetCount.value)
				+ 4L * 3
				+ 3L * 4
				+ 1L;
	}

	/**
	 * Bytes one sampled transition costs, including its recurrent state.
	 *
	 * <p>The state is 1KB at the default 128-wide LSTM, so it adds about 2% to a step's cost. That is
	 * the price of the update replaying each observation under the state the behaviour policy actually
	 * used, and it is cheap next to being wrong: the alternative cost the same 1KB's worth of gradient
	 * signal.
	 */
	public static long wireBytesPerStep( EnvConfig config, int stateSize ){
		return wireBytesPerStep( config ) + 4L + 4L * stateSize;
	}

	public static void write( DataOutputStream out, List<Transition> sampled ) throws IOException {
		out.writeInt( Protocol.MSG_TRANSITIONS );
		writeBody( out, sampled );
	}

	/**
	 * The frame's contents, without its message header.
	 *
	 * Separate so a check can round-trip a transition without having to write a plausible message
	 * header in front of it, and so the two halves are visibly the same code path.
	 */
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
			out.writeInt( t.actionIndex );
			out.writeInt( t.slotIndex );

			out.writeFloat( t.oldLogProbability );
			out.writeFloat( t.advantage );
			out.writeFloat( t.returnValue );
			out.writeBoolean( t.terminal );

			//the LSTM state this step was decided under. 256 floats, and without it the trainer
			//replays every observation under the wrong hidden state - see Transition.recurrentState
			writeState( out, t.recurrentState );
		}
	}

	/** Writes a recurrent state, or a zero length if there is none. */
	private static void writeState( DataOutputStream out, float[] state ) throws IOException {
		if (state == null){
			out.writeInt( 0 );
			return;
		}
		out.writeInt( state.length );
		for (float v : state) out.writeFloat( v );
	}

	/** Reads a recurrent state, or returns {@code null} for a zero length. */
	private static float[] readState( DataInputStream in, int expected ) throws IOException {
		int length = in.readInt();
		if (length == 0) return null;

		//bounded against what the network could hold, so a corrupt length cannot allocate its way to
		//an OutOfMemoryError - which on a 2GB-pagefile machine takes the run with it
		if (length != expected){
			throw new IOException( "the frame carries a recurrent state of " + length
					+ " floats but this network's is " + expected + ". The worker and trainer are"
					+ " built differently." );
		}

		float[] state = new float[ length ];
		for (int i = 0; i < length; i++) state[ i ] = in.readFloat();
		return state;
	}

	/**
	 * Reads a transition frame into fresh transitions, appended to {@code into}.
	 *
	 * Deliberately not {@link Transition#take}: pooled objects are recycled across generations, so a
	 * decode that missed a field would silently read the previous generation's value instead of
	 * failing. This buffer is large and cold - 2,400 steps is ~118 MB - which is precisely the case
	 * the pool is wrong for.
	 */
	public static List<Transition> read( DataInputStream in, EnvConfig config,
			int stateSize ) throws IOException {
		return readBody( in, config, stateSize );
	}

	/**
	 * The frame's contents, without its message header. See {@link #writeBody}.
	 *
	 * @param stateSize floats the LSTM state must be, so a frame from a differently sized worker is
	 *                  refused rather than read into a network it does not belong to
	 */
	public static List<Transition> readBody( DataInputStream in, EnvConfig config,
			int stateSize ) throws IOException {
		int count = in.readInt();

		int gridSize = config.spatialChannels() * config.gridWidth * config.gridWidth;
		int inventorySize = config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT;
		int heroSize = HeroEncoder.FEATURES;

		//one array rather than count empty transitions, so a generation's buffer is 8k pointers
		List<Transition> into = new ArrayList<>( count );

		for (int i = 0; i < count; i++){
			Transition t = new Transition();
			t.grid = new byte[ gridSize ];
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

	/**
	 * Sizes the wire format's fixed widths.
	 *
	 * Read from the real classes rather than restated, because a hardcoded action or target count
	 * here would desynchronise the codec from the network the trainer will build against - and a
	 * desync here reads a plausible number rather than throwing.
	 */
	private static final class ActionCount {
		static final int size =
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.size();
	}

	private static final class TargetCount {
		static final int value =
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper.TARGET_COUNT;
	}
}