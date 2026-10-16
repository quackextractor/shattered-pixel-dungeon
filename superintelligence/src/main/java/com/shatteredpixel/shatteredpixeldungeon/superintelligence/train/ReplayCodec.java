package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Replay;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class ReplayCodec {

	private ReplayCodec() {}

	public static final int VERSION = 4;

	public static final int LEGACY_4_FIELD_VERSION = 1;

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
			out.writeLong( step.baseDraws );
			out.writeLong( step.baseFingerprint );
		}
	}

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
			step.baseDraws = in.readLong();
			step.baseFingerprint = in.readLong();
			replay.steps.add( step );
		}
		replay.declaredSteps = declared;
		return replay;
	}
}
