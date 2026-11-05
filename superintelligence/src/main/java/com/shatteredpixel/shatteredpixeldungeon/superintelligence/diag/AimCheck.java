package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.SpiritBow;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingStone;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.DescentBot;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Policy;

import java.util.function.Predicate;

public class AimCheck {

	private static final int CHECKS = 8;
	private static final int NEAREST = 9;
	private static final String[] SEEDS = { "AIM-A", "AIM-B", "AIM-C", "AIM-D", "AIM-E", "AIM-F", "AIM-G", "AIM-H" };

	private static SPDEnv env;

	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "aimcheck" ) );
		HeadlessServices.disableSaving( true );
		env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );

		checkAnAimCanBeResolvedThroughTheMask();
		checkAnEnemyAtRangeCanBeNamed();
		checkNoEnemyMeansNoEnemyTarget();
		checkAStoneFliesAtRange();
		checkABowShotHits();
		checkAStaffZapHits();
		checkAWandZapSpendsACharge();
		checkTheTeacherShoots();

		System.exit( GateReport.report( "aimed items", CHECKS ) );
	}

	private static Mob placeRatAtRange( HeroClass hero ){
		for (String seed : SEEDS){
			env.reset( seed, hero );
			int cell = cellAtRange();
			if (cell < 0) continue;
			Rat rat = new Rat();
			rat.pos = cell;
			rat.state = rat.PASSIVE;
			GameScene.add( rat, 0f );
			Dungeon.observe();
			return rat;
		}
		GateReport.fail( "no seed of " + SEEDS.length + " has a clear cell in view 3 or more away, so no shot could be tested" );
		return null;
	}

	private static int cellAtRange(){
		int hero = Dungeon.hero.pos;
		for (int c = 0; c < Dungeon.level.length(); c++){
			if (!Dungeon.level.heroFOV[ c ] || !Dungeon.level.passable[ c ] || Dungeon.level.distance( hero, c ) < 3) continue;
			if (com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar( c ) != null) continue;
			if (new Ballistica( hero, c, Ballistica.PROJECTILE ).collisionPos == c) return c;
		}
		return -1;
	}

	private static int slotOf( Predicate< Item > kind ){
		env.mapper().refreshSlots();
		for (int i = 0; i < env.mapper().slots().size(); i++){
			if (kind.test( env.mapper().slots().get( i ) )) return i;
		}
		return -1;
	}

	private static boolean aim( int slot ){
		env.step( Action.USE, 0 );
		env.step( Action.USE, slot );
		return env.mode() == EnvMode.TARGETING;
	}

	private static void checkAnAimCanBeResolvedThroughTheMask(){
		if (placeRatAtRange( HeroClass.WARRIOR ) == null) return;
		if (!aim( slotOf( i -> i instanceof ThrowingStone ) )){
			GateReport.fail( "a stone did not ask for a target" );
			return;
		}
		if (env.actionMask()[ Action.USE.index ] < 0.5f){
			GateReport.fail( "aiming offers only CANCEL, so a policy can never fire anything" );
		}
		if (ActionMapper.aimAction( 0 ) != Action.CANCEL || ActionMapper.aimAction( NEAREST ) != Action.USE){
			GateReport.fail( "target 0 must hold fire and any other target must fire" );
		}
	}

	private static void checkAnEnemyAtRangeCanBeNamed(){
		Mob rat = placeRatAtRange( HeroClass.WARRIOR );
		if (rat == null) return;
		if (ActionMapper.TARGET_COUNT <= NEAREST){
			GateReport.fail( "the target head reaches only the 8 cells beside the hero" );
			return;
		}
		aim( slotOf( i -> i instanceof ThrowingStone ) );
		if (env.targetMask()[ NEAREST ] < 0.5f) GateReport.fail( "the nearest enemy is not offered as a target" );
		if (env.mapper().targetCell( NEAREST ) != rat.pos) GateReport.fail( "target " + NEAREST + " is not the rat" );
		if (env.targetMask()[ NEAREST + 1 ] > 0.5f) GateReport.fail( "a second enemy target is offered with one enemy in view" );
		for (int i = 1; i < NEAREST; i++){
			if (env.targetMask()[ i ] > 0.5f) GateReport.fail( "adjacent target " + i + " is offered, and it throws the held item away" );
		}
	}

	private static void checkNoEnemyMeansNoEnemyTarget(){
		if (ActionMapper.TARGET_COUNT <= NEAREST){
			GateReport.fail( "no enemy target exists" );
			return;
		}
		env.reset( SEEDS[ 0 ], HeroClass.WARRIOR );
		for (int i = NEAREST; i < ActionMapper.TARGET_COUNT; i++){
			if (env.targetMask()[ i ] > 0.5f) GateReport.fail( "target " + i + " is offered with no enemy in view" );
		}
	}

	private static void checkAStoneFliesAtRange(){
		if (ActionMapper.TARGET_COUNT <= NEAREST){
			GateReport.fail( "a stone cannot be aimed past the 8 cells beside the hero" );
			return;
		}
		Mob rat = placeRatAtRange( HeroClass.WARRIOR );
		if (rat == null) return;
		Item stone = env.mapper().slots().get( slotOf( i -> i instanceof ThrowingStone ) );
		int before = stone.quantity();
		float clock = com.shatteredpixel.shatteredpixeldungeon.actors.Actor.now();
		aim( slotOf( i -> i instanceof ThrowingStone ) );
		env.step( Action.USE, NEAREST );
		if (stone.quantity() >= before && Dungeon.hero.belongings.getItem( ThrowingStone.class ) == stone) GateReport.fail( "the stone was not thrown" );
		if (com.shatteredpixel.shatteredpixeldungeon.actors.Actor.now() <= clock) GateReport.fail( "the throw took no time" );
	}

	private static boolean hits( Mob target, int shots, Predicate< Item > weapon ){
		for (int i = 0; i < shots && env.running() && target.HP == target.HT; i++){
			Dungeon.hero.HP = Dungeon.hero.HT;
			aim( slotOf( weapon ) );
			if (env.mode() != EnvMode.TARGETING) return false;
			env.step( Action.USE, NEAREST );
			if (env.mode() != EnvMode.WORLD) return false;
		}
		return target.HP < target.HT;
	}

	private static void checkABowShotHits(){
		if (ActionMapper.TARGET_COUNT <= NEAREST){
			GateReport.fail( "a bow cannot be aimed past the 8 cells beside the hero" );
			return;
		}
		Mob rat = placeRatAtRange( HeroClass.HUNTRESS );
		if (rat != null && !hits( rat, 8, i -> i instanceof SpiritBow )) GateReport.fail( "8 bow shots at a rat did not hurt it" );
	}

	private static void checkAStaffZapHits(){
		if (ActionMapper.TARGET_COUNT <= NEAREST){
			GateReport.fail( "a staff cannot be aimed past the 8 cells beside the hero" );
			return;
		}
		Mob rat = placeRatAtRange( HeroClass.MAGE );
		if (rat != null && !hits( rat, 2, i -> i instanceof MagesStaff )) GateReport.fail( "2 staff zaps at a rat did not hurt it" );
	}

	private static void checkTheTeacherShoots(){
		Mob rat = placeRatAtRange( HeroClass.HUNTRESS );
		if (rat == null) return;
		DescentBot bot = new DescentBot();
		int[] secondary = new int[ 1 ];
		boolean labelled = false;
		for (int i = 0; i < 30 && env.running() && rat.HP == rat.HT; i++){
			Dungeon.hero.HP = Dungeon.hero.HT;
			if (env.mode() == EnvMode.TARGETING) labelled |= bot.label( env, Policy.HEAD_TARGET ) == NEAREST;
			env.step( bot.act( env, secondary ), secondary[ 0 ] );
		}
		if (rat.HP == rat.HT) GateReport.fail( "DescentBot had a bow and a rat in the open and never hurt it" );
		if (!labelled) GateReport.fail( "DescentBot teaches nothing on the target head" );
	}

	private static void checkAWandZapSpendsACharge(){
		if (ActionMapper.TARGET_COUNT <= NEAREST){
			GateReport.fail( "a wand cannot be aimed past the 8 cells beside the hero" );
			return;
		}
		Mob rat = placeRatAtRange( HeroClass.WARRIOR );
		if (rat == null) return;
		Wand wand = new WandOfMagicMissile();
		wand.identify();
		wand.collect();
		int charges = wand.curCharges;
		aim( slotOf( i -> i == wand ) );
		env.step( Action.USE, NEAREST );
		if (wand.curCharges >= charges) GateReport.fail( "the wand zap spent no charge, so it never went off" );
	}
}
