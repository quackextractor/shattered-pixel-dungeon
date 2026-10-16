package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import java.util.ArrayDeque;

public class Transition {

	public byte[] grid;

	public float[] inventory;
	public float[] hero;

	public float[] actionMask;
	public float[] slotMask;
	public float[] targetMask;

	public float[] recurrentState;

	public int liveHead;

	public int episodeId = -1;
	public int stepIndex = -1;

	public int actionIndex;
	public int slotIndex;

	public float oldLogProbability;

	public float value;

	public float reward;

	public boolean terminal;

	public float nextValue;

	public float returnValue;
	public float advantage;

	private static final ArrayDeque<Transition> POOL = new ArrayDeque<>();
	private static final int MAX_POOLED = 8192;

	private boolean pooled = false;

	public static Transition take( int gridSize, int inventorySize, int heroSize,
			int actionCount, int slotCount, int targetCount, int recurrentStateSize ){

		Transition t = POOL.poll();
		if (t == null) t = new Transition();

		t.grid    = fit( t.grid,    gridSize );
		t.inventory = fit( t.inventory, inventorySize );
		t.hero    = fit( t.hero,    heroSize );
		t.actionMask = fit( t.actionMask, actionCount );
		t.slotMask   = fit( t.slotMask,   slotCount );
		t.targetMask = fit( t.targetMask, targetCount );
		t.recurrentState = recurrentStateSize > 0
				? fit( t.recurrentState, recurrentStateSize )
				: null;

		t.oldLogProbability = 0;
		t.value = 0;
		t.reward = 0;
		t.nextValue = 0;
		t.returnValue = 0;
		t.advantage = 0;
		t.terminal = false;
		t.liveHead = Policy.HEAD_ACTION;
		t.episodeId = -1;
		t.stepIndex = -1;
		t.actionIndex = 0;
		t.slotIndex = 0;
		t.pooled = false;

		return t;
	}

	public static Transition take( int gridSize, int inventorySize, int heroSize,
			int actionCount, int slotCount, int targetCount ){
		return take( gridSize, inventorySize, heroSize, actionCount, slotCount, targetCount, 0 );
	}

	private static float[] fit( float[] existing, int len ){
		return (existing != null && existing.length == len) ? existing : new float[ len ];
	}

	private static byte[] fit( byte[] existing, int len ){
		return (existing != null && existing.length == len) ? existing : new byte[ len ];
	}

	public void packGrid( float[] src ){
		for (int i = 0; i < grid.length; i++){
			grid[ i ] = (byte) (src[ i ] > 0.5f ? 1 : 0 );
		}
	}

	public void unpackGrid( float[] dst ){
		for (int i = 0; i < grid.length && i < dst.length; i++){
			dst[ i ] = grid[ i ] != 0 ? 1f : 0f;
		}
	}

	public void release(){
		if (pooled) return;
		pooled = true;
		if (POOL.size() < MAX_POOLED) POOL.addLast( this );
	}
}