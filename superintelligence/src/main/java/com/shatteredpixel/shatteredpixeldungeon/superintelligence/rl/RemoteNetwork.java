package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.shared.InferenceBroker;

public final class RemoteNetwork implements PolicyNetwork {

	private final InferenceBroker.Server server;
	private final int slot, gridLength;
	private final Transition packer = new Transition();
	private final float[] out, action, slots, targets;

	public RemoteNetwork( EnvConfig config, InferenceBroker.Server server, int slot ){
		this.server = server;
		this.slot = slot >= 0 ? slot : server.open();
		gridLength = config.spatialChannels() * config.gridWidth * config.gridHeight;
		packer.gridCells = gridLength;
		packer.grid = new byte[ Transition.packedGridBytes( gridLength ) ];
		action = new float[ Action.size() ];
		slots = new float[ config.maxSlots ];
		targets = new float[ ActionMapper.TARGET_COUNT ];
		out = new float[ action.length + slots.length + targets.length + 1 ];
	}

	@Override
	public void forward( float[] grid, float[] inventory, float[] heroFeatures ){
		packer.packGrid( grid );
		send( inventory, heroFeatures );
	}

	@Override
	public void forward( float[] grid, int[] touched, int touchedCount, float[] inventory, float[] heroFeatures ){
		packer.packGrid( grid, touched, touchedCount );
		send( inventory, heroFeatures );
	}

	private void send( float[] inventory, float[] heroFeatures ){
		server.forward( slot, packer.grid, inventory, heroFeatures, out );
		System.arraycopy( out, 0, action, 0, action.length );
		System.arraycopy( out, action.length, slots, 0, slots.length );
		System.arraycopy( out, action.length + slots.length, targets, 0, targets.length );
	}

	public int slot(){ return slot; }

	public int gridLength(){ return gridLength; }

	@Override public float[] actionLogits(){ return action; }
	@Override public float[] slotLogits(){ return slots; }
	@Override public float[] targetLogits(){ return targets; }
	@Override public float value(){ return out[ out.length - 1 ]; }

	@Override public int stateSize(){ return 2 * NetworkWeights.MEMORY_CELLS; }
	@Override public void resetState(){ server.reset( slot ); }
	@Override public void saveState( float[] into ){ server.save( slot, into ); }
	@Override public void loadState( float[] from ){ server.load( slot, from ); }
}
