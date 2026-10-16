package com.shatteredpixel.shatteredpixeldungeon.superintelligence.utils;

import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;

public class DungeonSeedCodes {

	private DungeonSeedCodes() {}

	public static String encode( long seed ){
		return DungeonSeed.convertToCode( seed );
	}

	public static long decode( String text ){
		return DungeonSeed.convertFromText( text );
	}

	public static String random(){
		return DungeonSeed.convertToCode( DungeonSeed.randomSeed() );
	}

	public static boolean isValid( String text ){
		if (text == null || text.isEmpty()) return false;
		return decode( text ) >= 0;
	}
}