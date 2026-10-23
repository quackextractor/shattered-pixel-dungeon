package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.HeroDisguise;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Levitation;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.HighGrass;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.DisintegrationTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.PoisonDartTrap;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;

import com.watabou.utils.RandomTrace;

import java.util.Arrays;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ObserveCheck.fail;

public final class SceneGateCheck {

	private SceneGateCheck(){}

	static void checkTramplingInViewRefreshesTheFieldOfView(){
		Level level = freshFloor();
		int cell = Dungeon.hero.pos;
		Level.set( cell, Terrain.HIGH_GRASS );
		Arrays.fill( level.heroFOV, false );
		level.heroFOV[ cell ] = true;

		HighGrass.trample( level, cell );

		int seen = 0;
		for (boolean b : level.heroFOV) if (b) seen++;
		if (seen <= 1){
			fail( "trampling high grass in the hero's view left the field of view as it was. The rendered game"
					+ " calls Dungeon.observe() there, so its next search rolls for every secret cell the new"
					+ " view reveals and the trainer's does not - ZRD-CVK-QYT parted from the viewer at step 627"
					+ " that way, one draw apart." );
		}
	}

	static void checkLandingFromLevitationPressesTheCell(){
		Level level = freshFloor();
		Hero hero = Dungeon.hero;
		Level.set( hero.pos, Terrain.HIGH_GRASS );
		Buff.affect( hero, Levitation.class, 1f ).detach();

		if (level.map[ hero.pos ] == Terrain.HIGH_GRASS){
			fail( "levitation ended over high grass and the grass stood. The rendered game presses the cell"
					+ " the hero lands on, so a trap, a plate or grass under it fires there and not in the"
					+ " trainer." );
		}
	}

	static void checkADisguiseRollsItsClassHeadlessly(){
		freshFloor();
		Hero hero = Dungeon.hero;

		RandomTrace.enable();
		RandomTrace.reset();
		long before = RandomTrace.draws();
		HeroDisguise disguise = Buff.affect( hero, HeroDisguise.class, HeroDisguise.DURATION );
		long spent = RandomTrace.draws() - before;
		RandomTrace.reset();
		RandomTrace.disable();

		if (disguise.getDisguise() == null || spent == 0){
			fail( "a cursed wand's disguise attached headlessly without rolling its class (" + spent
					+ " draws). The rendered game rolls it from the gameplay RNG in fx, so the trainer"
					+ " spent one value fewer and Hero.name() threw on the null class." );
			return;
		}
		hero.name();
		disguise.detach();
	}

	static void checkAHeapDroppedInPlayIsExplored(){
		Level level = freshFloor();
		boolean levelgenExplored = true;
		for (Heap h : level.heaps.valueList()) levelgenExplored &= h.autoExplored;
		Heap dropped = level.drop( new Gold( 1 ), Dungeon.hero.pos );

		if (!dropped.autoExplored){
			fail( "a heap dropped in play was not marked autoExplored headlessly. The rendered game marks"
					+ " it in GameScene.add, so levelExplorePercent - the observation's F_FLOOR_EXPLORED -"
					+ " counted a room the viewer counts as explored." );
		}
		if (!level.heaps.isEmpty() && levelgenExplored){
			fail( "every heap level generation placed was marked autoExplored; only heaps added in play are" );
		}
	}

	static void checkADartTrapInViewHitsWithoutAScene(){
		SPDEnv env = freshEnv();
		Hero hero = Dungeon.hero;
		new PoisonDartTrap().set( hero.pos ).activate();
		env.step( Action.WAIT, 0 );

		if (hero.buff( Poison.class ) == null){
			fail( "a poison dart trap fired at the hero in view and the hero was not poisoned. Its in-view branch"
					+ " recycled a MissileSprite from ShatteredPixelDungeon.scene(), which is null headlessly, so"
					+ " the trainer threw where the viewer hits; WornDartTrap already calls the hit directly." );
		}
	}

	static void checkADisintegrationTrapInViewHitsWithoutAScene(){
		SPDEnv env = freshEnv();
		Hero hero = Dungeon.hero;
		int before = hero.HP;
		new DisintegrationTrap().set( hero.pos ).activate();

		if (hero.isAlive() && hero.HP >= before){
			fail( "a disintegration trap fired at the hero in view and dealt nothing. It added its death ray to"
					+ " ShatteredPixelDungeon.scene() before the damage roll, which threw headlessly." );
		}
	}

	static void checkAnUnseenMobSpriteIsHidden(){
		freshFloor();
		Mob unseen = null;
		for (Mob m : Dungeon.level.mobs){
			if (m.sprite != null && !m.sprite.visibleOutOfFFOV && !Dungeon.level.heroFOV[ m.pos ]) unseen = m;
		}
		if (unseen == null){
			fail( "no mob out of the hero's view on the SCENEGATECHECK floor to test sprite visibility with" );
			return;
		}
		unseen.sprite.visible = true;
		Dungeon.observe();

		if (unseen.sprite.visible){
			fail( "a mob out of the hero's view kept a visible sprite headlessly. GameScene.afterObserve sets"
					+ " sprite.visible from the field of view only with a scene, and gameplay branches on it:"
					+ " Great Crab's parry pitch, Flow and Swiftness particles and Mob.move's reveal drew or"
					+ " wrote only on one side." );
		}
	}

	private static Level freshFloor(){
		freshEnv();
		return Dungeon.level;
	}

	private static SPDEnv freshEnv(){
		SPDEnv env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );
		env.reset( "SCENEGATECHECK", HeroClass.WARRIOR );
		return env;
	}
}
