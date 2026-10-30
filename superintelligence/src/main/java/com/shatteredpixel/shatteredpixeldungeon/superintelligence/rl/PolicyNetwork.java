package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

public interface PolicyNetwork {

	void forward( float[] grid, float[] inventory, float[] heroFeatures );

	void forward( float[] grid, int[] touched, int touchedCount, float[] inventory, float[] heroFeatures );

	float[] actionLogits();

	float[] slotLogits();

	float[] targetLogits();

	float value();

	void resetState();

	int stateSize();

	void saveState( float[] into );

	void loadState( float[] from );
}
