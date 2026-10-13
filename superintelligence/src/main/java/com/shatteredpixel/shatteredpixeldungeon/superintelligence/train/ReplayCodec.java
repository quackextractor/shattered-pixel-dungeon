package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The worker's replay wire format: one encoder, one decoder, both here.
 *
 * <p><b>This class exists because the format was written twice and drifted.</b> {@code Worker.writeReplay}
 * and {@code TrainerWorkers.readReplay} were separate methods on two sides of a pipe, and the writer
 * sent four step fields where the reader — and the {@link Replay} it rebuilds — have nine. The five it
 * dropped ({@code reward}, {@code quickslots}, {@code heroHp}, {@code turn}, {@code inventory}) were not
 * zeroed: they kept the sentinel values {@link Replay.Step} declares for *not recorded*, which every
 * reader treats as "skip this comparison".
 *
 * <p>So it did not crash, and it did not report a divergence. **Every replay the trainer produced
 * verified on position alone.** Measured on a run that had genuinely reached depth 2: 655 steps, no
 * divergence reported, and a file that could not have detected a starving hero or a one-turn drift —
 * both of which {@link com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder}
 * records health and turn specifically to catch, and says so in its own comment.
 *
 * <p>A pipe format spread across two classes is two chances to disagree, and a disagreement here reads
 * as a plausible number rather than failing. The encoder and decoder are therefore one change here
 * rather than two coordinated ones, and {@link #VERSION} makes the format explicit so that a worker and
 * a trainer built from different sources refuse to talk rather than exchanging plausible garbage.
 *
 * <p><b>Still a third implementation of "write a replay",</b> alongside {@code ReplayIO.write} for the
 * text format and the recorder itself for collection. That duplication is what
 * {@code PLAN-replay-parity.md} §3 names, and it is not resolved here — but it is now one class rather
 * than two methods, which is the difference between a format and a convention, and it is public so
 * that {@code diag.ReplayCodecCheck} can test it. A wire format that cannot be reached from outside its
 * own package is a wire format that cannot be tested.
 */
public final class ReplayCodec {

	private ReplayCodec() {}

	/**
	 * Wire format version, written as the first field of every replay frame.
	 *
* <p>Not decoration. {@code 1} is the four-field format above, which is only readable by a trainer
	 * built from the same source as the worker that wrote it; {@code 2} is all nine step fields;
	 * {@code 3} adds the reward profile to the header, without which a recording written under a profile
	 * was written out saying it was recorded under the default one. A
	 * trainer that meets a version it does not know refuses the frame, because the alternative — reading
	 * a body whose length does not match the reader's idea of it — is a desynchronised pipe, and
	 * {@link WorkerPool}'s stall watchdog reports that as "the workers hung" several minutes later.
	 */
	public static final int VERSION = 3;

	/** The version whose body is the four-field form, kept so {@link #read} can name what it saw. */
	public static final int LEGACY_4_FIELD_VERSION = 1;

	/**
	 * Writes a whole replay.
	 *
	 * <p>The header carries everything that decides whether the body can be interpreted at all: the slot
	 * width and the equipping rule, because a recorded slot index means nothing without them, and the
	 * turn cap, because it decides where the recording stops being faithful.
	 */
	public static void write( DataOutputStream out, Replay replay ) throws IOException {
		out.writeInt( VERSION );
		out.writeUTF( replay.seedText );
		out.writeUTF( replay.heroClass );
		out.writeInt( replay.challenges );
		out.writeInt( replay.generation );
		out.writeDouble( replay.score );
		out.writeInt( replay.depth );
		out.writeInt( replay.turns );
		out.writeInt( replay.turnLimitPerFloor );
		out.writeInt( replay.maxSlots );
		out.writeBoolean( replay.allowEquipping );
		out.writeUTF( replay.termination );
		out.writeUTF( replay.rewardProfile.key() );

		out.writeInt( replay.steps.size() );
		for (Replay.Step step : replay.steps){
			out.writeUTF( step.action );
			out.writeInt( step.slot );
			out.writeUTF( step.mode );
			out.writeInt( step.heroPos );
			out.writeDouble( step.reward );
			out.writeUTF( step.quickslots );
			out.writeInt( step.heroHp );
			out.writeFloat( step.turn );
			out.writeUTF( step.inventory );
		}
	}

	/**
	 * Reads a replay written by {@link #write}.
	 *
	 * @param steps the step count the caller already read off the frame, asserted here rather than
	 *               re-read, because the frame carries it separately for workers that send no body
	 * @throws IOException on a version this build does not speak, naming both
	 */
	public static Replay read( DataInputStream in, int steps ) throws IOException {
		int version = in.readInt();
		if (version != VERSION ){
			throw new IOException( "replay wire version " + version + " is not version " + VERSION
					+ ". The worker and the trainer are built from different sources; the "
					+ (version == LEGACY_4_FIELD_VERSION
							? "four-field body has no health, turn or inventory and every comparison"
								+ " but position would be skipped"
							: "body length does not match what this reader expects" )
					+ ", so refusing is the only safe answer." );
		}

		Replay replay = new Replay();
		replay.seedText = in.readUTF();
		replay.heroClass = in.readUTF();
		replay.challenges = in.readInt();
		replay.generation = in.readInt();
		replay.score = in.readDouble();
		replay.depth = in.readInt();
		replay.turns = in.readInt();
		replay.turnLimitPerFloor = in.readInt();
		replay.maxSlots = in.readInt();
		replay.allowEquipping = in.readBoolean();
		replay.termination = in.readUTF();
		replay.rewardProfile =
				com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile
						.parse( in.readUTF() );

		int declared = in.readInt();
		if (declared != steps ){
			throw new IOException( "replay declares " + declared + " steps but the frame announced "
					+ steps + "; the pipe is out of step." );
		}

		replay.steps.clear();
		for (int i = 0; i < declared; i++){
			Replay.Step step = new Replay.Step();
			step.action = in.readUTF();
			step.slot = in.readInt();
			step.mode = in.readUTF();
			step.heroPos = in.readInt();
			step.reward = in.readDouble();
			step.quickslots = in.readUTF();
			step.heroHp = in.readInt();
			step.turn = in.readFloat();
			step.inventory = in.readUTF();
			replay.steps.add( step );
		}
		replay.declaredSteps = declared;
		return replay;
	}
}
