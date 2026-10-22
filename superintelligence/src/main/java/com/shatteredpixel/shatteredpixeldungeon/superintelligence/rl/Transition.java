package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.GridChannel;
import java.util.ArrayDeque;
public class Transition {
	public byte[] grid;
	public int gridCells;
	private static final boolean[] FRACTIONAL = GridChannel.fractionalPlanes();
	public void packGrid( float[] src ){
		java.util.Arrays.fill( grid, (byte) 0 );
		int n = Math.min( src.length, gridCells );
		int area = planeArea( gridCells );
		int levels = bitBytes( gridCells );
		for (int plane = 0; plane < FRACTIONAL.length; plane++){
			int start = plane * area;
			int end = Math.min( n, start + area );
			if (FRACTIONAL[ plane ]){
				for (int i = start; i < end; i++) grid[ levels + i - start ] = (byte) GridChannel.level( src[ i ] );
				levels += area;
				continue;
			}
			for (int i = start; i < end; i++){
				if (src[ i ] <= 0.5f ) continue;
				grid[ i >>> 3 ] |= (byte) (1 << ( i & 7 ));
			}
		}
	}
	public void unpackGrid( float[] dst ){
		int n = Math.min( dst.length, gridCells );
		int area = planeArea( gridCells );
		int levels = bitBytes( gridCells );
		for (int plane = 0; plane < FRACTIONAL.length; plane++){
			int start = plane * area;
			int end = Math.min( n, start + area );
			if (FRACTIONAL[ plane ]){
				for (int i = start; i < end; i++){
					dst[ i ] = ( grid[ levels + i - start ] & 0xFF ) / (float) GridChannel.LEVELS;
				}
				levels += area;
				continue;
			}
			for (int i = start; i < end; i++){
				dst[ i ] = ( ( grid[ i >>> 3 ] >>> ( i & 7 ) ) & 1 ) != 0 ? 1f : 0f;
			}
		}
	}
	public static int packedGridBytes( int cells ){
		int fractional = 0;
		for (boolean f : FRACTIONAL) if (f) fractional++;
		return bitBytes( cells ) + fractional * planeArea( cells );
	}
	private static int bitBytes( int cells ){
		return ( cells + 7 ) >>> 3;
	}
	private static int planeArea( int cells ){
		if (cells % FRACTIONAL.length != 0){
			throw new IllegalArgumentException( cells + " grid cells do not split into "
					+ FRACTIONAL.length + " spatial planes" );
		}
		return cells / FRACTIONAL.length;
	}
	public float[] inventory;
	public float[] hero;
	public float[] actionMask;
	public float[] slotMask;
	public float[] targetMask;
	public float[] recurrentState;
	public int liveHead;
	public int episodeId = -1;
	public int stepIndex = -1;
	public int actionIndex;
	public int slotIndex;
	public int teacherIndex = -1;
	public boolean demonstration;
	public float oldLogProbability;
	public float value;
	public float reward;
	public boolean terminal;
	public float nextValue;
	public float returnValue;
	public float advantage;
	private static final ArrayDeque<Transition> POOL = new ArrayDeque<>();
	private static final int MAX_POOLED = 8192;
	private boolean pooled = false;
	boolean retained;
	public static Transition take( int gridSize, int inventorySize, int heroSize,
			int actionCount, int slotCount, int targetCount, int recurrentStateSize ){
		Transition t = POOL.poll();
		if (t == null) t = new Transition();
		t.gridCells = gridSize;
		t.grid    = fit( t.grid,    packedGridBytes( gridSize ) );
		t.inventory = fit( t.inventory, inventorySize );
		t.hero    = fit( t.hero,    heroSize );
		t.actionMask = fit( t.actionMask, actionCount );
		t.slotMask   = fit( t.slotMask,   slotCount );
		t.targetMask = fit( t.targetMask, targetCount );
		t.recurrentState = recurrentStateSize > 0
				? fit( t.recurrentState, recurrentStateSize )
				: null;
		t.oldLogProbability = 0;
		t.value = 0;
		t.reward = 0;
		t.nextValue = 0;
		t.returnValue = 0;
		t.advantage = 0;
		t.terminal = false;
		t.liveHead = Policy.HEAD_ACTION;
		t.episodeId = -1;
		t.stepIndex = -1;
		t.actionIndex = 0;
		t.slotIndex = 0;
		t.teacherIndex = -1;
		t.demonstration = false;
		t.pooled = false;
		t.retained = false;
		return t;
	}
	public static Transition take( int gridSize, int inventorySize, int heroSize,
			int actionCount, int slotCount, int targetCount ){
		return take( gridSize, inventorySize, heroSize, actionCount, slotCount, targetCount, 0 );
	}
	private static float[] fit( float[] existing, int len ){
		return (existing != null && existing.length == len) ? existing : new float[ len ];
	}
	private static byte[] fit( byte[] existing, int len ){
		return (existing != null && existing.length == len) ? existing : new byte[ len ];
	}
	public void release(){
		if (pooled) return;
		pooled = true;
		if (POOL.size() < MAX_POOLED) POOL.addLast( this );
	}
}
