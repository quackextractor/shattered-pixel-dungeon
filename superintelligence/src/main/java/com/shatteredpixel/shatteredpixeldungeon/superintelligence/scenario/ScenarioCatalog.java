package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScenarioCatalog {

	private ScenarioCatalog(){}

	private static final List< Scenario > ALL = new ArrayList<>();

	static {
		ALL.addAll( CombatScenarios.all() );
		ALL.addAll( ModeScenarios.all() );
		ALL.addAll( GapScenarios.all() );
		ALL.addAll( RangedScenarios.all() );
		ALL.addAll( CraftScenarios.all() );
		ALL.addAll( WorldScenarios.all() );
		ALL.addAll( FeatureScenarios.all() );
		ALL.addAll( TrapScenarios.all() );
		ALL.addAll( MobScenarios.all() );
		ALL.addAll( StateScenarios.all() );
		ALL.addAll( EffectScenarios.all() );
		ALL.addAll( LeakScenarios.all() );
		ALL.addAll( DepthScenarios.all() );
	}

	public static List< Scenario > all(){
		return Collections.unmodifiableList( ALL );
	}

	public static Scenario find( String id ){
		for (Scenario scenario : ALL) if (scenario.id.equals( id )) return scenario;
		return WorldSweep.find( id );
	}
}
