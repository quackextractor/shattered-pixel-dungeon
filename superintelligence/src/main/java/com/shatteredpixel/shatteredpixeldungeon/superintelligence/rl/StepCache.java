package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

public class StepCache {

	final float[] grid;
	final float[] concat;
	final float[] hidden;

	final float[] convPreAct;
	final float[] reducePreAct;

	final float[] trunkPreAct;
	final float[] actionPreAct;
	final float[] slotPreAct;
	final float[] targetPreAct;
	final float[] valuePreAct;

	final float[] actionGrad;
	final float[] slotGrad;
	final float[] targetGrad;
	float valueGrad;

	final float[] memoryOut;
	final float[] lstm;
	final int lstmStride;

	final float[] dHidden;
	final float[] dConcat;

	public float[] grid(){
		return grid;
	}

	StepCache( Network net ){
		this.grid = new float[ net.gridLength() ];
		this.concat = new float[ net.concatLength() ];
		this.hidden = new float[ net.trunkOut() ];
		this.convPreAct = new float[ net.convPreActLength() ];
		this.reducePreAct = new float[ net.reducePreActLength() ];
		this.trunkPreAct = new float[ net.trunkOut() ];
		this.actionPreAct = new float[ net.actionCount() ];
		this.slotPreAct = new float[ net.slotCount() ];
		this.targetPreAct = new float[ net.targetCount() ];
		this.valuePreAct = new float[ 1 ];
		this.actionGrad = new float[ net.actionCount() ];
		this.slotGrad = new float[ net.slotCount() ];
		this.targetGrad = new float[ net.targetCount() ];
		this.memoryOut = new float[ net.memoryOut() ];
		this.lstmStride = net.lstmCacheLength();
		this.lstm = new float[ net.lstmCacheLength() ];
		this.dHidden = new float[ net.trunkOut() ];
		this.dConcat = new float[ net.concatLength() ];
	}
}