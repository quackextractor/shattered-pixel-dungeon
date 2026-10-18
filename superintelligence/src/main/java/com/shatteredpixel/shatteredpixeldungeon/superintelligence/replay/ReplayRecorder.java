package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;

import java.util.List;


public class ReplayRecorder {

private final Replay replay = new Replay();
	private boolean recording;
	private boolean rngFingerprint;

	public ReplayRecorder() {}


	public void begin( String seedText, String heroClass, int challenges, int turnLimitPerFloor ){
		begin( seedText, heroClass, challenges, turnLimitPerFloor, null );
	}


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
			replay.rewardProfile = config.rewardProfile;
			rngFingerprint = config.recordRngFingerprint;
		} else {
			rngFingerprint = false;
		}

		if (rngFingerprint ){
			com.watabou.utils.RandomTrace.reset();
			com.watabou.utils.RandomTrace.enable();
		} else {
			com.watabou.utils.RandomTrace.disable();
		}

		recording = true;
	}


	public void record( Action action, int slot, EnvMode mode ){
		if (!recording) return;
		replay.add( action.name(), slot, mode.name(), -1, 0 );

		if (mode == EnvMode.SLOT || mode == EnvMode.INVENTORY){
			replay.steps.get( replay.length() - 1 ).quickslots = Quickslots.capture();
		}
	}


	public void afterStep( int heroPosition, float reward ){
		afterStep( heroPosition, reward, -1 );
	}

	public void afterStep( int heroPosition, float reward, int accepted ){
		if (!recording) return;
		int last = replay.length() - 1;
		if (last < 0) return;
		Replay.Step step = replay.steps.get( last );
		step.heroPos = heroPosition;
		step.reward = reward;

		step.heroHp = Dungeon.hero.HP;
		step.turn = Actor.now();
		step.inventory = inventoryOf();
		step.accepted = accepted;

		if (rngFingerprint && Dungeon.hero.isAlive() ){
			step.baseDraws = com.watabou.utils.RandomTrace.baseDraws();
			step.baseFingerprint = com.watabou.utils.RandomTrace.baseFingerprint();
		}
	}


	public static String inventory(){
		java.util.TreeMap< String, Integer > counts = new java.util.TreeMap<>();
		for (com.shatteredpixel.shatteredpixeldungeon.items.Item item : Dungeon.hero.belongings.backpack){
			counts.merge( describe( item ), 1, Integer::sum );
		}
		StringBuilder sb = new StringBuilder();
		for (java.util.Map.Entry< String, Integer > e : counts.entrySet()){
			if (sb.length() > 0) sb.append( ',' );
			sb.append( e.getKey() ).append( ':' ).append( e.getValue() );
		}
		return sb.toString();
	}


	private static final int MAX_BAG_DEPTH = 6;

	private static String describe( Item item ){
		return describe( item, 0 );
	}

	private static String describe( Item item, int depth ){
		if (depth >= MAX_BAG_DEPTH) return item.getClass().getSimpleName() + "()";
		if (!(item instanceof com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag)){
			return item.getClass().getSimpleName();
		}

		StringBuilder sb = new StringBuilder( item.getClass().getSimpleName() ).append( '(' );
		List< Item > inside = ((com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag) item).items;
		for (int i = 0; i < inside.size(); i++){
			if (i > 0) sb.append( '+' );
			sb.append( describe( inside.get( i ), depth + 1 ) );
		}
		return sb.append( ')' ).toString();
	}

	private static String inventoryOf(){
		return inventory();
	}


	public void end( double score, int depth, int turns, int generation, String termination ){
		replay.captureOutcome( score, depth, turns, generation, termination );
		recording = false;
	}

	public Replay replay(){
		return replay;
	}

	public boolean recording(){
		return recording;
	}

}