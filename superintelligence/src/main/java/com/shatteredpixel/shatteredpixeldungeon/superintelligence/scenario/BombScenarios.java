package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Regrowth;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.SmokeScreen;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barkskin;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Roots;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Bee;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Skeleton;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Sheep;
import com.shatteredpixel.shatteredpixeldungeon.items.Honeypot;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.ArcaneBomb;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.Bomb;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.Firebomb;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.FlashBangBomb;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.FrostBomb;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.HolyBomb;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.Noisemaker;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.RegrowthBomb;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.ShrapnelBomb;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.SmokeBomb;
import com.shatteredpixel.shatteredpixeldungeon.items.bombs.WoollyBomb;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.GooBlob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.AIM;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.THROW;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.effect;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.frozen;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.held;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.rat;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.target;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.volume;

final class BombScenarios {

	private BombScenarios(){}

	private static final int FUSE = 3;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( hurts( "bomb.Bomb", Bomb.class ));
		all.add( hurts( "bomb.ArcaneBomb", ArcaneBomb.class ));
		all.add( hurts( "bomb.ShrapnelBomb", ShrapnelBomb.class ));
		all.add( lit( "bomb.Firebomb", Firebomb.class, held( 100 ), "the firebomb never set the rat burning", () -> {
			Mob rat = rat();
			return () -> volume( Fire.class ) > 0 && rat.buff( Burning.class ) != null;
		}).covers());
		all.add( lit( "bomb.FlashBangBomb", FlashBangBomb.class, BombScenarios::rooted, "the flash-bang never stunned the rat", () -> {
			Mob rat = rat();
			return () -> rat.buff( Paralysis.class ) != null && rat.paralysed > 0;
		}).covers());
		all.add( lit( "bomb.FrostBomb", FrostBomb.class, BombScenarios::rooted, "the frost bomb never froze the rat", () -> {
			Mob rat = rat();
			return () -> frozen( rat );
		}).covers());
		all.add( lit( "bomb.HolyBomb", HolyBomb.class, BombScenarios::armouredUndead, "the holy bomb never hurt the armoured skeleton", () -> {
			Skeleton skeleton = new World().find( Skeleton.class );
			int hp = skeleton.HP;
			return () -> skeleton.HP < hp;
		}).covers());
		all.add( effect( "bomb.Noisemaker", Noisemaker.class, ( d, type ) -> {
			int cell = d.world().adjacentFree();
			d.use( type );
			d.step( Action.USE, d.direction( cell ));
		}, ScrollScenarios::sleeperOutOfSight, FUSE, "the noisemaker never woke the far rat", () -> {
			Mob rat = rat();
			return () -> rat.state != rat.SLEEPING;
		}).covers());
		all.add( lit( "bomb.RegrowthBomb", RegrowthBomb.class, held( 100 ), "the regrowth grew no grass or never rooted the rat", () -> {
			Mob rat = rat();
			int grass = grass();
			return () -> volume( Regrowth.class ) > 0 && grass() > grass && rat.buff( Roots.class ) != null;
		}).covers( Regrowth.class ));
		all.add( lit( "bomb.SmokeBomb", SmokeBomb.class, BombScenarios::watcher, "the smoke never hid the hero from the rat", () -> {
			Mob rat = rat();
			int hero = Dungeon.hero.pos;
			return () -> volume( SmokeScreen.class ) > 0 && !rat.fieldOfView[ hero ];
		}).before( d -> d.act( Action.WAIT )).covers());
		all.add( lit( "bomb.WoollyBomb", WoollyBomb.class, held( 100 ), "the woolly bomb raised no sheep", () -> {
			int sheep = count( Sheep.class );
			return () -> count( Sheep.class ) > sheep;
		}).covers());
		all.add( CraftScenarios.brew( "bomb.enhance", ArcaneBomb.class, Bomb::new, GooBlob::new ).covers( Bomb.EnhanceBomb.class ));
		all.add( effect( "bomb.Honeypot", Honeypot.class, THROW, held( 100 ), 10, "no bee rose from the pot to sting the rat", () -> {
			Mob rat = rat();
			int hp = rat.HP;
			return () -> count( Bee.class ) > 0 && rat.HP < hp;
		}).then( BombScenarios::swatBee ).covers( Bee.class ));
		return all;
	}

	private static PotionScenarios.Effect lit( String id, Class< ? extends Bomb > type, Scenario.Setup arena, String failure,
			Supplier< BooleanSupplier > arm ){
		return effect( id, type, AIM, arena, FUSE, failure, arm );
	}

	private static Scenario hurts( String id, Class< ? extends Bomb > type ){
		return lit( id, type, held( 100 ), "the " + type.getSimpleName() + " never hurt the rat", () -> {
			Mob rat = rat();
			int hp = rat.HP;
			return () -> rat.HP < hp;
		}).covers();
	}

	private static void rooted( World world, HeroKit kit ){
		Buff.affect( target( world, 100 ), Roots.class, 100f );
	}

	private static void watcher( World world, HeroKit kit ){
		PotionScenarios.watcher( world, kit );
		Buff.affect( rat(), Roots.class, 100f );
	}

	private static void swatBee( Driver d ){
		Bee bee = d.world().find( Bee.class );
		int hp = bee.HP;
		d.expect( d.until( () -> !bee.isAlive() || bee.HP < hp, 20, () -> {
			if (Dungeon.level.adjacent( Dungeon.hero.pos, bee.pos )) d.move( bee.pos );
			else d.walkTo( bee.pos, 1 );
		}), "the hero never hurt the bee from " + hp + " HP in 20 steps" );
	}

	private static void armouredUndead( World world, HeroKit kit ){
		Skeleton skeleton = world.mob( new Skeleton(), world.cellAtRange( 3, true ));
		skeleton.HP = skeleton.HT = 100;
		Buff.affect( skeleton, Paralysis.class, 100f );
		Barkskin.conditionallyAppend( skeleton, 1000, 100 );
	}

	private static int grass(){
		int cells = 0;
		for (int terrain : Dungeon.level.map){
			if (terrain == Terrain.GRASS || terrain == Terrain.HIGH_GRASS || terrain == Terrain.FURROWED_GRASS) cells++;
		}
		return cells;
	}

	private static int count( Class< ? extends Mob > type ){
		int found = 0;
		for (Mob mob : Dungeon.level.mobs) if (type.isInstance( mob )) found++;
		return found;
	}
}
