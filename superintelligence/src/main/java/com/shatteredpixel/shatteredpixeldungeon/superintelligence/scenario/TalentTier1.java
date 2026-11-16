package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barkskin;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barrier;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.CounterBuff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invisibility;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.PhysicalEmpower;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Regeneration;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.WandEmpower;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.ClericSpell;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.GuidingLight;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.HolyIntuition;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.ShieldOfLight;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.BrokenSeal;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClothArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Berry;
import com.shatteredpixel.shatteredpixeldungeon.items.food.Food;
import com.shatteredpixel.shatteredpixeldungeon.items.food.SupplyRation;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfAccuracy;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.SpiritBow;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Quarterstaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WornShortsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingKnife;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

final class TalentTier1 {

	private TalentTier1(){}

	private static final String USES = "uses_left_to_id";

	private static final int PRIMED = 2;

	private static final int SEARING = 3;

	private static final int SAMPLES = 200;

	private static final long SAMPLE_SEED = 0x7A1E47L;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( of( Talent.HEARTY_MEAL, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.health( 20, 100 );
			kit.hero().buff( Regeneration.class ).detach();
		}, TalentTier1::eat, () -> {
			int hp = hero().HP;
			return () -> hero().HP >= hp + 4;
		}, "eating below a third of HP healed less than 4" ));
		all.add( of( Talent.VETERANS_INTUITION, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.sturdy();
			ClothArmor armor = new ClothArmor();
			TalentFamily.prime( armor, USES, PRIMED );
			kit.hero().belongings.armor = armor;
			world.hunting( new Rat(), world.adjacentFree() );
		}, d -> d.waitFor( () -> progressed( hero().belongings.armor ), 20 ), () -> {
			Item armor = hero().belongings.armor;
			return armor::isIdentified;
		}, "one hit on the worn armour did not identify it" ));
		all.add( of( Talent.PROVOKED_ANGER, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.sturdy();
			kit.buff( Barrier.class ).setShield( 2 );
			world.hunting( new Rat(), world.adjacentFree() );
		}, d -> d.waitFor( () -> barrier() == 0, 20 ), () -> () -> has( Talent.ProvokedAngerTracker.class ),
				"breaking the barrier gave no provoked anger" ));
		all.add( of( Talent.IRON_WILL, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.health( 30, 100 );
			kit.hero().buff( Regeneration.class ).detach();
			world.hunting( new Rat(), world.adjacentFree() );
		}, d -> d.waitFor( () -> TalentFamily.saved( sealShield(), "initial_shield" ) > 0, 20 ), () -> {
			BrokenSeal.WarriorShield shield = sealShield();
			int max = shield.maxShield();
			return () -> TalentFamily.saved( shield, "initial_shield" ) > max;
		}, "the seal's shield came up no stronger than before the talent" ));
		all.add( of( Talent.EMPOWERING_MEAL, HeroClass.MAGE, ( world, kit ) -> kit.give( new WandOfMagicMissile() ), TalentTier1::eat, () -> {
			WandOfMagicMissile wand = hero().belongings.getItem( WandOfMagicMissile.class );
			float base = meanWandRoll( wand );
			return () -> has( WandEmpower.class ) && meanWandRoll( wand ) >= base + 1;
		}, "the meal did not empower the next wand damage" ).covers( WandEmpower.class ));
		all.add( of( Talent.SCHOLARS_INTUITION, HeroClass.MAGE, ( world, kit ) -> {
			kit.sturdy();
			TalentFamily.prime( kit.giveUnknown( new WandOfMagicMissile() ), USES, PRIMED );
			rat( world, world.cellAtRange( 3, true ));
		}, d -> d.until( () -> progressed( hero().belongings.getItem( WandOfMagicMissile.class )), 3,
				() -> aim( d, WandOfMagicMissile.class )), () -> {
			Item wand = hero().belongings.getItem( WandOfMagicMissile.class );
			return wand::isIdentified;
		}, "one zap did not identify the wand" ));
		all.add( of( Talent.LINGERING_MAGIC, HeroClass.MAGE, TalentTier1::ratAtThree, d -> aim( d, MagesStaff.class ),
				() -> () -> has( Talent.LingeringMagicTracker.class ), "the staff zap left no lingering magic" ));
		all.add( of( Talent.BACKUP_BARRIER, HeroClass.MAGE, TalentTier1::ratAtThree,
				d -> d.until( () -> barrier() > 0, 4, () -> aim( d, MagesStaff.class )), TalentTier1::barrierGrew,
				"spending the staff's last charge gave no barrier" ));
		all.add( of( Talent.CACHED_RATIONS, HeroClass.ROGUE, ( world, kit ) -> {
			kit.sturdy();
			Arrays.fill( world.level().mapped, true );
		}, d -> {
			d.fixture( d.env().actionMask()[ Action.TRAVEL_STAIRS.index ] > 0.5f, "no known path to the exit" );
			d.until( () -> Dungeon.depth == 2, 100, () -> d.act( Action.TRAVEL_STAIRS ));
		}, () -> {
			int depth = Dungeon.depth;
			float dropped = counted( Talent.CachedRationsDropped.class );
			return () -> Dungeon.depth > depth && counted( Talent.CachedRationsDropped.class ) > dropped && heaped( SupplyRation.class ) > 0;
		}, "the next floor held no cached ration" ));
		all.add( of( Talent.THIEFS_INTUITION, HeroClass.ROGUE, ( world, kit ) -> kit.giveUnknown( new RingOfAccuracy() ),
				d -> use( d, RingOfAccuracy.class ), () -> {
			RingOfAccuracy ring = hero().belongings.getItem( RingOfAccuracy.class );
			return ring::isKnown;
		}, "wearing the ring did not reveal its type" ));
		all.add( of( Talent.SUCKER_PUNCH, HeroClass.ROGUE, ( world, kit ) -> {
			kit.sturdy();
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 100;
			rat.state = rat.SLEEPING;
		}, d -> d.move( rat().pos ), () -> {
			Rat rat = rat();
			return () -> rat.buff( Talent.SuckerPunchTracker.class ) != null;
		}, "the surprise hit marked no sucker punch on the rat" ));
		all.add( of( Talent.PROTECTIVE_SHADOWS, HeroClass.ROGUE, ( world, kit ) -> {
			kit.sturdy();
			kit.buff( Invisibility.class, 20f );
		}, d -> d.waitFor( () -> barrier() > 0, 4 ), TalentTier1::barrierGrew, "waiting invisible built no barrier" ));
		all.add( of( Talent.NATURES_BOUNTY, HeroClass.HUNTRESS, ( world, kit ) -> {
			kit.sturdy();
			grass( world, 4 );
		}, d -> {
			int start = berries();
			d.until( () -> berries() > start, 25, () -> trample( d ));
		}, () -> {
			int start = berries();
			return () -> berries() > start;
		}, "trampling high grass dropped no berry" ).depth( 3 ));
		all.add( of( Talent.SURVIVALISTS_INTUITION, HeroClass.HUNTRESS, ( world, kit ) -> {
			kit.sturdy();
			ThrowingKnife knives = new ThrowingKnife();
			knives.quantity( 3 );
			TalentFamily.prime( kit.giveUnknown( knives ), USES, PRIMED );
			rat( world, world.cellAtRange( 2, true ));
		}, d -> d.until( () -> progressed( hero().belongings.getItem( ThrowingKnife.class )), 3, () -> {
			TalentFamily.unmask( d, Action.THROW );
			d.throwItem( ThrowingKnife.class );
			d.aimNearest();
		}), () -> {
			Item knives = hero().belongings.getItem( ThrowingKnife.class );
			return knives::isIdentified;
		}, "one knife hit did not identify the stack" ));
		all.add( of( Talent.FOLLOWUP_STRIKE, HeroClass.HUNTRESS, TalentTier1::ratAtThree,
				d -> d.until( () -> has( Talent.FollowupStrikeTracker.class ), 3, () -> aim( d, SpiritBow.class )), () -> () -> {
			Talent.FollowupStrikeTracker mark = hero().buff( Talent.FollowupStrikeTracker.class );
			return mark != null && mark.object == rat().id();
		}, "the bow hit marked no follow-up strike on the rat" ));
		all.add( of( Talent.NATURES_AID, HeroClass.HUNTRESS, ( world, kit ) -> {
			kit.sturdy();
			world.level().plant( new Sungrass.Seed(), world.adjacentFreeStraight() );
		}, d -> d.move( plantBeside() ), () -> {
			int bark = Barkskin.currentLevel( hero() );
			return () -> Barkskin.currentLevel( hero() ) > bark;
		}, "triggering a plant in view gave no barkskin" ));
		all.add( of( Talent.STRENGTHENING_MEAL, HeroClass.DUELIST, ( world, kit ) -> {}, TalentTier1::eat, () -> {
			float base = meanHeroRoll();
			return () -> has( PhysicalEmpower.class ) && meanHeroRoll() >= base + 1;
		}, "the meal did not empower the next melee damage" ).covers( PhysicalEmpower.class ));
		all.add( of( Talent.ADVENTURERS_INTUITION, HeroClass.DUELIST, ( world, kit ) -> {
			kit.sturdy();
			WornShortsword sword = new WornShortsword();
			TalentFamily.prime( sword, USES, PRIMED );
			kit.hero().belongings.weapon = sword;
			rat( world, world.adjacentFree() );
		}, d -> d.until( () -> progressed( hero().belongings.weapon ), 4, () -> d.move( rat().pos )), () -> {
			Item sword = hero().belongings.weapon;
			return sword::isIdentified;
		}, "one hit with the weapon did not identify it" ));
		all.add( of( Talent.PATIENT_STRIKE, HeroClass.DUELIST, ( world, kit ) -> kit.sturdy(), d -> d.act( Action.WAIT ),
				() -> () -> has( Talent.PatientStrikeTracker.class ), "waiting gave no patient strike" ));
		all.add( of( Talent.AGGRESSIVE_BARRIER, HeroClass.DUELIST, ( world, kit ) -> {
			kit.health( 40, 100 ).strength( 14 );
			kit.hero().belongings.weapon = (Quarterstaff) new Quarterstaff().identify();
		}, d -> use( d, Quarterstaff.class ), TalentTier1::barrierGrew, "the weapon ability at half HP gave no barrier" ));
		all.add( of( Talent.SATIATED_SPELLS, HeroClass.CLERIC, ( world, kit ) -> {}, TalentTier1::eat,
				() -> () -> has( Talent.SatiatedSpellsTracker.class ), "the meal readied no satiated spell" ));
		all.add( of( Talent.HOLY_INTUITION, HeroClass.CLERIC, ( world, kit ) -> kit.giveUnknown( new RingOfAccuracy() ), d -> {
			cast( d, HolyIntuition.INSTANCE );
			d.pick( RingOfAccuracy.class );
		}, () -> {
			Item ring = hero().belongings.getItem( RingOfAccuracy.class );
			return () -> ring.cursedKnown;
		}, "holy intuition did not reveal the ring's curse state" ));
		all.add( of( Talent.SEARING_LIGHT, HeroClass.CLERIC, ( world, kit ) -> {
			kit.sturdy();
			kit.hero().belongings.weapon = null;
			Rat rat = world.hunting( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 100;
			Buff.affect( rat, GuidingLight.Illuminated.class );
		}, d -> d.until( () -> rat().buff( GuidingLight.Illuminated.class ) == null, 3, () -> d.move( rat().pos )), () -> {
			Rat rat = rat();
			int hp = rat.HP;
			return () -> rat.buff( GuidingLight.Illuminated.class ) == null && rat.HP <= hp - SEARING;
		}, "the unarmed hit on the illuminated rat dealt under 3, so searing light added nothing" ));
		all.add( of( Talent.SHIELD_OF_LIGHT, HeroClass.CLERIC, ( world, kit ) -> {
			kit.sturdy();
			rat( world, world.cellAtRange( 2, true ));
		}, d -> {
			cast( d, ShieldOfLight.INSTANCE );
			d.aimNearest();
		}, () -> () -> {
			ShieldOfLight.ShieldOfLightTracker shield = hero().buff( ShieldOfLight.ShieldOfLightTracker.class );
			return shield != null && shield.object == rat().id();
		}, "shield of light did not guard against the rat" ));
		return all;
	}

	private static Scenario of( Talent talent, HeroClass hero, Scenario.Setup setup, Scenario.Script trigger,
			Supplier< BooleanSupplier > effect, String failure ){
		return TalentFamily.of( new TalentFamily.Probe( talent, hero, setup, trigger, effect, failure ));
	}

	private static Hero hero(){
		return TalentFamily.hero();
	}

	private static Rat rat(){
		return TalentFamily.rat();
	}

	private static void rat( World world, int cell ){
		Rat rat = world.mob( new Rat(), cell );
		rat.HP = rat.HT = 1000;
		Buff.affect( rat, Paralysis.class, 50f );
	}

	private static void ratAtThree( World world, HeroKit kit ){
		kit.sturdy();
		rat( world, world.cellAtRange( 3, true ));
	}

	private static void use( Driver d, Class< ? extends Item > type ){
		TalentFamily.use( d, type );
	}

	private static void eat( Driver d ){
		TalentFamily.eat( d );
	}

	private static void aim( Driver d, Class< ? extends Item > type ){
		TalentFamily.aim( d, type );
	}

	private static void cast( Driver d, ClericSpell spell ){
		TalentFamily.cast( d, spell );
	}

	private static boolean has( Class< ? extends Buff > type ){
		return TalentFamily.has( type );
	}

	private static int barrier(){
		return TalentFamily.barrier();
	}

	private static BooleanSupplier barrierGrew(){
		return TalentFamily.barrierGrew();
	}

	private static float counted( Class< ? extends CounterBuff > type ){
		CounterBuff counter = hero().buff( type );
		return counter == null ? 0 : counter.count();
	}

	private static BrokenSeal.WarriorShield sealShield(){
		BrokenSeal.WarriorShield shield = hero().buff( BrokenSeal.WarriorShield.class );
		if (shield == null) throw new Driver.Stop( "the warrior has no seal shield" );
		return shield;
	}

	private static boolean progressed( Item item ){
		return item.isIdentified() || TalentFamily.saved( item, USES ) < PRIMED;
	}

	private static float mean( IntSupplier roll ){
		Random.pushGenerator( SAMPLE_SEED );
		try {
			int total = 0;
			for (int i = 0; i < SAMPLES; i++) total += roll.getAsInt();
			return total / (float) SAMPLES;
		} finally {
			Random.popGenerator();
		}
	}

	private static float meanWandRoll( WandOfMagicMissile wand ){
		WandEmpower empower = hero().buff( WandEmpower.class );
		int left = empower == null ? 0 : empower.left;
		return mean( () -> {
			int damage = wand.damageRoll();
			if (empower != null) empower.left = left;
			return damage;
		});
	}

	private static float meanHeroRoll(){
		PhysicalEmpower empower = hero().buff( PhysicalEmpower.class );
		int left = empower == null ? 0 : empower.left;
		return mean( () -> {
			int damage = hero().damageRoll();
			if (empower != null) empower.left = left;
			return damage;
		});
	}

	private static int heaped( Class< ? extends Item > type ){
		int count = 0;
		for (Heap heap : Dungeon.level.heaps.valueList()) for (Item item : heap.items) if (type.isInstance( item )) count += item.quantity();
		return count;
	}

	private static int berries(){
		Item carried = hero().belongings.getItem( Berry.class );
		return heaped( Berry.class ) + (carried == null ? 0 : carried.quantity());
	}

	private static void grass( World world, int radius ){
		for (int cell = 0; cell < world.level().length(); cell++){
			int terrain = world.level().map[ cell ];
			if (world.level().distance( world.hero(), cell ) <= radius && world.free( cell )
					&& (terrain == Terrain.EMPTY || terrain == Terrain.EMPTY_DECO || terrain == Terrain.GRASS)){
				world.terrain( cell, Terrain.HIGH_GRASS );
			}
		}
	}

	private static void trample( Driver d ){
		PathFinder.buildDistanceMap( hero().pos, Dungeon.level.passable );
		int target = -1;
		for (int cell = 0; cell < Dungeon.level.length(); cell++){
			if (Dungeon.level.map[ cell ] != Terrain.HIGH_GRASS || PathFinder.distance[ cell ] == Integer.MAX_VALUE) continue;
			if (target < 0 || PathFinder.distance[ cell ] < PathFinder.distance[ target ]) target = cell;
		}
		d.require( target >= 0, "no high grass left to trample" );
		d.walkTo( target, 4 );
		d.move( target );
	}

	private static int plantBeside(){
		for (int offset : PathFinder.NEIGHBOURS8){
			if (Dungeon.level.plants.get( hero().pos + offset ) != null) return hero().pos + offset;
		}
		throw new Driver.Stop( "no plant beside the hero" );
	}
}
