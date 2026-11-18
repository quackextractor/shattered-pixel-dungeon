package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Ghoul;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Shaman;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessScheduler;
import com.watabou.utils.PathFinder;

import java.util.function.Supplier;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GateReport.fail;

final class ActOrderCases {

	static final int CHECKS = 6;

	private static final String SEED = "MOBSPROUT-D";

	private ActOrderCases(){}

	static void run(){
		checkItLandsAfterTheAct( "a Rat's attack", false, OrderRat::new );
		checkItLandsAfterTheAct( "a Ghoul's attack", false, OrderGhoul::new );
		checkItLandsAfterTheAct( "a RedShaman's bolt", true, OrderShaman::new );
		checkAnAttackOutsideAStepCompletesAtOnce();
		checkAMobAttackingDuringAnothersActCompletesAtOnce();
		checkAStepThatThrewLeavesNothingParked();
	}

	private static final class Order {
		boolean inAct, landed, landedInAct;

		void land(){
			landed = true;
			landedInAct |= inAct;
		}
	}

	private interface Probe {
		Order order();
	}

	private static class OrderRat extends Rat implements Probe {
		final Order order = new Order();
		boolean throwing;
		Runnable duringAct = () -> {};

		@Override public Order order(){ return order; }

		@Override
		public boolean act(){
			order.inAct = true;
			try {
				if (throwing) throw new IllegalStateException( "probe" );
				duringAct.run();
				return super.act();
			} finally {
				order.inAct = false;
			}
		}

		@Override
		public void onAttackComplete(){
			order.land();
			super.onAttackComplete();
		}
	}

	private static final class OrderGhoul extends Ghoul implements Probe {
		final Order order = new Order();

		@Override public Order order(){ return order; }

		@Override
		public boolean act(){
			order.inAct = true;
			try {
				return super.act();
			} finally {
				order.inAct = false;
			}
		}

		@Override
		public void onAttackComplete(){
			order.land();
			super.onAttackComplete();
		}
	}

	private static final class OrderShaman extends Shaman.RedShaman implements Probe {
		final Order order = new Order();

		@Override public Order order(){ return order; }

		@Override
		public boolean act(){
			order.inAct = true;
			try {
				return super.act();
			} finally {
				order.inAct = false;
			}
		}

		@Override
		public void onZapComplete(){
			order.land();
			super.onZapComplete();
		}
	}

	private static void checkItLandsAfterTheAct( String what, boolean ranged, Supplier< Mob > probe ){
		SPDEnv env = MobSpriteCheck.freshRun( SEED );
		int cell = ranged ? MobSpriteCheck.cellWithBoltLine() : cellBesideTheHero();
		if (cell < 0){
			fail( what + ": the fixture found no cell for the probe, so the order was never checked." );
			return;
		}
		Mob mob = probe.get();
		place( mob, cell );
		mob.aggro( Dungeon.hero );
		mob.state = mob.HUNTING;
		Dungeon.hero.HP = Dungeon.hero.HT = 1000;
		Order order = ((Probe) mob).order();

		for (int i = 0; i < 3 && env.running() && !order.landed; i++) env.step( Action.WAIT, 0 );

		if (!order.landed){
			fail( what + " never completed in 3 turns, so the order was never checked." );
		} else if (order.landedInAct){
			fail( what + " completed inside act(). The rendered game runs it as an animation callback after"
					+ " act() returns, so whatever act() does after super.act() ran in the wrong order headlessly." );
		} else {
			System.out.println( "  " + what + " lands after its act" );
		}
	}

	private static void checkAnAttackOutsideAStepCompletesAtOnce(){
		OrderRat rat = holdingTheScheduler( new OrderRat() );
		if (rat == null) return;
		rat.sprite.attack( Dungeon.hero.pos );
		if (!rat.order.landed){
			fail( "an attack asked for outside a scheduler step was parked. Nothing drains it there, so the mob"
					+ " would hold the scheduler for good." );
			return;
		}
		System.out.println( "  an attack outside a step completes at once" );
	}

	private static void checkAMobAttackingDuringAnothersActCompletesAtOnce(){
		MobSpriteCheck.freshRun( SEED );
		Actor.remove( Dungeon.hero );
		OrderRat bystander = new OrderRat();
		OrderRat instigator = new OrderRat();
		boolean[] landedDuringTheAct = { false };
		boolean[] acted = { false };
		instigator.duringAct = () -> {
			if (acted[ 0 ]) return;
			acted[ 0 ] = true;
			bystander.sprite.attack( Dungeon.hero.pos );
			landedDuringTheAct[ 0 ] = bystander.order.landed;
		};
		int cell = freeCell();
		place( bystander, cell );
		place( instigator, freeCell() );
		for (int i = 0; i < 50 && !acted[ 0 ]; i++) HeadlessScheduler.step();

		if (!acted[ 0 ]){
			fail( "the instigating probe never acted in 50 scheduler steps, so the case was never reached." );
		} else if (!landedDuringTheAct[ 0 ]){
			fail( "a mob attacking during another actor's act was deferred. Only the acting mob holds the"
					+ " scheduler in the rendered game; any other attack lands on its own animation." );
		} else {
			System.out.println( "  a mob attacking during another's act completes at once" );
		}
	}

	private static void checkAStepThatThrewLeavesNothingParked(){
		OrderRat rat = new OrderRat();
		rat.throwing = true;
		MobSpriteCheck.freshRun( SEED );
		Actor.remove( Dungeon.hero );
		place( rat, freeCell() );
		boolean threw = false;
		for (int i = 0; i < 50 && !threw; i++){
			try {
				HeadlessScheduler.step();
			} catch (IllegalStateException e){
				threw = true;
			}
		}
		if (!threw || Actor.currentActor() != rat){
			fail( "the throwing probe never acted in 50 scheduler steps, so the case was never reached." );
			return;
		}
		rat.sprite.attack( Dungeon.hero.pos );
		if (!rat.order.landed){
			fail( "after an act() that threw, the next attack was parked as if a step were still in progress." );
			return;
		}
		System.out.println( "  a step that threw leaves nothing parked" );
	}

	private static OrderRat holdingTheScheduler( OrderRat rat ){
		MobSpriteCheck.freshRun( SEED );
		Actor.remove( Dungeon.hero );
		place( rat, freeCell() );
		for (int i = 0; i < 50 && Actor.currentActor() != rat; i++) HeadlessScheduler.step();
		if (Actor.currentActor() != rat){
			fail( "the probe never held the scheduler in 50 steps, so the case was never reached." );
			return null;
		}
		return rat;
	}

	private static void place( Mob mob, int cell ){
		mob.pos = cell;
		GameScene.add( mob );
	}

	private static int cellBesideTheHero(){
		for (int direction : PathFinder.NEIGHBOURS8){
			int cell = Dungeon.hero.pos + direction;
			if (Dungeon.level.passable[ cell ] && Actor.findChar( cell ) == null) return cell;
		}
		return -1;
	}

	private static int freeCell(){
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (Dungeon.level.passable[ cell ] && Actor.findChar( cell ) == null
					&& !Dungeon.level.adjacent( cell, Dungeon.hero.pos )) return cell;
		}
		throw new IllegalStateException( "the level has no free cell away from the hero" );
	}
}
