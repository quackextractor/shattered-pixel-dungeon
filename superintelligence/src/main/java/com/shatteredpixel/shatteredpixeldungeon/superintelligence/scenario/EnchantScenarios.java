package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Chill;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Corruption;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Regeneration;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Terror;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Blazing;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Blocking;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Blooming;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Chilling;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Corrupting;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Crystal;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Elastic;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Eldritch;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Grim;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Kinetic;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Lucky;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Projecting;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Shocking;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Unstable;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Vampiric;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Venomous;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Vorpal;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.watabou.utils.Bundle;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class EnchantScenarios {

	private EnchantScenarios(){}

	static final int ATTACKS = 20;

	static final int TARGET_HP = 1000;

	private static final int ENCHANT_LEVEL = 30;

	static final Scenario.Setup TARGET = ( world, kit ) -> rat( world, world.adjacentFree(), TARGET_HP );

	static final Scenario.Setup DRY = ( world, kit ) -> {
		int cell = world.adjacentFree();
		for (int c = 0; c < Dungeon.level.length(); c++){
			if (Dungeon.level.water[ c ] && Dungeon.level.distance( cell, c ) <= 2) world.terrain( c, Terrain.EMPTY );
		}
		rat( world, cell, TARGET_HP );
	};

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( proc( "enchant.Blazing", Blazing.class, DRY, ratGains( Burning.class ), "the blazing weapon never set the rat burning" ));
		all.add( proc( "enchant.Blocking", Blocking.class, TARGET, () -> {
			int shield = Dungeon.hero.shielding();
			return () -> Dungeon.hero.shielding() > shield;
		}, "the blocking weapon never shielded the hero" ));
		all.add( proc( "enchant.Blooming", Blooming.class, DRY, () -> {
			int grass = cells( Terrain.HIGH_GRASS, Terrain.FURROWED_GRASS );
			return () -> cells( Terrain.HIGH_GRASS, Terrain.FURROWED_GRASS ) > grass;
		}, "the blooming weapon grew no grass" ));
		all.add( proc( "enchant.Chilling", Chilling.class, TARGET, ratGains( Chill.class ), "the chilling weapon never chilled the rat" ));
		all.add( proc( "enchant.Corrupting", Corrupting.class, pack( 3, 1 ), () -> EnchantScenarios::corrupted,
				"no rat killed by the corrupting weapon rose as a corrupted ally" ));
		all.add( proc( "enchant.Crystal", Crystal.class, ( world, kit ) -> {
			((Crystal) weapon().enchantment).setDurability( 50 );
			TARGET.build( world, kit );
		}, () -> {
			float worn = durability();
			return () -> durability() > worn;
		}, "the crystal weapon never repaired above its starting durability" ));
		all.add( proc( "enchant.Elastic", Elastic.class, ( world, kit ) -> rat( world, world.adjacentFreeWithRoomBehind( 2 ), TARGET_HP ), () -> {
			Mob rat = PotionScenarios.rat();
			return () -> rat.isAlive() && !Dungeon.level.adjacent( Dungeon.hero.pos, rat.pos );
		}, "the elastic weapon never knocked the rat away" ));
		all.add( proc( "enchant.Eldritch", Eldritch.class, ( world, kit ) -> {
			TARGET.build( world, kit );
			rat( world, world.cellAtRange( 3, true ), TARGET_HP );
		}, () -> {
			Mob watcher = farthestRat();
			return () -> watcher.buff( Terror.class ) != null;
		}, "the eldritch weapon never terrified the watching rat" ));
		all.add( proc( "enchant.Grim", Grim.class, ( world, kit ) -> rat( world, world.adjacentFree(), TARGET_HP ).HP = 300,
				EnchantScenarios::grim, "the grim weapon never slew the rat on a hit that could not kill it" ));
		all.add( proc( "enchant.Kinetic", Kinetic.class, ( world, kit ) -> {
			rat( world, world.adjacentFree(), 1 );
			rat( world, world.adjacentFree(), TARGET_HP );
		}, EnchantScenarios::kinetic, "the kinetic weapon never added the stored overkill to the next hit" ));
		all.add( proc( "enchant.Lucky", Lucky.class, pack( 3, 1 ), () -> {
			int heaps = Dungeon.level.heaps.valueList().size();
			return () -> Dungeon.level.heaps.valueList().size() > heaps;
		}, "no rat killed by the lucky weapon dropped loot" ));
		all.add( proc( "enchant.Projecting", Projecting.class, ( world, kit ) -> rat( world, world.cellAtRange( 2, true ), TARGET_HP ), () -> {
			Mob rat = PotionScenarios.rat();
			int hp = rat.HP;
			int home = Dungeon.hero.pos;
			return () -> rat.HP < hp && Dungeon.hero.pos == home;
		}, "the projecting weapon never hit the rat two cells away" ));
		all.add( proc( "enchant.Shocking", Shocking.class, ( world, kit ) -> {
			Rat target = rat( world, world.adjacentFree(), TARGET_HP );
			rat( world, outOfReachBeside( world, target.pos ), TARGET_HP );
		}, () -> {
			Mob bystander = farthestRat();
			int hp = bystander.HP;
			return () -> bystander.HP < hp;
		}, "the shocking weapon never arced into the rat beside the target" ));
		all.add( proc( "enchant.Unstable", Unstable.class, TARGET, () -> {
			Mob rat = PotionScenarios.rat();
			Set< Buff > before = new HashSet<>( rat.buffs() );
			return () -> newNegative( rat, before );
		}, "the unstable weapon never procced an enchantment that left a debuff on the rat" ));
		all.add( proc( "enchant.Vampiric", Vampiric.class, ( world, kit ) -> {
			kit.health( 100, TARGET_HP );
			Buff.detach( kit.hero(), Regeneration.class );
			TARGET.build( world, kit );
		}, () -> {
			int hp = Dungeon.hero.HP;
			return () -> Dungeon.hero.HP > hp;
		}, "the vampiric weapon never healed the hero" ));
		all.add( proc( "enchant.Venomous", Venomous.class, TARGET, ratGains( Poison.class ), "the venomous weapon never poisoned the rat" ));
		all.add( proc( "enchant.Vorpal", Vorpal.class, TARGET, ratGains( Bleeding.class ), "the vorpal weapon never made the rat bleed" ));
		all.addAll( WeaponCurseScenarios.all() );
		return all;
	}

	static Scenario proc( String id, Class< ? extends Weapon.Enchantment > type, Scenario.Setup arena, Supplier< BooleanSupplier > arm,
			String failure ){
		return new Scenario( id, type.getSimpleName() + " procs from a worn weapon", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Weapon.Enchantment enchantment = Reflection.newInstance( type );
					Weapon weapon = weapon();
					weapon.level( enchantment.curse() ? 0 : ENCHANT_LEVEL );
					weapon.cursed = enchantment.curse();
					weapon.enchant( enchantment );
					weapon.identify();
					arena.build( world, kit );
				},
				d -> {
					BooleanSupplier holds = arm.get();
					d.require( !holds.getAsBoolean(), "the " + type.getSimpleName() + " effect held before the first attack" );
					d.expect( d.until( holds, ATTACKS, () -> strike( d )), failure );
				} ).covers( type );
	}

	private static void strike( Driver d ){
		Mob target = target();
		if (target == null) d.act( Action.WAIT );
		else if (Dungeon.level.adjacent( Dungeon.hero.pos, target.pos )) d.move( target.pos );
		else d.act( Action.INTERACT );
	}

	private static Mob target(){
		Mob best = null;
		for (Mob mob : Dungeon.level.mobs){
			if (!mob.isAlive() || mob.alignment != Char.Alignment.ENEMY || !Dungeon.hero.canAttack( mob )) continue;
			if (best == null || mob.HP < best.HP || (mob.HP == best.HP && mob.pos < best.pos)) best = mob;
		}
		return best;
	}

	static Weapon weapon(){
		return (Weapon) Dungeon.hero.belongings.weapon;
	}

	static Rat rat( World world, int cell, int hp ){
		Rat rat = world.mob( new Rat(), cell );
		rat.HP = rat.HT = hp;
		rat.state = rat.PASSIVE;
		return rat;
	}

	private static Scenario.Setup pack( int count, int hp ){
		return ( world, kit ) -> {
			for (int i = 0; i < count; i++) rat( world, world.adjacentFree(), hp );
		};
	}

	static Supplier< BooleanSupplier > ratGains( Class< ? extends Buff > type ){
		return () -> {
			Mob rat = PotionScenarios.rat();
			return () -> rat.buff( type ) != null;
		};
	}

	static Mob farthestRat(){
		List< Mob > rats = new ArrayList<>();
		for (Mob mob : Dungeon.level.mobs) if (mob instanceof Rat) rats.add( mob );
		return rats.stream().max( Comparator.< Mob >comparingInt( m -> Dungeon.level.distance( Dungeon.hero.pos, m.pos )) ).orElseThrow();
	}

	private static int outOfReachBeside( World world, int target ){
		for (int offset : PathFinder.NEIGHBOURS8){
			int cell = target + offset;
			if (world.free( cell ) && Dungeon.level.distance( world.hero(), cell ) == 2) return cell;
		}
		throw new NoFixture( "no free cell beside the target and out of the hero's reach" );
	}

	static int cells( int... terrains ){
		int count = 0;
		for (int cell : Dungeon.level.map) for (int terrain : terrains) if (cell == terrain) count++;
		return count;
	}

	static boolean newNegative( Char ch, Set< Buff > before ){
		for (Buff buff : ch.buffs()) if (buff.type == Buff.buffType.NEGATIVE && !before.contains( buff )) return true;
		return false;
	}

	private static boolean corrupted(){
		for (Mob mob : Dungeon.level.mobs){
			if (mob instanceof Rat && mob.isAlive() && mob.alignment == Char.Alignment.ALLY && mob.buff( Corruption.class ) != null) return true;
		}
		return false;
	}

	static float durability(){
		Bundle bundle = new Bundle();
		weapon().enchantment.storeInBundle( bundle );
		return bundle.getFloat( "durability" );
	}

	static int plainMax(){
		return weapon().max() + Math.max( 0, Dungeon.hero.STR() - weapon().STRReq() );
	}

	private static BooleanSupplier grim(){
		Mob rat = PotionScenarios.rat();
		int plainMax = plainMax();
		int[] last = { rat.HP };
		boolean[] outright = { false };
		return () -> {
			outright[ 0 ] |= !rat.isAlive() && last[ 0 ] > plainMax;
			last[ 0 ] = rat.HP;
			return outright[ 0 ];
		};
	}

	private static BooleanSupplier kinetic(){
		Mob tank = PotionScenarios.rat();
		for (Mob mob : Dungeon.level.mobs) if (mob.HP > tank.HP) tank = mob;
		Mob struck = tank;
		int plainMax = plainMax();
		int[] last = { struck.HP, stored() };
		boolean[] seen = { false, false };
		return () -> {
			if (last[ 1 ] > 0 && last[ 0 ] - struck.HP > plainMax) seen[ 1 ] = true;
			last[ 0 ] = struck.HP;
			last[ 1 ] = stored();
			seen[ 0 ] |= last[ 1 ] > 0;
			return seen[ 0 ] && seen[ 1 ];
		};
	}

	private static int stored(){
		Kinetic.ConservedDamage conserved = Dungeon.hero.buff( Kinetic.ConservedDamage.class );
		return conserved == null ? 0 : conserved.damageBonus();
	}
}
