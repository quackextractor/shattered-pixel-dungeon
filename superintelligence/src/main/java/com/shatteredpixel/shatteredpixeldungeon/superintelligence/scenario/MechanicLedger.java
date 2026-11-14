package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class MechanicLedger {

	private final Map< String, Integer > counts = new TreeMap<>();
	private int[] statsAtStart;

	public MechanicLedger(){
		for (Action action : Action.values()) if (action.kind != Action.Kind.MACRO) counts.put( "action:" + action.name(), 0 );
		for (EnvMode mode : EnvMode.values()) counts.put( "mode:" + mode.name(), 0 );
	}

	public void begin(){
		statsAtStart = stats();
	}

	public void before( SPDEnv env, Action action, int secondary ){
		add( "action:" + action.name() );
		add( "mode:" + env.mode().name() );
		if (env.mode() != EnvMode.WORLD && action == Action.USE){
			List< Item > slots = env.mapper().slots();
			Item item = secondary >= 0 && secondary < slots.size() ? slots.get( secondary ) : null;
			if (item != null) add( "item:" + item.getClass().getSimpleName() );
		}
		for (Mob mob : Dungeon.level.mobs){
			if (Dungeon.level.heroFOV[ mob.pos ]) add( "mob:" + mob.getClass().getSimpleName() );
		}
		for (Buff buff : Dungeon.hero.buffs()) add( "buff:" + buff.getClass().getSimpleName() );
	}

	public void end(){
		if (statsAtStart == null) return;
		int[] now = stats();
		for (int i = 0; i < STAT_NAMES.length; i++){
			if (now[ i ] > statsAtStart[ i ]) counts.merge( "stat:" + STAT_NAMES[ i ], now[ i ] - statsAtStart[ i ], Integer::sum );
		}
		statsAtStart = null;
	}

	public int count( String key ){
		return counts.getOrDefault( key, 0 );
	}

	public int never(){
		int never = 0;
		for (int value : counts.values()) if (value == 0) never++;
		return never;
	}

	public void write( File out ){
		List< String > lines = new ArrayList<>();
		lines.add( "mechanic,steps" );
		for (Map.Entry< String, Integer > entry : counts.entrySet()) lines.add( entry.getKey() + "," + entry.getValue() );
		try {
			out.mkdirs();
			Files.write( new File( out, "coverage.csv" ).toPath(), lines, StandardCharsets.UTF_8 );
		} catch (IOException e){
			System.out.println( "[WARN]   could not write coverage.csv: " + e.getMessage() );
		}
	}

	private void add( String key ){
		counts.merge( key, 1, Integer::sum );
	}

	private static final String[] STAT_NAMES = { "enemiesSlain", "foodEaten", "itemsCrafted", "upgradesUsed",
			"sneakAttacks", "thrownAttacks", "goldCollected", "ankhsUsed" };

	private static int[] stats(){
		return new int[]{ Statistics.enemiesSlain, Statistics.foodEaten, Statistics.itemsCrafted, Statistics.upgradesUsed,
				Statistics.sneakAttacks, Statistics.thrownAttacks, Statistics.goldCollected, Statistics.ankhsUsed };
	}
}
