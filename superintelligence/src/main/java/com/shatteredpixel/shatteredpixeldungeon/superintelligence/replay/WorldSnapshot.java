/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */

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

/**
 * Per-step record of everything the world contains, for diffing a headless run against the rendered
 * viewer.
 *
 * <p>Every other comparison in the verification suite compares four fields: hero position, health,
 * engine time, inventory. That is enough to detect a divergence and never enough to explain one. The
 * reporting tool says "hp is 19, recording says 20" and stops, which is the same information a run
 * that died to a mob it should have dodged would give. This records the whole world, so the question
 * becomes answerable: which actor moved, which cell changed, which item appeared, at which step.
 *
 * <p>Deliberately a diagnostic, not a gate. It is large - a full floor is thousands of cells - and its
 * shape changes whenever the level model does, so a golden would need regenerating for unrelated
 * reasons. {@link WorldDiff} is what compares two of these; nothing asserts on one.
 *
 * <p>Two record kinds share the file, because a step alone cannot see the fault this exists for:
 *
 * <ul>
 *   <li>{@code step} - the world after a recorded step settled. The trainer's {@code StepObserver} and
 *       the viewer's {@code ReplayPlayer.settle} both emit these, and they line up one to one.</li>
 *   <li>{@code frame} - the world at a frame boundary, whether or not that frame applied a step. Only
 *       the viewer has frames. A frame whose state differs from the previous frame's is the only place
 *       a world change that no recorded step accounts for can appear.</li>
 * </ul>
 */
public class WorldSnapshot {

	private static final String HEADER =
			"# spd-world-snapshot v1\trecord\tlabel\tfields";

	/** Guards against a bag that contains itself, which would otherwise recurse until the stack dies. */
	private static final int MAX_BAG_DEPTH = 8;

	private final List< String > lines = new ArrayList<>();

	/** Whether per-cell terrain is recorded. Off reduces a floor-sized record to a roster. */
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

	/**
	 * Records the world as it stands.
	 *
	 * @param kind  {@code step} or {@code frame}
	 * @param label step index, or frame counter. Keyed rather than positional, so a run that stopped
	 *              early compares honestly against one that did not.
	 */
	public WorldSnapshot sample( String kind, int label ){
		String prefix = kind + "\t" + label + "\t";
		lines.add( prefix + hero() );
		lines.add( prefix + roster() );
		if (cells) sampleCells( prefix, label );
		return this;
	}

	/**
	 * Adds the viewer's own per-frame accounting to a frame record.
	 *
	 * <p>The frame delta and what the drain concluded are what turn "31 frames passed and nothing
	 * happened" into an explanation. Without them the gap is only measurable in wall-clock, and the two
	 * candidate explanations - a long animation, or the drain waiting on something else - are
	 * indistinguishable from the world state alone.
	 *
	 * @param elapsed     frame delta in seconds as the player saw it
	 * @param drain       what the scheduler drain returned, or null when it did not run
	 * @param appliedStep whether this frame applied a recorded step
	 */
	public WorldSnapshot frame( String kind, int label, float elapsed, Object drain, boolean appliedStep ){
		lines.add( kind + "\t" + label + "\tdriver\telapsed=" + elapsed
				+ "\tdrain=" + (drain == null ? "-" : drain.toString())
				+ "\tapplied=" + appliedStep );
		return this;
	}

	/**
	 * Records the step gate's own inputs on a frame record.
	 *
	 * <p>The three fields together are what a two-sided diff cannot show. Whether a recorded step was
	 * applied while some actor was mid-turn, and while an animation was in flight, is the whole question
	 * of whether the viewer honours the trainer's ordering - and it is invisible from world state alone,
	 * because the world looks identical on both sides until the ordering has already gone wrong.
	 *
	 * <p>Read at the frame boundary, so it describes the moment the gate was consulted.
	 */
	public WorldSnapshot gate( boolean readyToAct, String currentActor, int animating ){
		lines.add( "gate\t" + gateLabel++ + "\tgate\tready=" + readyToAct
				+ "\tcurrent=" + currentActor
				+ "\tanimating=" + animating );
		return this;
	}

	/** Separate counter so a gate line cannot be mistaken for a frame record at the same label. */
	private int gateLabel;

	/**
	 * Records what the animation clock actually saw on a frame.
	 *
	 * <p>{@code Game.elapsed} is what every animation accumulates and {@code timeTotal} is its running
	 * sum, so their per-frame difference is precisely what {@code MovieClip} advanced by. Recording it is
	 * the only way to tell a pinned frame clock that landed from one that did not: the player's own
	 * pacing would look correct either way, and an animation still running on real frame time would show
	 * up nowhere else.
	 *
	 * @param playerElapsed what {@link ReplayPlayer} was handed, which may differ from the game's own
	 * @param gameElapsed   {@code Game.elapsed} at the frame boundary
	 */
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

