package com.shatteredpixel.shatteredpixeldungeon.superintelligence.utils;

import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;

/**
 * Converts seeds between the game's numeric form and the text used in seeds, replay files and the
 * trainer's logs.
 *
 * A run is only reproducible from its seed text, so the text form has to round-trip exactly.
 * {@link DungeonSeed#convertToCode} produces the game's own nine-consonant code, which is
 * human-typeable and unambiguous - which matters when a seed is being typed into a command line by
 * hand to reproduce a run.
 */
public class DungeonSeedCodes {

	private DungeonSeedCodes() {}

	/** Encodes a numeric seed as the game's pronounceable code. */
	public static String encode( long seed ){
		return DungeonSeed.convertToCode( seed );
	}

	/** Decodes a text seed back to the game's numeric form. */
	public static long decode( String text ){
		return DungeonSeed.convertFromText( text );
	}

	/**
	 * Picks a readable random seed.
	 *
	 * Delegates to the game's own generator, which deliberately avoids vowels so the codes cannot
	 * spell anything.
	 */
	public static String random(){
		return DungeonSeed.convertToCode( DungeonSeed.randomSeed() );
	}

	/** True when {@code text} resolves to a seed rather than meaning "no seed". */
	public static boolean isValid( String text ){
		if (text == null || text.isEmpty()) return false;
		return decode( text ) >= 0;
	}
}