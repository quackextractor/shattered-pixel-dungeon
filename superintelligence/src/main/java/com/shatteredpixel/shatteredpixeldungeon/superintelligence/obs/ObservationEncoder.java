package com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mimic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.MissileWeapon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.watabou.utils.PathFinder;


public class ObservationEncoder {


	private static final int MARGIN = 2;

	private final EnvConfig config;


	private final float[] grid;

	private final int gridWidth;
	private final int gridHeight;


	private final float[] inventory;


	private final float[] heroFeatures;

	private final ActionMapper mapper;

	public ObservationEncoder( EnvConfig config, ActionMapper mapper ){
		this.config = config;
		this.mapper = mapper;
		this.gridWidth = config.gridWidth;
		this.gridHeight = config.gridHeight;
		this.grid = new float[GridChannel.spatialCount() * gridWidth * gridHeight];
		this.inventory = new float[config.maxSlots * InventoryEncoder.FEATURES_PER_SLOT];
		this.heroFeatures = new float[HeroEncoder.FEATURES];
		this.edges = new float[gridWidth * gridHeight];
		this.PLANE_AREA = gridWidth * gridHeight;
	}

	public float[] grid(){ return grid; }
	public float[] inventory(){ return inventory; }
	public float[] hero(){ return heroFeatures; }

	public int gridWidth(){ return gridWidth; }
	public int gridHeight(){ return gridHeight; }
	public int spatialChannels(){ return GridChannel.spatialCount(); }


	public int gridSize(){
		return grid.length;
	}


	public int encode(){
		Level level = Dungeon.level;
		Hero hero = Dungeon.hero;

		java.util.Arrays.fill( grid, 0f );
		java.util.Arrays.fill( inventory, 0f );
		java.util.Arrays.fill( heroFeatures, 0f );

				int newlyExplored = countNewlyExplored( level );

		buildSpatial( level, hero );
		InventoryEncoder.encode( mapper.slots(), inventory, config.maxSlots );
		HeroEncoder.encode( hero, heroFeatures );

		return newlyExplored;
	}

	private void buildSpatial( Level level, Hero hero ){
		int width = level.width();
		int height = level.height();

		int originX = hero.pos % width - MARGIN;
		int originY = hero.pos / width - MARGIN;
		int spanX = gridWidth + MARGIN * 2;
		int spanY = gridHeight + MARGIN * 2;

		float stepX = (float) spanX / gridWidth;
		float stepY = (float) spanY / gridHeight;

		float scale = Math.max( 1f, Math.max( stepX, stepY ) );
		int x0 = Math.max( 0, originX );
		int y0 = Math.max( 0, originY );
		int x1 = Math.min( width - 1, originX + spanX - 1 );
		int y1 = Math.min( height - 1, originY + spanY - 1 );

		float[] edges = this.edges;

		for (int gy = 0; gy < gridHeight; gy++){
			int sy = y0 + Math.round( gy * stepY );
			if (sy > y1 ) sy = y1;
			if (sy < y0 ) sy = y0;

			for (int gx = 0; gx < gridWidth; gx++){
				int sx = x0 + Math.round( gx * stepX );
				if (sx > x1 ) sx = x1;
				if (sx < x0 ) sx = x0;

				int cell = sx + sy * width;
				int p = gx + gy * gridWidth;

				writeCell( level, hero, cell, p );
				edges[ p ] = edgeStrength( level, cell, width );
			}
		}

		applyEdges();
		applyExitBeacon( level, hero );
	}


	private void applyExitBeacon( Level level, Hero hero ){
		if (level == null || hero == null) return;

		int cell = seenTransitionCell;
		if (cell < 0){
			cell = level.getTransition( null ) != null ? level.getTransition( null ).cell() : -1;
			if (cell < 0 ) return;
			if (!level.visited[ cell ] ) return;
			seenTransitionCell = cell;
		}

		if (!level.visited[ cell ]) return;

		int p = gridIndex( level, hero, cell );
		if (p >= 0) put( GridChannel.EXIT_BEACON, p, 1f );
	}


	private int gridIndex( Level level, Hero hero, int cell ){
		int width = level.width();
		int cx = cell % width;
		int cy = cell / width;

		int hx = hero.pos % width;
		int hy = hero.pos / width;

		int spanX = gridWidth + MARGIN * 2;
		int spanY = gridHeight + MARGIN * 2;
		float stepX = Math.max( 1f, (float) spanX / gridWidth );
		float stepY = Math.max( 1f, (float) spanY / gridHeight );

		int gx = Math.round( (cx - ( hx - MARGIN )) / stepX );
		int gy = Math.round( (cy - ( hy - MARGIN )) / stepY );

		if (gx < 0 || gx >= gridWidth || gy < 0 || gy >= gridHeight ) return -1;
		return gx + gy * gridWidth;
	}

