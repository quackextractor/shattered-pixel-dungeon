package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.DescentBot;

import java.util.ArrayList;
import java.util.List;

public final class WorldSweep {

	public static final int[] DEPTHS = { 1, 3, 7, 12, 17, 22 };
	public static final int SEEDS = 2;
	public static final int STEPS = 150;

	private WorldSweep(){}

	public static List< Scenario > all( MechanicLedger ledger ){
		List< Scenario > all = new ArrayList<>();
		for (int k = 0; k < SEEDS; k++){
			for (int depth : DEPTHS){
				for (HeroClass hero : HeroClass.values()) all.add( sweep( hero, depth, k, ledger ));
			}
		}
		return all;
	}

	static Scenario find( String id ){
		if (id == null || !id.startsWith( "sweep." )) return null;
		for (Scenario scenario : all( new MechanicLedger() )) if (scenario.id.equals( id )) return scenario;
		return null;
	}

	private static Scenario sweep( HeroClass hero, int depth, int k, MechanicLedger ledger ){
		String id = "sweep." + hero.name().toLowerCase() + ".d" + depth + "." + k;
		boolean teacher = k % 2 == 0;
		Scenario scenario = new Scenario( id, (teacher ? "teacher" : "uniform policy") + " plays floor " + depth, hero,
				( world, kit ) -> kit.level( Math.max( 1, depth ) ),
				d -> play( d, ledger, teacher, id.hashCode() ) ).seeds( "SWEEP-" + k ).botDriven( STEPS );
		return depth > 1 ? scenario.depth( depth ) : scenario;
	}

	private static void play( Driver d, MechanicLedger ledger, boolean teacher, long seed ){
		DescentBot bot = new DescentBot();
		UniformPolicy uniform = new UniformPolicy( seed );
		int[] secondary = new int[ 1 ];
		ledger.begin();
		try {
			for (int i = 0; i < STEPS && d.env().running(); i++){
				Action action = teacher ? bot.act( d.env(), secondary ) : uniform.choose( d.env(), secondary );
				Action primitive = action.kind == Action.Kind.MACRO ? bot.primitive( action ) : action;
				ledger.before( d.env(), primitive, secondary[ 0 ] );
				d.step( primitive, secondary[ 0 ] );
			}
		} finally {
			ledger.end();
		}
	}
}
