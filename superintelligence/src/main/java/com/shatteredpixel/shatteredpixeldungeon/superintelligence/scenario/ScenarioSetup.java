package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.LevelPipeline;
import com.watabou.utils.Random;

public final class ScenarioSetup {

	private ScenarioSetup(){}

	public interface Hook {
		boolean jump();
		void build();
	}

	public static boolean known( String id ){
		return ScenarioCatalog.find( id ) != null;
	}

	public static Hook hookFor( String id ){
		Scenario scenario = id == null || id.isEmpty() ? null : ScenarioCatalog.find( id );
		return scenario == null ? null : hookFor( scenario );
	}

	public static Hook hookFor( Scenario scenario ){
		return new Hook() {
			@Override public boolean jump(){
				return false;
			}

			@Override public void build(){
				Random.pushGenerator( Dungeon.seed ^ scenario.id.hashCode() );
				try {
					scenario.setup.build( new World(), new HeroKit() );
				} finally {
					Random.popGenerator();
				}
				LevelPipeline.attachBlobEmitters();
				Dungeon.observe();
			}
		};
	}
}
