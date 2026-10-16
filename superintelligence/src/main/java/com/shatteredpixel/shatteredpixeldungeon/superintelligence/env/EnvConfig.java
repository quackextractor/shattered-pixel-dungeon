package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;


public class EnvConfig {


	public int maxSlots = 96;


	public int gridWidth = 48;
	public int gridHeight = 48;


	public int spatialChannels(){
		return com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.GridChannel.spatialCount();
	}


	public int turnLimitPerFloor = 1500;


	public int turnLimitTotal = 40000;


	public int actorStepLimit = 20000;


	public int stallLimit = 120;


	public int freeStepBudget = 250;


	public int maxViewDistance = 12;


	public boolean honourVisionBuffs = true;


	public boolean mimicsVisibleOutOfFog = true;


	public boolean allowEquipping = true;


	public boolean allowTrading = true;


	public boolean allowAlchemy = true;


	public float curriculumProgress = 0f;


	public float turnCost = 1f;


	public float depthReward = 1800f;


	public float exploreReward = 0.05f;


	public float maxHpReward = 0.5f;


	public float talentReward = 400f;

	public float stairPotentialWeight = 2f;


	public float goldReward = 0.05f;


	public float damagePenalty = 40f;


	public float killReward = 150f;


	public float sneakAttackReward = 300f;


	public float freeStepCostFactor = 0.5f;


	public float deathPenalty = 2500f;


	public float stallPenalty = 3000f;


	public float turnLimitPenalty = 2000f;


	public float victoryReward = 60000f;


	public com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile rewardProfile
			= com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile.DEFAULT;


	public boolean persistSaves = false;


	public float dialogAgeSeconds = 0.2f;


	public void writeTo( DataOutputStream out ) throws IOException {
		out.writeInt( maxSlots );
		out.writeInt( gridWidth );
		out.writeInt( gridHeight );
		out.writeInt( turnLimitPerFloor );
		out.writeInt( turnLimitTotal );
		out.writeInt( actorStepLimit );
		out.writeInt( stallLimit );
		out.writeInt( freeStepBudget );
				out.writeInt( maxViewDistance );
		out.writeFloat( curriculumProgress );
		out.writeFloat( turnCost );
		out.writeFloat( depthReward );
		out.writeFloat( exploreReward );
		out.writeFloat( stairPotentialWeight );
		out.writeFloat( maxHpReward );
		out.writeFloat( talentReward );
		out.writeFloat( goldReward );
		out.writeFloat( damagePenalty );
		out.writeFloat( killReward );
		out.writeFloat( sneakAttackReward );
		out.writeFloat( freeStepCostFactor );
		out.writeFloat( deathPenalty );
		out.writeFloat( stallPenalty );
		out.writeFloat( turnLimitPenalty );
		out.writeFloat( victoryReward );
		out.writeFloat( dialogAgeSeconds );
		out.writeBoolean( honourVisionBuffs );
		out.writeBoolean( mimicsVisibleOutOfFog );
		out.writeBoolean( allowEquipping );
		out.writeBoolean( allowTrading );
		out.writeBoolean( allowAlchemy );
		out.writeBoolean( persistSaves );

		out.writeUTF( rewardProfile.key() );
	}


	public void readFrom( DataInputStream in ) throws IOException {
		maxSlots = in.readInt();
		gridWidth = in.readInt();
		gridHeight = in.readInt();
		turnLimitPerFloor = in.readInt();
		turnLimitTotal = in.readInt();
		actorStepLimit = in.readInt();
		stallLimit = in.readInt();
		freeStepBudget = in.readInt();
		maxViewDistance = in.readInt();
		curriculumProgress = in.readFloat();
		turnCost = in.readFloat();
		depthReward = in.readFloat();
		exploreReward = in.readFloat();
		stairPotentialWeight = in.readFloat();
		maxHpReward = in.readFloat();
		talentReward = in.readFloat();
		goldReward = in.readFloat();
		damagePenalty = in.readFloat();
		killReward = in.readFloat();
		sneakAttackReward = in.readFloat();
		freeStepCostFactor = in.readFloat();
		deathPenalty = in.readFloat();
		stallPenalty = in.readFloat();
		turnLimitPenalty = in.readFloat();
		victoryReward = in.readFloat();
		dialogAgeSeconds = in.readFloat();
		honourVisionBuffs = in.readBoolean();
		mimicsVisibleOutOfFog = in.readBoolean();
		allowEquipping = in.readBoolean();
		allowTrading = in.readBoolean();
		allowAlchemy = in.readBoolean();
		persistSaves = in.readBoolean();
		rewardProfile = com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardProfile
				.parse( in.readUTF() );
	}

	public EnvConfig copy(){
		EnvConfig c = new EnvConfig();
		c.maxSlots = maxSlots;
		c.gridWidth = gridWidth;
		c.gridHeight = gridHeight;
		c.turnLimitPerFloor = turnLimitPerFloor;
		c.turnLimitTotal = turnLimitTotal;
		c.actorStepLimit = actorStepLimit;
		c.stallLimit = stallLimit;
		c.freeStepBudget = freeStepBudget;
		c.maxViewDistance = maxViewDistance;
		c.honourVisionBuffs = honourVisionBuffs;
		c.mimicsVisibleOutOfFog = mimicsVisibleOutOfFog;
		c.allowEquipping = allowEquipping;
		c.allowTrading = allowTrading;
		c.allowAlchemy = allowAlchemy;
		c.curriculumProgress = curriculumProgress;
		c.turnCost = turnCost;
		c.depthReward = depthReward;
		c.exploreReward = exploreReward;
		c.stairPotentialWeight = stairPotentialWeight;
		c.maxHpReward = maxHpReward;
		c.talentReward = talentReward;
		c.goldReward = goldReward;
		c.damagePenalty = damagePenalty;
		c.killReward = killReward;
		c.sneakAttackReward = sneakAttackReward;
		c.freeStepCostFactor = freeStepCostFactor;
		c.deathPenalty = deathPenalty;
		c.stallPenalty = stallPenalty;
		c.turnLimitPenalty = turnLimitPenalty;
		c.victoryReward = victoryReward;
		c.dialogAgeSeconds = dialogAgeSeconds;
		c.rewardProfile = rewardProfile;
		c.persistSaves = persistSaves;
		return c;
	}
}