	/**
	 * The mob and blob roster.
	 *
	 * <p>Sorted because a list's order is not a fact about the world: two runs can spawn the same mobs
	 * in a different order and would then report every mob as changed. {@code EpisodeDiff} sorts for
	 * the same reason.
	 *
	 * <p>{@link Actor#id()} assigns lazily on first read, so reading it here consumes ids an actor that
	 * never asked would otherwise have taken. Harmless - ids are compared within a run, never across
	 * runs - and it is the only stable handle a mob has, {@code Level.mobs} being a set of identity.
	 */
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
		//Three-state, not two: fieldOfView is allocated lazily, so a mob that has never seen anything is
		//a different fact from one that has and cannot see. EpisodeDiff makes the same point.
		sb.append( "\tsees=" ).append( seesHero( mob, heroPos ) );
		sb.append( "\tseen=" ).append( mob.enemySeen() );
		sb.append( "\talerted=" ).append( mob.alerted() );
		Char enemy = mob.currentEnemy();
		sb.append( "\ttarget=" ).append( enemy == null ? "-" : enemy.getClass().getSimpleName() );
		sb.append( "\tbuffs=" ).append( buffs( mob ) );
		return sb.toString();
	}

	/**
	 * Blobs have no cell - they occupy an area and a volume of water - so what is recorded is the volume
	 * and the extent, not a position. A gas cloud that spread further than the recording says is a real
	 * divergence and this catches it.
	 */
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

	/**
	 * Per-cell state for the whole floor.
	 *
	 * <p>Iterates the level arrays directly rather than reusing {@code ObservationEncoder}, because the
	 * encoder crops a window around the hero and rescales it onto the network's grid - several real
	 * cells collapse onto one grid cell there, so it cannot represent a per-cell difference at all. It
	 * is still the reference for <i>which</i> fields matter and how FOV gating works, and both are
	 * followed here.
	 */
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

		//The overwhelming majority of cells are bare floor, and a line each for all of them buries the
		//few that carry anything. Recorded only when the cell has content, a transition, or is not simply
		//explored-and-empty - so terrain, FOV and passability outside this set are implied rather than
		//enumerated. A terrain difference on a plain cell is therefore missed, which the fingerprint in
		// the trailing cells record covers instead.
		boolean plain = heap == null && trap == null && plant == null
				&& level.getTransition( cell ) == null
				&& level.visited[cell] && !level.heroFOV[cell] && !level.passable[cell];
		if (plain) return null;

		StringBuilder sb = new StringBuilder( "cell\t" ).append( cell );
		sb.append( "\tx=" ).append( cell % level.width() );
		sb.append( "\ty=" ).append( cell / level.width() );
		//Named rather than the raw id: the id indexes a table that only grows, so a diff across two builds
		//would compare numbers that no longer mean the same thing. Level.tileName is the game's spelling.
		sb.append( "\ttier=" ).append( level.tileName( level.map[cell] ) );
		sb.append( "\tvis=" ).append( flag( level.heroFOV, cell ) );
		sb.append( "\texpl=" ).append( flag( level.visited, cell ) );
		sb.append( "\tpass=" ).append( flag( level.passable, cell ) );
		sb.append( "\tlos=" ).append( flag( level.losBlocking, cell ) );

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

	/**
	 * An order-sensitive fingerprint over every cell's terrain and passability.
	 *
	 * <p>Covers what {@link #cellRow} deliberately omits: a tile swapped for another on a plain explored
	 * cell produces no row, so without this a real terrain divergence would be invisible.
	 */
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

	/** Same fold over a boolean array, for the three per-cell masks the row records omit. */
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

	/**
	 * The whole inventory, path-qualified, so an item inside a bag is distinguishable from the same item
	 * loose in the backpack.
	 *
	 * <p>{@code Belongings} iterates equipment first in fixed slot order then the backpack, and
	 * {@code Bag.iterator()} already recurses - but flattening loses the path, so two VelvetPouches
	 * holding different things render identically. {@code ReplayRecorder.inventory()} shares that blind
	 * spot and additionally walks only the backpack.
	 */
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
		//Both levels, because they differ: level() hides a level on an unidentified upgradable item and
		//trueLevel() does not, so a diff reporting only one would call those two states equal.
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

	/** Parsed into records keyed by {@code kind:label}, then by record name. */
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
}