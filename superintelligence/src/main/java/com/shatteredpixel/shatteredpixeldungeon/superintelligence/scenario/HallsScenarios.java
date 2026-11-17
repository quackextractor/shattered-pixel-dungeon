package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.AscensionChallenge;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Ooze;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Acidic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DemonSpawner;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.RipperDemon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.YogDzewa;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.YogFist;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Imp;
import com.shatteredpixel.shatteredpixeldungeon.items.Amulet;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation;
import com.shatteredpixel.shatteredpixeldungeon.levels.HallsBossLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.HallsLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.LastLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.LastShopLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.Room;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.standard.ImpShopRoom;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.LevelPipeline;
import com.watabou.utils.Bundle;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class HallsScenarios {

	private HallsScenarios(){}

	private static final int HALLS_DEPTH = 22;
	private static final int BOSS_DEPTH = 25;
	private static final int AMULET_DEPTH = 26;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( acidic() );
		all.add( demonSpawner() );
		all.add( fist( YogFist.RottingFist.class, "RottingFist" ));
		all.add( fist( YogFist.RustedFist.class, "RustedFist" ));
		all.add( fist( YogFist.BrightFist.class, "BrightFist" ));
		all.add( fist( YogFist.DarkFist.class, "DarkFist" ));
		all.add( yogMinions() );
		all.add( descend() );
		all.add( amulet().covers( Amulet.class, LastLevel.class ));
		all.add( ascent().covers( AscensionChallenge.class ));
		return all;
	}

	private static Scenario acidic(){
		return new Scenario( "halls.Acidic", "the acidic scorpio corrodes the hero who strikes it", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Acidic acidic = world.hunting( new Acidic(), world.adjacentFree() );
					acidic.HP = acidic.HT = Math.max( acidic.HT, FoeFamily.MIN_HP );
					Buff.affect( acidic, Paralysis.class, 100f );
				},
				d -> {
					Acidic acidic = d.world().find( Acidic.class );
					d.require( acidic.buff( Paralysis.class ) != null, "the acidic scorpio was free to strike back" );
					d.require( Dungeon.hero.buff( Ooze.class ) == null, "the hero was oozing before the fight" );
					int hp = acidic.HP;
					d.until( () -> acidic.HP < hp, 8, () -> d.move( acidic.pos ));
					d.expect( acidic.HP < hp, "8 attacks left the acidic scorpio at " + acidic.HP + " of " + hp );
					d.expect( Dungeon.hero.buff( Ooze.class ) != null, "the acidic scorpio did not corrode the hero who hit it" );
				} ).depth( 23 ).covers( Acidic.class );
	}

	private static Scenario demonSpawner(){
		return new Scenario( "halls.DemonSpawner", "a demon spawner raises ripper demons that the hero then kills", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.hero().belongings.weapon.level( 30 );
					world.hunting( new DemonSpawner(), world.adjacentFree() );
				},
				d -> {
					DemonSpawner spawner = d.world().find( DemonSpawner.class );
					int hp = spawner.HP;
					d.require( MobScenarios.count( RipperDemon.class ) == 0, "a ripper demon stood before the spawner acted" );
					d.waitFor( () -> MobScenarios.count( RipperDemon.class ) > 0, 6 );
					d.expect( MobScenarios.count( RipperDemon.class ) > 0, "the demon spawner raised no ripper demon in 6 turns" );
					FoeFamily.brawl( d, DemonSpawner.class, 12, null, "the demon spawner never hurt the hero" );
					d.expect( spawner.HP < hp, "12 steps beside the spawner left it at " + spawner.HP + " of " + hp );
					RipperDemon demon = d.world().find( RipperDemon.class );
					int demonHp = demon.HP;
					FoeFamily.brawl( d, RipperDemon.class, 20, null, "the raised ripper demon never hurt the hero" );
					d.expect( !demon.isAlive() || demon.HP < demonHp, "20 steps left the raised ripper demon at " + demon.HP );
				} ).depth( 23 ).covers( DemonSpawner.class );
	}

	private static Scenario fist( Class< ? extends YogFist > type, String name ){
		return FoeFamily.fight( "halls.fist." + name, type, 1, 20, null,
				"the " + name + " never hurt the hero" ).depth( BOSS_DEPTH );
	}

	private static Scenario yogMinions(){
		return new Scenario( "halls.yog-minions", "Yog's phase raises a larva, and his eyes, scorpios and rippers fight", HeroClass.WARRIOR,
				( world, kit ) -> {
					bossFloor( world );
					kit.health( 4000, 4000 );
					kit.hero().belongings.weapon.level( 40 );
					((HallsBossLevel) world.level()).seal();
					YogDzewa yog = world.find( YogDzewa.class );
					yog.notice();
					ScrollOfTeleportation.appear( Dungeon.hero, beside( world, yog.pos ));
					int[] cells = cells( world, 3 );
					world.hunting( new YogDzewa.YogEye(), cells[ 0 ] );
					world.hunting( new YogDzewa.YogScorpio(), cells[ 1 ] );
					world.hunting( new YogDzewa.YogRipper(), cells[ 2 ] );
				},
				d -> {
					d.require( MobScenarios.count( YogDzewa.Larva.class ) == 0, "Yog had raised a larva before his phase turned" );
					YogDzewa yog = d.world().find( YogDzewa.class );
					int hp = yog.HP;
					d.require( Dungeon.level.adjacent( Dungeon.hero.pos, yog.pos ), "the hero is not beside Yog" );
					d.until( () -> yog.HP < hp, 4, () -> d.move( yog.pos ));
					d.expect( yog.HP < hp, "4 attacks beside Yog left him at " + yog.HP + " of " + hp );
					d.waitFor( () -> MobScenarios.count( YogDzewa.Larva.class ) > 0, 20 );
					d.expect( MobScenarios.count( YogDzewa.Larva.class ) > 0, "20 turns of Yog's first phase raised no larva" );
					fight( d, YogDzewa.Larva.class );
					fight( d, YogDzewa.YogEye.class );
					fight( d, YogDzewa.YogRipper.class );
					fight( d, YogDzewa.YogScorpio.class );
				} ).depth( BOSS_DEPTH ).covers( YogDzewa.class, YogDzewa.Larva.class, YogDzewa.YogEye.class,
				YogDzewa.YogRipper.class, YogDzewa.YogScorpio.class, HallsBossLevel.class ).seeds( "SCN-A", "SCN-B", "SCN-C", "SCN-D" );
	}

	private static int beside( World world, int cell ){
		for (int offset : PathFinder.NEIGHBOURS8){
			if (world.free( cell + offset )) return cell + offset;
		}
		throw new NoFixture( "no free cell beside " + cell );
	}

	private static void fight( Driver d, Class< ? extends Mob > type ){
		Mob mob = d.world().find( type );
		int hp = mob.HP;
		d.until( () -> !mob.isAlive() || Dungeon.level.adjacent( Dungeon.hero.pos, mob.pos ), 30,
				() -> d.walkTo( mob.pos, 1 ));
		if (!mob.isAlive()) return;
		d.require( Dungeon.level.adjacent( Dungeon.hero.pos, mob.pos ), "30 waits did not bring the "
				+ type.getSimpleName() + " to the hero" );
		FoeFamily.brawl( d, type, 20, null, "the " + type.getSimpleName() + " never hurt the hero" );
		d.expect( !mob.isAlive() || mob.HP < hp, "20 steps left the " + type.getSimpleName() + " at " + mob.HP + " of " + hp );
	}

	private static int[] cells( World world, int count ){
		int[] out = new int[ count ];
		int n = 0;
		for (int range = 2; range <= 4 && n < count; range++){
			for (int cell = 0; cell < world.level().length() && n < count; cell++){
				if (!world.free( cell )) continue;
				if (world.level().distance( world.hero(), cell ) != range) continue;
				boolean used = false;
				for (int i = 0; i < n; i++) used |= out[ i ] == cell;
				if (!used) out[ n++ ] = cell;
			}
		}
		if (n < count) throw new NoFixture( "no " + count + " free cells near the hero" );
		return out;
	}

	private static Scenario descend(){
		return new Scenario( "halls.descend", "take the stairs down from a halls floor", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Arrays.fill( world.level().mapped, true );
				},
				d -> {
					d.require( Dungeon.level instanceof HallsLevel, "depth " + HALLS_DEPTH + " is a "
							+ Dungeon.level.getClass().getSimpleName() );
					d.fixture( d.env().actionMask()[ Action.TRAVEL_STAIRS.index ] > 0.5f, "no known path to the exit" );
					d.until( () -> Dungeon.depth == HALLS_DEPTH + 1, 100, () -> d.act( Action.TRAVEL_STAIRS ));
					d.expect( Dungeon.depth == HALLS_DEPTH + 1, "100 TRAVEL_STAIRS steps left the hero on depth " + Dungeon.depth );
				} ).depth( HALLS_DEPTH ).covers( HallsLevel.class );
	}


	private static Scenario amulet(){
		return new Scenario( "halls.amulet", "step onto the amulet of yendor on the last floor and keep it", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					if (!(world.level() instanceof LastLevel)){
						throw new NoFixture( "depth " + AMULET_DEPTH + " is a " + world.level().getClass().getSimpleName() );
					}
					Statistics.amuletObtained = true;
					ScrollOfTeleportation.appear( Dungeon.hero, LastLevel.AMULET_POS + world.level().width() );
				},
				d -> {
					d.require( Dungeon.level instanceof LastLevel, "depth " + AMULET_DEPTH + " is a "
							+ Dungeon.level.getClass().getSimpleName() );
					d.require( Dungeon.hero.belongings.getItem( Amulet.class ) == null, "the hero already carried the amulet" );
					d.move( LastLevel.AMULET_POS );
					d.expect( Dungeon.hero.belongings.getItem( Amulet.class ) != null, "stepping onto the amulet did not pick it up" );
				} ).depth( AMULET_DEPTH );
	}

	private static Scenario ascent(){
		return new Scenario( "halls.ascent", "carrying the amulet up from Yog's floor starts the ascension", HeroClass.WARRIOR,
				( world, kit ) -> {
					bossFloor( world );
					kit.sturdy();
					kit.give( new Amulet() );
				},
				d -> {
					d.require( Dungeon.level instanceof HallsBossLevel, "depth " + BOSS_DEPTH + " is a "
							+ Dungeon.level.getClass().getSimpleName() );
					d.require( Dungeon.hero.pos == Dungeon.level.entrance(), "the hero did not start on the ascent stairs" );
					d.require( Dungeon.hero.buff( AscensionChallenge.class ) == null, "the ascension was already running" );
					float before = AscensionChallenge.statModifier( new Rat() );
					d.require( before == 1f, "an enemy was already boosted without the ascension: " + before );
					int beside = d.world().adjacentFree();
					d.move( beside );
					d.move( Dungeon.level.entrance() );
					d.require( d.env().mode() == EnvMode.MENU, "the ascent stairs asked nothing: " + d.env().mode() );
					d.require( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge.optionCount() == 2,
							"the ascension prompt offers "
									+ com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge.optionCount()
									+ " options, not ascent and stay" );
					d.menu( 0 );
					d.expect( Dungeon.hero.buff( AscensionChallenge.class ) != null, "accepting the ascent left the hero unchallenged" );
					d.expect( AscensionChallenge.statModifier( new Rat() ) > before,
							"the ascension left a rat at modifier " + AscensionChallenge.statModifier( new Rat() ));
					d.expect( Dungeon.depth == BOSS_DEPTH - 1, "the ascent left the hero on depth " + Dungeon.depth );
				} ).depth( BOSS_DEPTH );
	}

	private static void bossFloor( World world ){
		if (!(world.level() instanceof HallsBossLevel)){
			throw new NoFixture( "depth " + Dungeon.depth + " is a " + world.level().getClass().getSimpleName() );
		}
		world.clearMobs();
	}
}
