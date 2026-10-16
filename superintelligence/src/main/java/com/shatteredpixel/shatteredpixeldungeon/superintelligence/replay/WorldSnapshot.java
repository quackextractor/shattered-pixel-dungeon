package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Belongings;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SpecialRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.plants.Plant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public class WorldSnapshot {
	private static final String HEADER =
			"# spd-world-snapshot v1\trecord\tlabel\tfields";
	private static final int MAX_BAG_DEPTH = 8;
	private final List< String > lines = new ArrayList<>();
	private boolean cells = true;
	public WorldSnapshot(){
		lines.add( HEADER );
	}
	public WorldSnapshot cells( boolean enabled ){
		cells = enabled;
		return this;
	}
	public void dungeonSeed( long seed ){
		lines.add( "# dungeonSeed=" + seed );
	}
	public WorldSnapshot specials( List< String > run, List< String > floor ){
		lines.add( "specials\trun=" + String.join( ",", run )
				+ "\tfloor=" + String.join( ",", floor ) );
		return this;
	}
	public WorldSnapshot sample( String kind, int label ){
		if (kind.equals( "step" )) lastStepLabel = label;
		String prefix = kind + "\t" + label + "\t";
		lines.add( prefix + hero() );
		lines.add( prefix + roster() );
		lines.add( prefix + specialRooms() );
		if (cells) sampleCells( prefix, label );
		return this;
	}
	private String specialRooms(){
		return "specials\trun=" + poolNames( SpecialRoom.runSpecials )
				+ "\tfloor=" + poolNames( SpecialRoom.floorSpecials );
	}
	private static String poolNames( List< ? > pool ){
		if (pool == null || pool.isEmpty()) return "-";
		StringBuilder sb = new StringBuilder();
		for (Object room : pool){
			if (sb.length() > 0) sb.append( ',' );
			sb.append( room instanceof Class ? ((Class<?>) room).getSimpleName()
					: String.valueOf( room ) );
		}
		return sb.toString();
	}
	public WorldSnapshot frame( String kind, int label, float elapsed, Object drain, boolean appliedStep ){
		lines.add( kind + "\t" + label + "\tdriver\telapsed=" + elapsed
				+ "\tdrain=" + (drain == null ? "-" : drain.toString())
				+ "\tapplied=" + appliedStep );
		return this;
	}
	public WorldSnapshot gate( boolean readyToAct, String currentActor, int animating ){
		lines.add( "gate\t" + gateLabel++ + "\tgate\tready=" + readyToAct
				+ "\tcurrent=" + currentActor
				+ "\tanimating=" + animating );
		return this;
	}
	private int gateLabel;
	public WorldSnapshot accepted( boolean accepted ){
		lines.add( "step\t" + lastStepLabel + "\taccepted\taccepted=" + accepted );
		return this;
	}
	private int lastStepLabel = -1;
	public WorldSnapshot turns( float previousNow, float now, String taker, int spent ){
		String delta = Float.isNaN( previousNow ) ? "-" : String.valueOf( now - previousNow );
		lines.add( "frame\t" + turnsLabel++ + "\tturns\tnow=" + now
				+ "\tdelta=" + delta
				+ "\ttaker=" + taker
				+ "\tspent=" + spent );
		return this;
	}
	private int turnsLabel;
	public WorldSnapshot clock( String kind, int label, float playerElapsed, float gameElapsed,
			float timeTotal, float timeScale ){
		lines.add( kind + "\t" + label + "\tclock\tplayer=" + playerElapsed
				+ "\tgame=" + gameElapsed
				+ "\ttotal=" + timeTotal
				+ "\tscale=" + timeScale );
		return this;
	}
	private String hero(){
		Hero h = Dungeon.hero;
		if (h == null) return "hero\t-";
		StringBuilder sb = new StringBuilder( "hero\tpos=" ).append( h.pos );
		sb.append( " hp=" ).append( h.HP ).append( '/' ).append( h.HT );
		sb.append( " turn=" ).append( Actor.now() );
		sb.append( " depth=" ).append( Dungeon.depth ).append( " branch=" ).append( Dungeon.branch );
		sb.append( " gold=" ).append( Dungeon.gold );
		sb.append( " lvl=" ).append( h.lvl ).append( " exp=" ).append( h.exp );
		sb.append( " str=" ).append( h.STR() );
		sb.append( " paral=" ).append( h.paralysed ).append( " invis=" ).append( h.invisible );
		sb.append( " rooted=" ).append( h.rooted ).append( " flying=" ).append( h.flying );
		sb.append( " resting=" ).append( h.resting ).append( " ready=" ).append( h.ready );
		sb.append( " starving=" ).append( h.isStarving() );
		sb.append( " buffs=" ).append( buffs( h ) );
		sb.append( " inv=" ).append( inventory( h.belongings ) );
		return sb.toString();
	}
	private String roster(){
		Level level = Dungeon.level;
		if (level == null) return "roster\t-";
		int heroPos = Dungeon.hero == null ? -1 : Dungeon.hero.pos;
		List< String > rows = new ArrayList<>();
		for (Mob mob : level.mobs) rows.add( mobRow( mob, heroPos ) );
		for (Blob blob : level.blobs.values()) rows.add( blobRow( blob ) );
		Collections.sort( rows );
		return "roster\tn=" + rows.size() + "\t" + String.join( "\t", rows );
	}
	private String mobRow( Mob mob, int heroPos ){
		StringBuilder sb = new StringBuilder( "mob\t" ).append( mob.getClass().getSimpleName() );
		sb.append( "\tid=" ).append( mob.id() );
		sb.append( "\tpos=" ).append( mob.pos );
		sb.append( "\thp=" ).append( mob.HP ).append( '/' ).append( mob.HT );
		sb.append( "\talign=" ).append( mob.alignment );
		sb.append( "\tstate=" ).append( mob.state == null ? "none" : mob.state.getClass().getSimpleName() );
		sb.append( "\tcd=" ).append( mob.cooldown() );
		sb.append( "\tdead=" ).append( !mob.isAlive() );
		sb.append( "\tsees=" ).append( seesHero( mob, heroPos ) );
		sb.append( "\tseen=" ).append( mob.enemySeen() );
		sb.append( "\talerted=" ).append( mob.alerted() );
		Char enemy = mob.currentEnemy();
		sb.append( "\ttarget=" ).append( enemy == null ? "-" : enemy.getClass().getSimpleName() );
		sb.append( "\tbuffs=" ).append( buffs( mob ) );
		return sb.toString();
	}
	private String blobRow( Blob blob ){
		return "blob\t" + blob.getClass().getSimpleName()
				+ "\tvol=" + blob.volume
				+ "\tarea=" + blob.area.width() + "x" + blob.area.height()
				+ "\tcd=" + blob.cooldown();
	}
	private static String seesHero( Mob mob, int heroPos ){
		if (mob.fieldOfView == null) return "unknown";
		if (heroPos < 0 || heroPos >= mob.fieldOfView.length) return "unknown";
		return mob.fieldOfView[heroPos] ? "Y" : "N";
	}
	private void sampleCells( String prefix, int label ){
		Level level = Dungeon.level;
		if (level == null) return;
		int described = 0;
		for (int cell = 0; cell < level.length(); cell++){
			String row = cellRow( level, cell );
			if (row == null) continue;
			lines.add( prefix + row );
			described++;
		}
		lines.add( prefix + "cells\tmap=" + level.width() + "x" + level.height()
				+ "\tdescribed=" + described
				+ "\tterrain=" + terrainFingerprint( level )
				+ "\tvis=" + booleanFingerprint( level.heroFOV )
				+ "\texpl=" + booleanFingerprint( level.visited )
				+ "\tpass=" + booleanFingerprint( level.passable ) );
	}
	private String cellRow( Level level, int cell ){
		Heap heap = level.heaps.get( cell );
		Trap trap = level.traps.get( cell );
		Plant plant = level.plants.get( cell );
		boolean pit = level.pit[ cell ];
		boolean plain = heap == null && trap == null && plant == null && !pit
				&& level.getTransition( cell ) == null
				&& level.visited[cell] && !level.heroFOV[cell] && !level.passable[cell];
		if (plain) return null;
		StringBuilder sb = new StringBuilder( "cell\t" ).append( cell );
		sb.append( "\tx=" ).append( cell % level.width() );
		sb.append( "\ty=" ).append( cell / level.width() );
		sb.append( "\ttier=" ).append( level.tileName( level.map[cell] ) );
		sb.append( "\tvis=" ).append( flag( level.heroFOV, cell ) );
		sb.append( "\texpl=" ).append( flag( level.visited, cell ) );
		sb.append( "\tpass=" ).append( flag( level.passable, cell ) );
		sb.append( "\tlos=" ).append( flag( level.losBlocking, cell ) );
		if (pit) sb.append( "\tpit=1" );
		if (level.getTransition( cell ) != null ){
			sb.append( "\ttrans=" ).append( level.getTransition( cell ).getClass().getSimpleName() );
		}
		if (heap != null && !heap.isEmpty()){
			sb.append( "\theap=" ).append( heapItems( heap.items ) );
			sb.append( "\theapSeen=" ).append( heap.seen );
		}
		if (trap != null){
			sb.append( "\ttrap=" ).append( trap.getClass().getSimpleName() );
			sb.append( "\tactive=" ).append( trap.active );
			sb.append( "\ttrapVis=" ).append( trap.visible );
		}
		if (plant != null) sb.append( "\tplant=" ).append( plant.getClass().getSimpleName() );
		return sb.toString();
	}
	private static String terrainFingerprint( Level level ){
		long h = 0xcbf29ce484222325L;
		for (int cell = 0; cell < level.length(); cell++){
			int v = (level.map[cell] << 1) | (level.passable[cell] ? 1 : 0);
			h ^= v & 0xff;
			h *= 0x100000001b3L;
			h ^= (v >>> 8) & 0xff;
			h *= 0x100000001b3L;
		}
		return Long.toHexString( h );
	}
	private static String booleanFingerprint( boolean[] arr ){
		long h = 0xcbf29ce484222325L;
		for (int i = 0; i < arr.length; i++){
			h ^= arr[i] ? 1 : 0;
			h *= 0x100000001b3L;
		}
		return Long.toHexString( h );
	}
	private static String flag( boolean[] arr, int cell ){
		return cell < arr.length && arr[cell] ? "1" : "0";
	}
	private static String buffs( Char ch ){
		List< String > names = new ArrayList<>();
		for (Buff b : ch.buffs()) names.add( b.getClass().getSimpleName() + ":" + b.cooldown() );
		Collections.sort( names );
		return names.isEmpty() ? "-" : String.join( ",", names );
	}
	private static String inventory( Belongings b ){
		StringBuilder sb = new StringBuilder();
		appendItem( sb, b.weapon(), "weapon" );
		appendItem( sb, b.armor(), "armor" );
		appendItem( sb, b.artifact(), "artifact" );
		appendItem( sb, b.misc(), "misc" );
		appendItem( sb, b.ring(), "ring" );
		appendItem( sb, b.secondWep(), "secondWep" );
		if (b.backpack != null) walk( sb, b.backpack.items, "backpack", 0 );
		return sb.length() == 0 ? "-" : sb.toString();
	}
	private static void walk( StringBuilder sb, List< Item > items, String path, int depth ){
		if (depth > MAX_BAG_DEPTH) return;
		for (int i = 0; i < items.size(); i++) appendItem( sb, items.get( i ), path + "/" + i );
	}
	private static void appendItem( StringBuilder sb, Item item, String path ){
		if (item == null) return;
		sb.append( path ).append( '=' ).append( item.getClass().getSimpleName() );
		sb.append( "(l" ).append( item.level() ).append( "/t" ).append( item.trueLevel() );
		sb.append( " q" ).append( item.quantity() );
		sb.append( " v" ).append( item.value() );
		if (item.cursed) sb.append( " cursed" );
		if (item.cursedKnown) sb.append( " cursedKnown" );
		if (item.isIdentified()) sb.append( " identified" );
		sb.append( ") " );
		if (item instanceof Bag){
			walk( sb, ((Bag)item).items, path + "/" + item.getClass().getSimpleName(), depth( path ) );
		}
	}
	private static int depth( String path ){
		int n = 0;
		for (int i = 0; i < path.length(); i++) if (path.charAt( i ) == '/' ) n++;
		return n;
	}
	private static String heapItems( List< Item > list ){
		StringBuilder sb = new StringBuilder();
		for (Item i : list) sb.append( i.getClass().getSimpleName() ).append( 'x' ).append( i.quantity() ).append( ',' );
		return sb.length() == 0 ? "-" : sb.toString();
	}
	public List< String > lines(){
		return lines;
	}
	public String render(){
		return String.join( "\n", lines ) + "\n";
	}
	public void writeTo( Path path ) throws IOException {
		Files.write( path, render().getBytes( StandardCharsets.UTF_8 ) );
	}
	public static Map< String, Map< String, List< String > > > parse( List< String > raw ){
		Map< String, Map< String, List< String > > > out = new LinkedHashMap<>();
		for (String line : raw){
			if (line.isEmpty() || line.startsWith( "#" )) continue;
			String[] parts = line.split( "\t", 3 );
			if (parts.length < 3) continue;
			String name = parts[2].split( "\t" )[0];
			out.computeIfAbsent( parts[0] + ":" + parts[1], k -> new LinkedHashMap<>() )
					.computeIfAbsent( name, n -> new ArrayList<>() )
					.add( line );
		}
		return out;
	}
	public static WorldSnapshot read( Path path ) throws IOException {
		WorldSnapshot s = new WorldSnapshot();
		s.lines.clear();
		s.lines.addAll( Files.readAllLines( path, StandardCharsets.UTF_8 ) );
		return s;
	}
	public static WorldSnapshot capture( int stepIndex, String phase, boolean includeCells ){
		WorldSnapshot snapshot = new WorldSnapshot().cells( includeCells );
		snapshot.dungeonSeed( Dungeon.seed );
		return snapshot.sample( phase, stepIndex );
	}
}
