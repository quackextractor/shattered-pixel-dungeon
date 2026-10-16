package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.StallCause;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardLedger;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class RewardFixtures {

	static final List< String > failures = GateReport.failures();

	private RewardFixtures(){}

	static class Outcome {
		double score;
		int turns;
		int deepest;
		RewardModel.TerminateReason endReason = RewardModel.TerminateReason.OTHER;
		boolean endedNaturally;
		StallCause stallCause = StallCause.NONE;
		String startMode = "?";
		int refusals;
		int worldTurns;
		RewardLedger ledger;

		double termTotal( RewardTerm term ){
			return ledger == null ? 0 : ledger.termTotal( term );
		}

		int notes( RewardTerm term ){
			return ledger == null ? 0 : ledger.notes( term );
		}
	}

	static class Turn {
		final SPDEnv env;
		int step;

		Turn( SPDEnv env ){ this.env = env; }
	}

	interface Driver {
		void act( Turn turn );
	}

	static Outcome play( EnvConfig config, int maxTurns, String seed, Driver driver ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-rewardcheck" ));
		HeadlessServices.disableSaving( true );

		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( seed, HeroClass.WARRIOR );

		Turn turn = new Turn( env );
		Outcome outcome = new Outcome();
		outcome.startMode = env.mode().name();

		int n = 0;
		while (env.running() && n < maxTurns){
			turn.step = n;
			driver.act( turn );
			n++;
		}

		outcome.score = env.ledger().total();
		outcome.turns = env.turnsTotal();
		outcome.deepest = env.deepestDepth();
		outcome.endReason = env.endReason();
		outcome.endedNaturally = env.endedNaturally();
		outcome.stallCause = env.stallCause();
		outcome.ledger = env.ledger();
		return outcome;
	}

	static Outcome playWaits( EnvConfig config, int maxTurns ){
		return play( config, maxTurns, "REWARDCHECK", turn -> turn.env.step( Action.WAIT, 0 ));
	}

	static Outcome playInWorld( EnvConfig config, Action action, int maxTurns ){
		Outcome counted = new Outcome();

		Outcome o = play( config, maxTurns, "REWARDCHECK", turn -> {
			if (turn.env.mode() != EnvMode.WORLD ){
				turn.env.step( Action.CANCEL, 0 );
				return;
			}

			turn.env.step( action, 0 );
			counted.worldTurns++;
			counted.refusals += turn.env.ledger().notes( RewardTerm.INVALID_ACTION );
		} );

		o.worldTurns = counted.worldTurns;
		o.refusals = counted.refusals;
		return o;
	}

	static Action legalMove( SPDEnv env ){
		float[] mask = env.actionMask();
		for (Action a : Action.values()){
			if (a.directional() && a.index < mask.length && mask[ a.index ] != 0f ) return a;
		}
		return Action.SEARCH;
	}

	static boolean descend( SPDEnv env ){
		com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition exit =
				Dungeon.level.getTransition(
						com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition.Type.REGULAR_EXIT );
		if (exit == null) return false;

		Hero hero = Dungeon.hero;
		hero.pos = exit.cell();
		boolean accepted = Dungeon.level.activateTransition( hero, exit );
		hero.ready = false;
		return accepted;
	}

	static boolean ascend( SPDEnv env ){
		com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition entrance =
				Dungeon.level.getTransition(
						com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition.Type.REGULAR_ENTRANCE );
		if (entrance == null) return false;

		Hero hero = Dungeon.hero;
		hero.pos = entrance.cell();
		boolean accepted = Dungeon.level.activateTransition( hero, entrance );
		hero.ready = false;
		return accepted;
	}

	static boolean placeMobBesideHero( SPDEnv env ){
		if (Dungeon.level == null || Dungeon.hero == null) return false;

		Hero hero = Dungeon.hero;
		int w = Dungeon.level.width();

		for (int off : adjacent( w )){
			int cell = hero.pos + off;
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			if (!Dungeon.level.insideMap( cell )) continue;
			if (!Dungeon.level.passable[ cell ]) continue;

			if (Math.abs( cell % w - hero.pos % w ) > 1) continue;
			if (Actor.findChar( cell ) != null) continue;
			if (Dungeon.level.heaps.get( cell ) != null) continue;

			Mob mob = Dungeon.level.createMob();
			if (mob == null) continue;

			if (Char.hasProp( mob, Char.Property.LARGE ) && !Dungeon.level.openSpace[ cell ]) continue;
			if (mob.state != mob.PASSIVE) mob.state = mob.WANDERING;
			mob.pos = cell;

			com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.LevelPipeline.spriteFor( mob );
			Actor.add( mob );
			Dungeon.level.mobs.add( mob );

			Dungeon.observe();
			return true;
		}
		return false;
	}

	static Mob adjacentEnemy(){
		Hero hero = Dungeon.hero;
		if (hero == null || Dungeon.level == null) return null;

		int w = Dungeon.level.width();

		for (int off : adjacent( w )){
			int cell = hero.pos + off;
			if (cell < 0 || cell >= Dungeon.level.length()) continue;
			Char ch = Actor.findChar( cell );
			if (ch instanceof Mob && ((Mob) ch).isAlive()
					&& ((Mob) ch).alignment == Char.Alignment.ENEMY){
				return (Mob) ch;
			}
		}
		return null;
	}

	static Action directionToTarget( SPDEnv env, Mob mob ){
		Hero hero = Dungeon.hero;
		int w = Dungeon.level.width();

		int dx = Integer.signum( mob.pos % w - hero.pos % w );
		int dy = Integer.signum( mob.pos / w - hero.pos / w );

		for (Action a : compass()){
			if (a.dx == dx && a.dy == dy && a.index < env.actionMask().length
					&& env.actionMask()[ a.index ] != 0f ){
				return a;
			}
		}

		for (Action a : compass()){
			if (a.index < env.actionMask().length && env.actionMask()[ a.index ] != 0f ) return a;
		}
		return Action.SEARCH;
	}

	private static Action[] compass(){
		return new Action[]{ Action.MOVE_N, Action.MOVE_E, Action.MOVE_S, Action.MOVE_W,
				Action.MOVE_NE, Action.MOVE_SE, Action.MOVE_SW, Action.MOVE_NW };
	}

	private static int[] adjacent( int width ){
		return new int[]{ -width, width, -1, 1, -width - 1, -width + 1, width - 1, width + 1 };
	}

	static int equippableSlot( SPDEnv env ){
		Hero hero = Dungeon.hero;
		if (hero == null) return 0;

		List< com.shatteredpixel.shatteredpixeldungeon.items.Item > slots = env.mapper().slots();
		for (int i = 0; i < slots.size(); i++){
			com.shatteredpixel.shatteredpixeldungeon.items.Item item = slots.get( i );
			if (item instanceof com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem
					&& !item.isEquipped( hero )){
				return i;
			}
		}
		return 0;
	}

	static int freeSteps( Outcome o ){
		return o.notes( RewardTerm.NO_PROGRESS );
	}

	static String fmt( double v ){
		return String.format( java.util.Locale.ROOT, "%.2f", v );
	}

	static String fine( double v ){
		return String.format( java.util.Locale.ROOT, "%.4f", v );
	}

	static void fail( String message ){
		GateReport.fail( message );
	}
}