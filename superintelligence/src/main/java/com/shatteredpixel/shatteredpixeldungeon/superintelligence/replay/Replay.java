package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

public class Replay {

	public static final String MAGIC = "SPD-REPLAY";

	public static final int VERSION = 5;

	public String seedText = "";
	public String heroClass = "WARRIOR";
	public int challenges = 0;

	public double score;

	public int depth;

	public int turns;

	public int generation;

	public int turnLimitPerFloor = 1500;

	public int maxSlots = 32;

	public boolean allowEquipping = true;

	public com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile rewardProfile
			= com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile.DEFAULT;

	public int declaredSteps;

	public int truncatedAt = -1;

	public final java.util.ArrayList<Step> steps = new java.util.ArrayList<>();

	public static class Step {

		public String action;

		public int slot;

		public String mode;

		public int heroPos;

		public double reward;

		public String quickslots = "";

		public int heroHp = -1;

		public float turn = -1f;

		public String inventory = "";

		public long baseDraws = -1;

		public long baseFingerprint = 0;

		public int accepted = -1;

		public Step( String action, int slot, String mode, int heroPos, double reward ){
			this.action = action;
			this.slot = slot;
			this.mode = mode;
			this.heroPos = heroPos;
			this.reward = reward;
		}

		public Step() {}
	}

	public void add( String action, int slot, String mode, int heroPos, double reward ){
		steps.add( new Step( action, slot, mode, heroPos, reward ) );
	}

	public int length(){
		return steps.size();
	}

	public boolean wireDroppedStepFields(){
		if (steps.isEmpty()) return stepFieldsDropped;
		for (Step step : steps){
			if (step.heroHp >= 0 || step.turn >= 0 || !step.inventory.isEmpty()) return false;
		}
		return true;
	}

	public boolean stepFieldsDropped = false;

	public boolean recordsRngFingerprint(){
		if (steps.isEmpty()) return false;
		for (Step step : steps){
			if (step.baseDraws >= 0) return true;
		}
		return false;
	}

	public boolean recordsQuickslots(){
		if (steps.isEmpty()) return quickslotsRecorded;
		for (Step step : steps){
			if (!EnvMode.SLOT.name().equals( step.mode ) && !EnvMode.INVENTORY.name().equals( step.mode )){
				continue;
			}
			if (step.quickslots != null && !step.quickslots.isEmpty()) return true;
		}
		return false;
	}

	public boolean quickslotsRecorded = true;

	public String group = "";

	public String termination = "";

	public void captureOutcome( double score, int depth, int turns, int generation, String termination ){
		this.score = score;
		this.depth = depth;
		this.turns = turns;
		this.generation = generation;
		this.termination = termination == null ? "" : termination;
	}

	public void reset(){
		steps.clear();
		score = 0;
		depth = 0;
		turns = 0;
		generation = 0;
		declaredSteps = 0;
		truncatedAt = -1;
		rewardProfile = com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile.DEFAULT;
	}
}