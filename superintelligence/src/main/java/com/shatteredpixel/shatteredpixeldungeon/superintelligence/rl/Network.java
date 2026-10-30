package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.InventoryEncoder;

import java.util.Random;


public class Network extends NetworkWeights implements PolicyNetwork {

	private final Tensor convOut;
	private final Tensor reduceOut;
	private final Tensor concat;
	private final Tensor hidden;
	private final Tensor memoryOut;
	private final Tensor actionLogits;
	private final Tensor slotLogits;
	private final Tensor targetLogits;
	private final Tensor value;

	private final Tensor dAction;
	private final Tensor dSlot;
	private final Tensor dTarget;
	private final Tensor dValueOut;
	private final Tensor dCell;
	private final Tensor dHidden;
	private final Tensor dHiddenT;
	private final Tensor dHeadScratch;
	private final Tensor dPrev;
	private final Tensor dLstmIn;
	private final Tensor dTrunkIn;
	private final Tensor dReduce;
	private final Tensor dConv;


	private final int gridLength;

	private float[] lastGrid;

	public Network( EnvConfig config, Random rng ){
		super( config, rng );

		int convFeatures = reduce.outputSize();

		this.convOut = new Tensor( 1, conv.outputSize() );
		this.reduceOut = new Tensor( 1, convFeatures );
		this.concat = new Tensor( 1, convFeatures + extraInputs );
		this.hidden = new Tensor( 1, 256 );
		this.memoryOut = new Tensor( 1, memory.size );
		this.actionLogits = new Tensor( 1, actionHead.out );
		this.slotLogits = new Tensor( 1, slotHead.out );
		this.targetLogits = new Tensor( 1, targetHead.out );
		this.value = new Tensor( 1, 1 );

		this.dAction = new Tensor( 1, actionHead.out );
		this.dSlot = new Tensor( 1, slotHead.out );
		this.dTarget = new Tensor( 1, targetHead.out );
		this.dValueOut = new Tensor( 1, 1 );
		this.dCell = new Tensor( 1, memory.size );
		this.dHidden = new Tensor( 1, memory.size );
		this.dHiddenT = new Tensor( 1, memory.size );
		this.dHeadScratch = new Tensor( 1, memory.size );
		this.dPrev = new Tensor( 1, memory.size );
		this.dLstmIn = new Tensor( 1, 256 );
		this.dTrunkIn = new Tensor( 1, trunk.in );
		this.dReduce = new Tensor( 1, convFeatures );
		this.dConv = new Tensor( 1, conv.outputSize() );

		this.gridLength = config.spatialChannels() * config.gridWidth * config.gridHeight;
	}


	public int actionCount(){ return actionHead.out; }
	public int slotCount(){ return slotHead.out; }
	public int targetCount(){ return targetHead.out; }


	public int gridLength(){
		return gridLength;
	}


	public int concatLength(){
		return concat.cols;
	}

	public int convPreActLength(){
		return conv.preActivationSize();
	}

	public int reducePreActLength(){
		return reduce.preActivationSize();
	}


	public int trunkOut(){
		return trunk.out;
	}


	public int memoryOut(){
		return memoryOut.cols;
	}


	public int lstmCacheLength(){
		return memory.cacheLength();
	}


	public StepCache takeCache(){
		return new StepCache( this );
	}

	public void resetState(){
		memory.reset();
	}


	public int stateSize(){
		return 2 * memory.size;
	}


	public void saveState( float[] into ){
		memory.snapshot( into );
	}


	public void loadState( float[] from ){
		memory.restore( from );
	}


	public void forward( float[] grid, float[] inventory, float[] heroFeatures ){
		runForward( grid, inventory, heroFeatures );
	}

	public void forward( float[] grid, int[] touched, int touchedCount, float[] inventory, float[] heroFeatures ){
		forward( grid, inventory, heroFeatures );
	}


