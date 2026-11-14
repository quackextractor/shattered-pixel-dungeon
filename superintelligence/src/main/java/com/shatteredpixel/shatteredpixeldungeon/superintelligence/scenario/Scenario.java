package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;

public final class Scenario {

	public interface Setup {
		void build( World world, HeroKit kit );
	}

	public interface Script {
		void run( Driver driver );
	}

	private static final String[] DEFAULT_SEEDS = { "SCN-A", "SCN-B", "SCN-C", "SCN-D", "SCN-E", "SCN-F" };

	public final String id;
	public final String mechanic;
	public final HeroClass hero;
	public final Setup setup;
	public final Script script;
	public String[] seeds = DEFAULT_SEEDS;
	public int depth = 0;
	public boolean dirtyEnd = false;

	public Scenario( String id, String mechanic, HeroClass hero, Setup setup, Script script ){
		this.id = id;
		this.mechanic = mechanic;
		this.hero = hero;
		this.setup = setup;
		this.script = script;
	}

	public Scenario seeds( String... seeds ){
		this.seeds = seeds;
		return this;
	}

	public Scenario dirty(){
		this.dirtyEnd = true;
		return this;
	}

	public Scenario depth( int depth ){
		this.depth = depth;
		return this;
	}
}
