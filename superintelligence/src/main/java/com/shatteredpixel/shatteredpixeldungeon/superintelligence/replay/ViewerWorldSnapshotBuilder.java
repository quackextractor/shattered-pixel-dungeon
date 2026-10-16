package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;

public final class ViewerWorldSnapshotBuilder {

	private ViewerWorldSnapshotBuilder(){}

	public static WorldSnapshot capture( int stepIndex, String phase ){
		Level level = Dungeon.level;
		boolean includeCells = (level != null) && (level.length() > 0);
		return WorldSnapshot.capture( stepIndex, phase, includeCells );
	}
}