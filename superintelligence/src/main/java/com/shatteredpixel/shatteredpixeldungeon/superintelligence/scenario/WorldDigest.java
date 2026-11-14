package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorldDigest {

	private WorldDigest(){}

	public static String capture(){
		Map< String, List< String > > sections = new LinkedHashMap<>();
		Hero hero = Dungeon.hero;
		sections.put( "hero", List.of( hero.pos + " " + hero.HP + "/" + hero.HT + " d" + Dungeon.depth + " g" + Dungeon.gold
				+ " l" + hero.lvl + " x" + hero.exp + " s" + hero.STR + " " + buffs( hero ) + " " + ReplayRecorder.inventory() ));
		List< String > mobs = new ArrayList<>();
		for (Mob mob : Dungeon.level.mobs){
			mobs.add( mob.getClass().getSimpleName() + " " + mob.pos + " " + mob.HP + "/" + mob.HT + " " + mob.alignment
					+ " " + buffs( mob ));
		}
		sections.put( "mobs", mobs );
		List< String > heaps = new ArrayList<>();
		for (Heap heap : Dungeon.level.heaps.valueList()){
			StringBuilder row = new StringBuilder().append( heap.pos ).append( ' ' ).append( heap.type );
			for (Item item : heap.items) row.append( ' ' ).append( item.getClass().getSimpleName() ).append( 'x' ).append( item.quantity() );
			heaps.add( row.toString() );
		}
		sections.put( "heaps", heaps );
		sections.put( "terrain", List.of( java.util.Arrays.toString( Dungeon.level.map )));
		List< String > traps = new ArrayList<>();
		for (Trap trap : Dungeon.level.traps.valueList()){
			traps.add( trap.pos + " " + trap.getClass().getSimpleName() + " " + trap.active + " " + trap.visible );
		}
		sections.put( "traps", traps );
		List< String > blobs = new ArrayList<>();
		for (Blob blob : Dungeon.level.blobs.values()) blobs.add( blob.getClass().getSimpleName() + " " + blob.volume );
		sections.put( "blobs", blobs );

		StringBuilder out = new StringBuilder();
		for (Map.Entry< String, List< String > > section : sections.entrySet()){
			List< String > rows = new ArrayList<>( section.getValue() );
			Collections.sort( rows );
			if (out.length() > 0) out.append( ',' );
			out.append( section.getKey() ).append( ':' ).append( Long.toHexString( fnv( String.join( "\n", rows ))));
		}
		return out.toString();
	}

	public static String difference( String recorded, String live ){
		if (recorded == null || recorded.isEmpty() || recorded.equals( live )) return null;
		Map< String, String > was = parse( recorded ), now = parse( live );
		List< String > differing = new ArrayList<>();
		for (String section : was.keySet()) if (!was.get( section ).equals( now.get( section ))) differing.add( section );
		return differing.isEmpty() ? "recorded " + recorded + ", replayed " + live
				: "section " + String.join( ", ", differing ) + " differs at the end of the recording";
	}

	private static Map< String, String > parse( String digest ){
		Map< String, String > out = new LinkedHashMap<>();
		for (String part : digest.split( "," )){
			int colon = part.indexOf( ':' );
			if (colon > 0) out.put( part.substring( 0, colon ), part.substring( colon + 1 ));
		}
		return out;
	}

	private static String buffs( Char ch ){
		List< String > names = new ArrayList<>();
		for (Buff buff : ch.buffs()) names.add( buff.getClass().getSimpleName() );
		Collections.sort( names );
		return String.join( "+", names );
	}

	private static long fnv( String text ){
		long hash = 0xcbf29ce484222325L;
		for (int i = 0; i < text.length(); i++){
			hash ^= text.charAt( i );
			hash *= 0x100000001b3L;
		}
		return hash;
	}
}