	public void forwardCached( float[] grid, float[] inventory, float[] heroFeatures,
			StepCache cache ){
		runForward( grid, inventory, heroFeatures );

		System.arraycopy( grid, 0, cache.grid, 0,
				Math.min( grid.length, cache.grid.length ) );
		System.arraycopy( concat.data, 0, cache.concat, 0, concat.cols );
		System.arraycopy( hidden.data, 0, cache.hidden, 0, hidden.cols );
		System.arraycopy( memoryOut.data, 0, cache.memoryOut, 0, memoryOut.cols );

		conv.savePreAct( cache.convPreAct );
		reduce.savePreAct( cache.reducePreAct );
		trunk.savePreAct( cache.trunkPreAct );
		actionHead.savePreAct( cache.actionPreAct );
		slotHead.savePreAct( cache.slotPreAct );
		targetHead.savePreAct( cache.targetPreAct );
		valueHead.savePreAct( cache.valuePreAct );
		memory.saveCache( cache.lstm );
	}

	private void runForward( float[] grid, float[] inventory, float[] heroFeatures ){
		lastGrid = grid;
		encode( grid, inventory, heroFeatures );
		trunk.forward( concat, hidden );
		recur();
	}

	private void encode( float[] grid, float[] inventory, float[] heroFeatures ){
		conv.forward( grid, convOut );
		reduce.forward( convOut.data, reduceOut );

		System.arraycopy( reduceOut.data, 0, concat.data, 0, reduceOut.cols );
		int off = reduceOut.cols;
		System.arraycopy( inventory, 0, concat.data, off, inventory.length );
		off += config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT;
		System.arraycopy( heroFeatures, 0, concat.data, off, heroFeatures.length );
	}

	private void recur(){
		memory.forward( hidden, memoryOut );

		actionHead.forward( memoryOut, actionLogits );
		slotHead.forward( memoryOut, slotLogits );
		targetHead.forward( memoryOut, targetLogits );
		valueHead.forward( memoryOut, value );
	}

	public float[] actionLogits(){ return actionLogits.data; }
	public float[] slotLogits(){ return slotLogits.data; }
	public float[] targetLogits(){ return targetLogits.data; }
	public float value(){ return value.data[ 0 ]; }


public void backward( float[] dActionLogits, float[] dSlotLogits, float[] dTargetLogits,
			float dCritic ){
		beginBackward();
		backwardStep( dActionLogits, dSlotLogits, dTargetLogits, dCritic, null );
	}


	public void beginBackward(){
		dPrev.fill( 0f );
		dCell.fill( 0f );
	}


	public void backwardStep( float[] dActionLogits, float[] dSlotLogits, float[] dTargetLogits,
			float dCritic, StepCache cache ){

		if (cache != null) restore( cache );
		backHeadsAndMemory( dActionLogits, dSlotLogits, dTargetLogits, dCritic );
		dTrunkIn.fill( 0f );
		trunk.backward( concat, dLstmIn, dTrunkIn );
		backEncoder( lastGrid, dTrunkIn.data );
	}

	private void backHeadsAndMemory( float[] dActionLogits, float[] dSlotLogits, float[] dTargetLogits,
			float dCritic ){
		if (dActionLogits != null) copyInto( dActionLogits, dAction );
		else dAction.fill( 0f );
		if (dSlotLogits != null) copyInto( dSlotLogits, dSlot );
		else dSlot.fill( 0f );
		if (dTargetLogits != null) copyInto( dTargetLogits, dTarget );
		else dTarget.fill( 0f );

		dValueOut.data[ 0 ] = dCritic;

		dHidden.fill( 0f );
		headGradient( actionHead, dAction );
		headGradient( slotHead, dSlot );
		headGradient( targetHead, dTarget );
		headGradient( valueHead, dValueOut );

		copyInto( dHidden.data, dHiddenT );
		dHiddenT.addScaled( dPrev, 1f );

		memory.backward( hidden, dHiddenT, dPrev, dCell );

		dLstmIn.fill( 0f );
		System.arraycopy( memory.inputGrad(), 0, dLstmIn.data, 0,
				Math.min( memory.inputGradLen(), dLstmIn.cols ) );
	}

	private void backEncoder( float[] grid, float[] dConcat ){
		dReduce.fill( 0f );
		System.arraycopy( dConcat, 0, dReduce.data, 0, dReduce.cols );
		dConv.fill( 0f );
		reduce.backward( convOut.data, dReduce, dConv.data );
		conv.backward( grid, dConv, null );
	}

	public void encodeStep( float[] inventory, float[] heroFeatures, StepCache cache ){
		encode( cache.grid, inventory, heroFeatures );
		System.arraycopy( concat.data, 0, cache.concat, 0, concat.cols );
		conv.savePreAct( cache.convPreAct );
		reduce.savePreAct( cache.reducePreAct );
	}

