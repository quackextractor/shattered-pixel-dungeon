package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ConfusionGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.CorrosiveGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Chill;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Corrosion;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Frost;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.FrostImbue;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Ooze;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Weakness;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Sheep;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.KindOfWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Greatsword;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.BlazingTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.ChillingTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.ConfusionTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.CorrosionTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.CursingTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.DisarmingTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.DistortionTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.ExplosiveTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.FlashingTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.FlockTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.FrostTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.GatewayTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.GeyserTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.GnollRockfallTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.GrimTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.GuardianTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.OozeTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.ShockingTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.StormTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.TenguDartTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.WarpingTrap;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.WeakeningTrap;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.watabou.utils.BArray;
import com.watabou.utils.PathFinder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class TrapEffects {

	private TrapEffects(){}

	interface Body {
		void run( Driver d, Trap trap, Runnable trigger );
	}

	interface Arm {
		BooleanSupplier arm( Trap trap );
	}

	static final class Effect {
		final Body body;
		final Class< ? >[] covers;
		Scenario.Setup arena = ( world, kit ) -> {};
		int depth = 0;

		Effect( Body body, Class< ? >... covers ){
			this.body = body;
			this.covers = covers;
		}

		Effect arena( Scenario.Setup arena ){
			this.arena = arena;
			return this;
		}

		Effect depth( int depth ){
			this.depth = depth;
			return this;
		}
	}

	static Map< Class< ? extends Trap >, Effect > all(){
		Map< Class< ? extends Trap >, Effect > all = new LinkedHashMap<>();
		all.put( BlazingTrap.class, armed( 3, "no fire burned around the blazing trap", trap -> () -> volume( Fire.class ) > 0 ));
		all.put( ChillingTrap.class, new Effect( TrapEffects::chillSlows, Chill.class ));
		all.put( ConfusionTrap.class, armed( 3, "the confusion trap released no gas", trap -> () -> volume( ConfusionGas.class ) > 0 ));
		all.put( CorrosionTrap.class, new Effect( watch( 4, "the corrosive gas never corroded the hero's health", trap -> {
			int hp = Dungeon.hero.HP;
			return () -> volume( CorrosiveGas.class ) > 0 && Dungeon.hero.buff( Corrosion.class ) != null && Dungeon.hero.HP < hp;
		}), Corrosion.class, CorrosiveGas.class ));
		all.put( CursingTrap.class, armed( 1, "the cursing trap left the worn weapon uncursed", trap -> {
			KindOfWeapon weapon = Dungeon.hero.belongings.weapon;
			return () -> weapon != null && weapon.cursed;
		}).arena( ( world, kit ) -> kit.hero().belongings.armor = null ));
		all.put( DisarmingTrap.class, armed( 1, "the disarming trap left the weapon in the hero's hand", trap -> {
			KindOfWeapon weapon = Dungeon.hero.belongings.weapon;
			return () -> weapon != null && Dungeon.hero.belongings.weapon == null && onFloor( weapon );
		}));
		all.put( DistortionTrap.class, armed( 1, "the distortion trap brought no mob onto the level", trap -> {
			int mobs = Dungeon.level.mobs.size();
			return () -> Dungeon.level.mobs.size() > mobs;
		}));
		all.put( ExplosiveTrap.class, armed( 1, "the explosion never hurt the hero", trap -> hurt() ));
		all.put( FlashingTrap.class, armed( 1, "the flash neither blinded nor crippled the hero",
				trap -> () -> Dungeon.hero.buff( Blindness.class ) != null && Dungeon.hero.buff( Cripple.class ) != null ));
		all.put( FlockTrap.class, armed( 1, "the flock trap summoned no sheep",
				trap -> () -> Dungeon.level.mobs.stream().anyMatch( mob -> mob instanceof Sheep )));
		all.put( FrostTrap.class, armed( 12, "the frost never froze the rat beside the trap", trap -> {
			Mob rat = new World().find( Rat.class );
			return () -> rat.buff( Frost.class ) != null && rat.paralysed > 0;
		}, Frost.class ).arena( ( world, kit ) -> {
			kit.buff( FrostImbue.class, 100f );
			world.mob( new Rat(), besideTrap( world ));
		}));
		all.put( GatewayTrap.class, armed( 1, "the gateway left the hero beside its cell or wore out",
				trap -> () -> away( trap ) && trap.active ));
		all.put( GeyserTrap.class, armed( 1, "the geyser neither spread water nor knocked the rat back", trap -> {
			Mob rat = new World().find( Rat.class );
			int cell = rat.pos;
			int water = water();
			return () -> water() > water && rat.pos != cell;
		}).arena( ( world, kit ) -> Buff.affect( world.mob( new Rat(), besideTrap( world )), Paralysis.class, 50f )));
		all.put( GnollRockfallTrap.class, armed( 1, "the falling rocks never hurt the hero", trap -> hurt() ));
		all.put( GrimTrap.class, armed( 2, "the grim trap never struck the hero", trap -> hurt() ));
		all.put( GuardianTrap.class, new Effect( TrapEffects::guardianHunts, GuardianTrap.Guardian.class ).depth( 12 ));
		all.put( OozeTrap.class, armed( 3, "the ooze never ate at the hero's health", trap -> {
			int hp = Dungeon.hero.HP;
			return () -> Dungeon.hero.buff( Ooze.class ) != null && Dungeon.hero.HP < hp;
		}));
		all.put( ShockingTrap.class, armed( 1, "the electricity neither held nor hurt the hero", trap -> {
			float start = Actor.now();
			int hp = Dungeon.hero.HP;
			return () -> Actor.now() - start > 3f && Dungeon.hero.HP < hp;
		}));
		all.put( StormTrap.class, armed( 1, "the storm never shocked the rat two cells from the trap", trap -> {
			Mob rat = new World().find( Rat.class );
			int hp = rat.HP;
			return () -> rat.HP < hp;
		}).arena( ( world, kit ) -> world.mob( new Rat(), twoFromTrap( world )).HP = 100 ));
		all.put( TenguDartTrap.class, armed( 2, "the dart never poisoned the hero", trap -> () -> Dungeon.hero.buff( Poison.class ) != null ));
		all.put( WarpingTrap.class, armed( 1, "the warp neither moved the hero nor wiped his map", trap -> {
			boolean[] visited = Dungeon.level.visited.clone();
			return () -> away( trap ) && forgot( visited );
		}));
		all.put( WeakeningTrap.class, new Effect( TrapEffects::weakens, Weakness.class ).arena( ( world, kit ) -> {
			Greatsword sword = new Greatsword();
			sword.identify();
			kit.hero().belongings.weapon = sword;
			kit.strength( sword.STRReq() );
			Mob rat = world.mob( new Rat(), besideBoth( world ));
			rat.HP = rat.HT = 1000;
			Buff.affect( rat, Paralysis.class, 100f );
		}));
		return all;
	}

	private static Effect armed( int waits, String failure, Arm arm, Class< ? >... covers ){
		return new Effect( watch( waits, failure, arm ), covers );
	}

	private static Body watch( int waits, String failure, Arm arm ){
		return ( d, trap, trigger ) -> {
			BooleanSupplier holds = arm.arm( trap );
			d.require( !holds.getAsBoolean(), "the effect held before the trap fired" );
			trigger.run();
			d.expect( d.until( holds, waits, () -> d.act( Action.WAIT )), failure );
		};
	}

	private static void chillSlows( Driver d, Trap trap, Runnable trigger ){
		int start = Dungeon.hero.pos;
		float base = cost( trigger );
		d.waitFor( () -> Dungeon.hero.buff( Chill.class ) != null, 2 );
		d.require( Dungeon.hero.buff( Chill.class ) != null && Dungeon.hero.buff( Frost.class ) == null, "the hero was never chilled, or froze outright" );
		float chilled = cost( () -> d.move( start ));
		d.expect( chilled > base + 0.1f && chilled <= 2.01f, "a chilled step cost " + chilled + " turns against " + base + " before the chill" );
	}

	private static float cost( Runnable action ){
		float start = Actor.now();
		action.run();
		return Actor.now() - start;
	}

	private static void guardianHunts( Driver d, Trap trap, Runnable trigger ){
		d.require( guardian() == null, "a guardian stood on the level before the trap fired" );
		trigger.run();
		GuardianTrap.Guardian guardian = guardian();
		d.expect( guardian != null, "the guardian trap summoned no guardian" );
		if (guardian == null) return;
		boolean hunts = d.until( () -> guardian.state == guardian.HUNTING, 30, () -> d.act( Action.WAIT ));
		d.expect( hunts, "the guardian never hunted the hero, " + guardian.state.getClass().getSimpleName() + " at " + guardian.pos );
	}

	private static GuardianTrap.Guardian guardian(){
		for (Mob mob : Dungeon.level.mobs) if (mob instanceof GuardianTrap.Guardian) return (GuardianTrap.Guardian) mob;
		return null;
	}

	private static void weakens( Driver d, Trap trap, Runnable trigger ){
		Mob rat = d.world().find( Rat.class );
		int ceiling = Math.round( Dungeon.hero.belongings.weapon.max() * 0.67f );
		int strongest = 0;
		for (int i = 0; i < 20 && strongest <= ceiling; i++) strongest = Math.max( strongest, blow( d, rat ));
		d.require( strongest > ceiling, "no blow before the trap beat the weakened ceiling of " + ceiling );
		trigger.run();
		int hits = 0;
		int after = 0;
		for (int i = 0; i < 24 && hits < 12; i++){
			int blow = blow( d, rat );
			if (blow > 0) hits++;
			after = Math.max( after, blow );
		}
		d.expect( Dungeon.hero.buff( Weakness.class ) != null && hits >= 12 && after <= ceiling,
				"weakened blows reached " + after + " over " + hits + " hits, ceiling " + ceiling );
	}

	private static int blow( Driver d, Mob rat ){
		d.require( rat.isAlive() && Dungeon.level.adjacent( Dungeon.hero.pos, rat.pos ), "the rat left the hero's reach" );
		int hp = rat.HP;
		d.move( rat.pos );
		return hp - rat.HP;
	}

	private static BooleanSupplier hurt(){
		int hp = Dungeon.hero.HP;
		return () -> Dungeon.hero.HP < hp;
	}

	private static int volume( Class< ? extends Blob > type ){
		Blob blob = Dungeon.level.blobs.get( type );
		return blob == null ? 0 : blob.volume;
	}

	private static int water(){
		int cells = 0;
		for (boolean wet : Dungeon.level.water) if (wet) cells++;
		return cells;
	}

	private static boolean away( Trap trap ){
		return Dungeon.level.distance( Dungeon.hero.pos, trap.pos ) > 1;
	}

	private static boolean forgot( boolean[] visited ){
		for (int i = 0; i < visited.length; i++) if (visited[ i ] && !Dungeon.level.visited[ i ]) return true;
		return false;
	}

	private static boolean onFloor( KindOfWeapon weapon ){
		for (Heap heap : Dungeon.level.heaps.valueList()) if (heap.items.contains( weapon )) return true;
		return false;
	}

	private static int besideTrap( World world ){
		int trap = TrapScenarios.adjacentTrap().pos;
		for (int offset : PathFinder.NEIGHBOURS8) if (world.free( trap + offset )) return trap + offset;
		throw new NoFixture( "no free cell beside the trap" );
	}

	private static int twoFromTrap( World world ){
		int trap = TrapScenarios.adjacentTrap().pos;
		PathFinder.buildDistanceMap( trap, BArray.not( world.level().solid, null ), 2 );
		for (int cell = 0; cell < world.level().length(); cell++){
			if (PathFinder.distance[ cell ] == 2 && world.free( cell )) return cell;
		}
		throw new NoFixture( "no free cell two steps from the trap" );
	}

	private static int besideBoth( World world ){
		int trap = TrapScenarios.adjacentTrap().pos;
		for (int offset : PathFinder.NEIGHBOURS8){
			int cell = trap + offset;
			if (world.free( cell ) && world.level().adjacent( cell, world.hero() )) return cell;
		}
		throw new NoFixture( "no free cell beside both the hero and the trap" );
	}
}
