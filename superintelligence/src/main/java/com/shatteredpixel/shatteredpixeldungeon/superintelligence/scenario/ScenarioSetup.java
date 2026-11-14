package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.LevelPipeline;
import com.watabou.utils.Random;

public final class ScenarioSetup {

	private ScenarioSetup(){}

	public interface Hook {
		boolean jump();
		boolean landed();
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
				if (landed()) return false;
				com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene.clearPendingCellListener();
				return Dungeon.level.activateTransition( Dungeon.hero, new LevelTransition( Dungeon.level, Dungeon.hero.pos,
						LevelTransition.Type.REGULAR_EXIT, scenario.depth, 0, LevelTransition.Type.REGULAR_ENTRANCE ));
			}

			@Override public boolean landed(){
				return scenario.depth <= 0 || (Dungeon.depth == scenario.depth && Dungeon.hero != null);
			}

			@Override public void build(){
				Random.pushGenerator( Dungeon.seed ^ scenario.id.hashCode() );
				try {
					scenario.setup.build( new World(), new HeroKit() );
				} finally {
					Random.popGenerator();
				}
				LevelPipeline.attachBlobEmitters();
				LevelPipeline.sizeHeroView();
				Dungeon.observe();
			}
		};
	}
}
