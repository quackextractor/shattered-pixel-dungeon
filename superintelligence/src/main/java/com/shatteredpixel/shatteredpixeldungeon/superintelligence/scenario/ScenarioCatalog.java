package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScenarioCatalog {

	private ScenarioCatalog(){}

	private static final List< String > IDS = new ArrayList<>();

	public static List< String > ids(){
		return Collections.unmodifiableList( IDS );
	}

	public static String find( String id ){
		return IDS.contains( id ) ? id : null;
	}
}
