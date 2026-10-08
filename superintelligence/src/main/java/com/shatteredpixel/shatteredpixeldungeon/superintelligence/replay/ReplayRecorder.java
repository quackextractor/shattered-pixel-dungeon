package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
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
		begin( seedText, heroClass, challenges, turnLimitPerFloor, null );
	}

	/**
	 * Prepared for a new episode, recording the settings that change what a slot index means.
	 *
	 * <p>A null config records EnvConfig's defaults, which is what the fixture gates want - they build
	 * the env and the replay from the same defaults, so the difference cannot arise. A real rollout
	 * passes its own config, because a recording that cannot say how wide the slot head was cannot be
	 * re-executed faithfully.
	 */
	public void begin( String seedText, String heroClass, int challenges, int turnLimitPerFloor,
			EnvConfig config ){
		replay.reset();
		replay.seedText = seedText == null ? "" : seedText;
		replay.heroClass = heroClass;
		replay.challenges = challenges;
		replay.turnLimitPerFloor = turnLimitPerFloor;
		if (config != null){
			replay.maxSlots = config.maxSlots;
			replay.allowEquipping = config.allowEquipping;
		}
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
		//Captured here, before the action runs, because that is when the slot index was chosen. An equip
		//or unequip in this very step can rebind a slot, so afterwards would describe a different world.
		if (mode == EnvMode.SLOT || mode == EnvMode.INVENTORY){
			replay.steps.get( replay.length() - 1 ).quickslots = Quickslots.capture();
		}
	}

	/** Fills in the post-step state of the step just recorded. */
	public void afterStep( int heroPosition, float reward ){
		if (!recording) return;
		int last = replay.length() - 1;
		if (last < 0) return;
		Replay.Step step = replay.steps.get( last );
		step.heroPos = heroPosition;
		step.reward = reward;
		//Recorded so that replaying a run is checked against something other than a re-run of the same
		//code. Position alone cannot see a hero who is starving, or one turn adrift of the recording, and
		//both of those played back cleanly while being wrong.
		step.heroHp = Dungeon.hero.HP;
		step.turn = Actor.now();
		step.inventory = inventoryOf();
	}

	/** Inventory as {@code ItemClass:count}, sorted, so the comparison is order-independent. */
	public static String inventory(){
		java.util.TreeMap< String, Integer > counts = new java.util.TreeMap<>();
		for (com.shatteredpixel.shatteredpixeldungeon.items.Item item : Dungeon.hero.belongings.backpack){
			counts.merge( item.getClass().getSimpleName(), 1, Integer::sum );
		}
		StringBuilder sb = new StringBuilder();
		for (java.util.Map.Entry< String, Integer > e : counts.entrySet()){
			if (sb.length() > 0) sb.append( ',' );
			sb.append( e.getKey() ).append( ':' ).append( e.getValue() );
		}
		return sb.toString();
	}

	private static String inventoryOf(){
		return inventory();
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