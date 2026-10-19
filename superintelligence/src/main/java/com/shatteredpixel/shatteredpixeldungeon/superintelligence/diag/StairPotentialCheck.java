package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.Curriculum;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardLedger;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.StairPotential;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.RewardFixtures.fail;

public final class StairPotentialCheck {

	private StairPotentialCheck(){}

	public static void checkTheFieldIsBuiltOncePerTerrain(){
		EnvConfig config = new EnvConfig();
		RewardModel model = new RewardModel( config, new RewardLedger(), new Curriculum() );
		RewardFixtures.play( config, 3, "STAIRCACHE", turn -> turn.env.step( Action.WAIT, 0 ) );

		model.resetSnapshot();
		for (int i = 0; i < 5; i++) model.step( 0, true, false );

		StairPotential potential = model.stairPotential();
		if (potential.builds() != 1){
			fail( "five reward steps on one unchanged floor built the stair distance field "
					+ potential.builds() + " time(s). It is a breadth-first search over the whole level"
					+ " and only terrain can change it." );
			return;
		}

		int cell = blockableCell( potential );
		if (cell < 0){
			fail( "no passable cell off the hero and the exit had a finite stair distance, so the"
					+ " terrain-change half measured nothing." );
			return;
		}

		Level.set( cell, Terrain.WALL );
		int after = potential.distanceFrom( cell );
		if (potential.builds() != 2 || after != StairPotential.UNREACHABLE){
			fail( "walling cell " + cell + " left the field at " + potential.builds() + " build(s) with"
					+ " distance " + after + " there, so a terrain change is served from a stale field." );
			return;
		}

		System.out.println( "  the stair field is built once over 5 steps and rebuilt when terrain changes" );
	}

	private static int blockableCell( StairPotential potential ){
		int exit = StairPotential.exitCell( Dungeon.level );
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (cell == exit || cell == Dungeon.hero.pos) continue;
			if (!Dungeon.level.passable[ cell ]) continue;
			int d = potential.distanceFrom( cell );
			if (d > 0 && d != StairPotential.UNREACHABLE) return cell;
		}
		return -1;
	}
}
