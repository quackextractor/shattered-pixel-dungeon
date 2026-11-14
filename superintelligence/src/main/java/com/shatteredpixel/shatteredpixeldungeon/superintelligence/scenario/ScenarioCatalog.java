package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScenarioCatalog {

	private ScenarioCatalog(){}

	private static final List< Scenario > ALL = new ArrayList<>();

	static {
		ALL.addAll( CombatScenarios.all() );
	}

	public static List< Scenario > all(){
		return Collections.unmodifiableList( ALL );
	}

	public static Scenario find( String id ){
		for (Scenario scenario : ALL) if (scenario.id.equals( id )) return scenario;
		return null;
	}
}