	private void writeCell( Level level, Hero hero, int cell, int p ){
		boolean[] fov = level.heroFOV;
		boolean visible = cell < fov.length && fov[ cell ];
		boolean explored = level.visited[ cell ];
		int terrain = level.map[ cell ];

		put( GridChannel.WALL, p, !level.passable[ cell ] );
		put( GridChannel.OPAQUE, p, level.losBlocking[ cell ] );
		put( GridChannel.VISIBLE, p, visible );
		put( GridChannel.EXPLORED, p, explored );
		put( GridChannel.FOGGED, p, explored && !visible );

		if (visible){
			put( GridChannel.HIGH_GRASS, p, terrain == Terrain.HIGH_GRASS
					|| terrain == Terrain.FURROWED_GRASS );
			put( GridChannel.CHASM, p, terrain == Terrain.CHASM );
			put( GridChannel.ALCHEMY, p, terrain == Terrain.ALCHEMY );
			put( GridChannel.LOCKED_DOOR, p, terrain == Terrain.LOCKED_DOOR
					|| terrain == Terrain.LOCKED_EXIT
					|| terrain == Terrain.CRYSTAL_DOOR
					|| terrain == Terrain.HERO_LKD_DR );
			put( GridChannel.BOULDER, p, terrain == Terrain.MINE_BOULDER
					|| terrain == Terrain.MINE_CRYSTAL );

			Trap trap = level.traps.get( cell );
			if (trap != null && trap.visible){
				put( GridChannel.TRAP_DISARMED, p, !trap.active );
				put( GridChannel.TRAP_ACTIVE, p, trap.active );
			}

			if (level.getTransition( cell ) != null ){
				put( GridChannel.TRANSITION, p, 1f );
			}

			Heap heap = level.heaps.get( cell );
			if (heap != null && !heap.isEmpty()){
				put( GridChannel.HEAP, p, 1f );

				if (heapSeen( heap )) put( GridChannel.GROUND_ITEM, p, groundItem( heap ) );
			}

			Char ch = Actor.findChar( cell );
			if (ch != null && ch != hero){

				boolean shown = visible || mimicStillVisible( ch, visible );
				if (shown){
					switch (ch.alignment){
						case ENEMY:
							put( GridChannel.ENEMY, p, 1f );
							break;
						case ALLY:
							put( GridChannel.ALLY, p, 1f );
							break;
						default:
							put( GridChannel.NEUTRAL, p, 1f );
							break;
					}
					if (isThreatening( (Mob) ch )) put( GridChannel.THREATENING, p, 1f );
				}
			}

			if (cell == hero.pos) put( GridChannel.HERO, p, 1f );
		}
	}


	private boolean mimicStillVisible( Char ch, boolean visible ){
		if (visible) return true;
		if (!config.mimicsVisibleOutOfFog) return false;
		if (!(ch instanceof Mimic)) return false;
		if (!Dungeon.level.visited[ ch.pos ]) return false;
		Mimic mimic = (Mimic) ch;
		return mimic.state == mimic.PASSIVE && mimic.stealthy();
	}

	private boolean heapSeen( Heap heap ){
		return heap.seen;
	}


	private boolean groundItem( Heap heap ){
		for (Item item : heap.items){
			if (item.value() > 0) return true;
			if (item instanceof MissileWeapon) return true;
		}
		return false;
	}


	private boolean isThreatening( Mob mob ){
		if (!mob.isAlive() || mob.alignment == Char.Alignment.ALLY) return false;
		if (mob.invisible > 0) return false;
if (!Dungeon.level.adjacent( mob.pos, Dungeon.hero.pos )) return false;
		return mob.state == mob.HUNTING;
	}


	private float edgeStrength( Level level, int cell, int width ){
		boolean visible = cell < level.heroFOV.length && level.heroFOV[ cell ];
		if (!visible || !level.passable[ cell ]) return 0f;

		float n = 0f;
		for (int step : PathFinder.NEIGHBOURS8){
			int n2 = cell + step;
			if (n2 < 0 || n2 >= level.length()) continue;
			if (!level.heroFOV[ n2 ]) continue;
			if (level.passable[ n2 ]) n++;
		}
		return n / 8f;
	}

	private void applyEdges(){
		int plane = PLANE_INDEX[ GridChannel.VISIBLE_EDGE.ordinal() ];
		if (plane < 0) return;
		System.arraycopy( edges, 0, grid, plane * PLANE_AREA, edges.length );
	}


	private static final int[] PLANE_INDEX = new int[ GridChannel.count() ];
	private static final int PLANE_SIZE;


	private final int PLANE_AREA;

	static {
		int n = 0;
		for (GridChannel c : GridChannel.values()){
			PLANE_INDEX[ c.ordinal() ] = (c.kind == GridChannel.Kind.SCALAR) ? -1 : n++;
		}
		PLANE_SIZE = n;
	}


	private final float[] edges;

	private void put( GridChannel channel, int p, boolean value ){
		put( channel, p, value ? 1f : 0f );
	}

	private void put( GridChannel channel, int p, float value ){
		if (value == 0f) return;
		int plane = PLANE_INDEX[ channel.ordinal() ];
		if (plane < 0) return;
		grid[ plane * PLANE_AREA + p] = value;
	}


	private int lastExplored = 0;
	private int lastDepth = -1;

	private int countNewlyExplored( Level level ){
		int explored = 0;
		for (int i = 0; i < level.length(); i++) if (level.visited[ i ]) explored++;

		if (Dungeon.depth != lastDepth){

			lastDepth = Dungeon.depth;
			lastExplored = explored;
			return explored;
		}

		int delta = explored - lastExplored;
		lastExplored = explored;
		return Math.max( 0, delta );
	}


	public int exploredOnFloor(){
		return lastExplored;
	}


	public void resetExplored(){
		lastExplored = 0;
		lastDepth = -1;
		seenTransitionCell = -1;
	}


	private int seenTransitionCell = -1;
}
