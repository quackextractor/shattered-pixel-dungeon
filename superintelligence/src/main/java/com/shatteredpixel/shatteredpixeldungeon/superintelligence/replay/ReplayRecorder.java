package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;

/**
 * Records the decisions an agent makes so the run can be replayed later.
 *
 * Recording is a straight pass-through from the env's step loop, with no buffering: the agent has
 * already made its decision by the time step() returns, so the recorder only has to remember it.
 */
public class ReplayRecorder {

	private final Replay replay = new Replay();
	private boolean recording;

	public ReplayRecorder() {}

/** Prepared for a new episode. */
	public void begin( String seedText, String heroClass, int challenges, int turnLimitPerFloor ){
		replay.reset();
		replay.seedText = seedText == null ? "" : seedText;
		replay.heroClass = heroClass;
		replay.challenges = challenges;
		replay.turnLimitPerFloor = turnLimitPerFloor;
		recording = true;
	}

	/**
	 * Records one decision.
	 *
	 * Called before {@link SPDEnv#step}, so the recorded mode is the one the agent chose under. The
	 * hero position is filled in afterwards by {@link #afterStep}, because a replay is verified by
	 * comparing where the hero ended up.
	 */
	public void record( Action action, int slot, EnvMode mode ){
		if (!recording) return;
		replay.add( action.name(), slot, mode.name(), -1, 0 );
	}

	/** Fills in the post-step state of the step just recorded. */
	public void afterStep( int heroPosition, float reward ){
		if (!recording) return;
		int last = replay.length() - 1;
		if (last < 0) return;
		replay.steps.get( last ).heroPos = heroPosition;
		replay.steps.get( last ).reward = reward;
	}

	/** Closes the recording and stores the run's outcome. */
	public void end( double score, int depth, int turns, int generation ){
		replay.captureOutcome( score, depth, turns, generation );
		recording = false;
	}

	public Replay replay(){
		return replay;
	}

	public boolean recording(){
		return recording;
	}

}