	public void trunkSteps( StepCache[] caches, int steps ){
		float[][] x = new float[ steps ][], y = new float[ steps ][], pre = new float[ steps ][];
		for (int t = 0; t < steps; t++){
			x[ t ] = caches[ t ].concat;
			y[ t ] = caches[ t ].hidden;
			pre[ t ] = caches[ t ].trunkPreAct;
		}
		trunk.forwardSteps( x, y, pre, steps );
	}

	public void recurrentStep( StepCache cache ){
		System.arraycopy( cache.hidden, 0, hidden.data, 0, hidden.cols );
		recur();
		System.arraycopy( memoryOut.data, 0, cache.memoryOut, 0, memoryOut.cols );
		actionHead.savePreAct( cache.actionPreAct );
		slotHead.savePreAct( cache.slotPreAct );
		targetHead.savePreAct( cache.targetPreAct );
		valueHead.savePreAct( cache.valuePreAct );
		memory.saveCache( cache.lstm );
	}

	public void recurrentBackwardStep( float[] dActionLogits, float[] dSlotLogits, float[] dTargetLogits,
			float dCritic, StepCache cache ){
		System.arraycopy( cache.hidden, 0, hidden.data, 0, hidden.cols );
		System.arraycopy( cache.memoryOut, 0, memoryOut.data, 0, memoryOut.cols );
		actionHead.loadPreAct( cache.actionPreAct );
		slotHead.loadPreAct( cache.slotPreAct );
		targetHead.loadPreAct( cache.targetPreAct );
		valueHead.loadPreAct( cache.valuePreAct );
		memory.loadCache( cache.lstm );
		backHeadsAndMemory( dActionLogits, dSlotLogits, dTargetLogits, dCritic );
		System.arraycopy( dLstmIn.data, 0, cache.dHidden, 0, cache.dHidden.length );
	}

	public void trunkBackwardSteps( StepCache[] caches, int steps ){
		float[][] x = new float[ steps ][], pre = new float[ steps ][], dy = new float[ steps ][], dx = new float[ steps ][];
		for (int t = 0; t < steps; t++){
			x[ t ] = caches[ t ].concat;
			pre[ t ] = caches[ t ].trunkPreAct;
			dy[ t ] = caches[ t ].dHidden;
			dx[ t ] = caches[ t ].dConcat;
		}
		trunk.backwardSteps( x, pre, dy, dx, steps );
	}

	public void encoderBackwardStep( StepCache cache ){
		conv.loadPreAct( cache.convPreAct );
		for (int i = 0; i < convOut.cols; i++) convOut.data[ i ] = Math.max( 0f, cache.convPreAct[ i ] );
		reduce.loadPreAct( cache.reducePreAct );
		backEncoder( cache.grid, cache.dConcat );
	}


	private void restore( StepCache cache ){
		System.arraycopy( cache.concat, 0, concat.data, 0, concat.cols );
		System.arraycopy( cache.hidden, 0, hidden.data, 0, hidden.cols );
		System.arraycopy( cache.memoryOut, 0, memoryOut.data, 0, memoryOut.cols );
		lastGrid = cache.grid;

		conv.loadPreAct( cache.convPreAct );
		for (int i = 0; i < convOut.cols; i++) convOut.data[ i ] = Math.max( 0f, cache.convPreAct[ i ] );
		reduce.loadPreAct( cache.reducePreAct );
		trunk.loadPreAct( cache.trunkPreAct );
		actionHead.loadPreAct( cache.actionPreAct );
		slotHead.loadPreAct( cache.slotPreAct );
		targetHead.loadPreAct( cache.targetPreAct );
		valueHead.loadPreAct( cache.valuePreAct );
		memory.loadCache( cache.lstm );
	}


	public float[] prevStateGradient(){
		return dPrev.data;
	}


	private static void copyInto( float[] src, Tensor into ){
		into.fill( 0f );
		System.arraycopy( src, 0, into.data, 0, Math.min( src.length, into.cols ) );
	}


	private void headGradient( Dense head, Tensor dOut ){
		head.backward( memoryOut, dOut, dHeadScratch );
		dHidden.addScaled( dHeadScratch, 1f );
	}

}
