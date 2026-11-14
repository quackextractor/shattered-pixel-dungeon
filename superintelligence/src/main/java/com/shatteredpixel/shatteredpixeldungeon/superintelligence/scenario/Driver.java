package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.ReplayRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class Driver {

	public static final int MAX_STEPS = 120;

	public static final int AIM_NEAREST = ActionMapper.ADJACENT_COUNT;

	static final class Stop extends RuntimeException {
		private static final long serialVersionUID = 1L;

		Stop( String message ){
			super( message );
		}
	}

	private final SPDEnv env;
	private final ReplayRecorder recorder;
	private final List< String > failures = new ArrayList<>();
	private final List< String > gaps = new ArrayList<>();

	private final int maxSteps;

	Driver( SPDEnv env, ReplayRecorder recorder, int maxSteps ){
		this.env = env;
		this.recorder = recorder;
		this.maxSteps = maxSteps;
	}

	public SPDEnv env(){
		return env;
	}

	public World world(){
		return new World();
	}

	List< String > failures(){
		return failures;
	}

	List< String > gaps(){
		return gaps;
	}

	public void gap( String why ){
		gaps.add( "step " + steps() + ": " + why );
	}

	public int steps(){
		return recorder.replay().length();
	}

	public void step( Action action, int secondary ){
		if (!env.running()) throw new Stop( "the episode ended (" + env.endReason() + ") before " + action );
		if (steps() >= maxSteps) throw new Stop( "the script passed " + maxSteps + " steps" );
		String unreachable = PolicyReach.check( env, action, secondary );
		if (unreachable != null) gap( unreachable );
		recorder.record( action, secondary, env.mode() );
		float reward = (float) env.step( action, secondary );
		recorder.afterStep( env.heroPosition(), reward, env.lastStepActed() ? 1 : 0 );
	}

	public void act( Action action ){
		step( action, 0 );
	}

	public void move( int cell ){
		int width = Dungeon.level.width();
		int dx = Integer.signum( cell % width - Dungeon.hero.pos % width );
		int dy = Integer.signum( cell / width - Dungeon.hero.pos / width );
		for (Action action : Action.values()){
			if (action.kind == Action.Kind.WORLD && action.name().startsWith( "MOVE_" )
					&& action.dx == dx && action.dy == dy){
				act( action );
				return;
			}
		}
		throw new Stop( "no move leads from " + Dungeon.hero.pos + " toward " + cell );
	}

	public int direction( int cell ){
		for (int o = 1; o < ActionMapper.ADJACENT_COUNT; o++) if (env.mapper().targetCell( o ) == cell) return o;
		throw new Stop( "cell " + cell + " is not beside the hero" );
	}

	public int slotOf( Class< ? extends Item > type ){
		env.mapper().refreshSlots();
		List< Item > slots = env.mapper().slots();
		for (int i = 0; i < slots.size(); i++){
			if (type.isInstance( slots.get( i ) )) return i;
		}
		return -1;
	}

	public void use( Class< ? extends Item > type ){
		require( env.mode() == EnvMode.WORLD, "USE of " + type.getSimpleName() + " outside WORLD (" + env.mode() + ")" );
		act( Action.USE );
		int slot = slotOf( type );
		if (slot < 0) throw new Stop( "no " + type.getSimpleName() + " in the slot list" );
		step( Action.USE, slot );
	}

	public void drop( Class< ? extends Item > type ){
		act( Action.DROP );
		int slot = slotOf( type );
		if (slot < 0) throw new Stop( "no " + type.getSimpleName() + " in the slot list" );
		step( Action.DROP, slot );
	}

	public void aimNearest(){
		require( env.mode() == EnvMode.TARGETING, "aim outside TARGETING (" + env.mode() + ")" );
		step( Action.USE, AIM_NEAREST );
	}

	public void pick( Class< ? extends Item > type ){
		require( env.mode() == EnvMode.INVENTORY, "pick outside INVENTORY (" + env.mode() + ")" );
		int slot = slotOf( type );
		if (slot < 0) throw new Stop( "no " + type.getSimpleName() + " offered to pick" );
		step( Action.USE, slot );
	}

	public void menu( int option ){
		step( Action.MENU_SELECT, option );
	}

	public void cancel(){
		act( Action.CANCEL );
	}

	public boolean until( BooleanSupplier done, int max, Runnable action ){
		for (int i = 0; i < max && !done.getAsBoolean() && env.running(); i++) action.run();
		return done.getAsBoolean();
	}

	public void waitFor( BooleanSupplier done, int max ){
		until( done, max, () -> act( Action.WAIT ) );
	}

	public void expect( boolean condition, String message ){
		if (!condition) failures.add( message );
	}

	public void require( boolean condition, String message ){
		if (!condition) throw new Stop( message );
	}

	public void fixture( boolean condition, String message ){
		if (!condition) throw new NoFixture( message );
	}
}
