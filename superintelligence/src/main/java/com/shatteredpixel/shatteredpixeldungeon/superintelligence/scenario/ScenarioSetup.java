package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

public final class ScenarioSetup {

	private ScenarioSetup(){}

	public static boolean known( String id ){
		return ScenarioCatalog.find( id ) != null;
	}
}
