package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.HeroDisguise;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Levitation;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.HighGrass;
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

	private static Level freshFloor(){
		SPDEnv env = new SPDEnv( new EnvConfig(), HeadlessGame.install() );
		env.reset( "SCENEGATECHECK", HeroClass.WARRIOR );
		return Dungeon.level;
	}
}
