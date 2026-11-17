package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.GreaterHaste;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Recharging;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.BrokenSeal;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfFrost;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Quarterstaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Sword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WornShortsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingKnife;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class TalentTier2Gear {

	private TalentTier2Gear(){}

	private static final int KILLS = 10;

	private static float swiftCost = -1f;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( momentum());
		all.add( of( Talent.LETHAL_HASTE, HeroClass.DUELIST, ( world, kit ) -> {
			kit.sturdy().strength( 20 );
			kit.hero().belongings.weapon = (Sword) new Sword().identify();
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 1;
		}, d -> TalentFamily.aim( d, Sword.class ), () -> () -> TalentFamily.has( GreaterHaste.class ),
				"the ability kill granted no greater haste" ));
		all.add( of( Talent.RUNIC_TRANSFERENCE, HeroClass.WARRIOR, ( world, kit ) -> {
			BrokenSeal seal = kit.give( new BrokenSeal() );
			seal.setGlyph( uncommonGlyph() );
		}, d -> d.act( Action.WAIT ), () -> {
			BrokenSeal seal = TalentFamily.hero().belongings.getItem( BrokenSeal.class );
			return () -> seal != null && seal.canTransferGlyph();
		}, "the seal refused an uncommon glyph" ));
		all.add( of( Talent.IMPROVISED_PROJECTILES, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.sturdy();
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 1000;
			kit.give( new Quarterstaff().identify() );
		}, d -> d.until( () -> TalentFamily.rat().buff( Blindness.class ) != null, 3, () -> {
			TalentFamily.unmask( d, Action.THROW );
			d.throwItem( Quarterstaff.class );
			d.aimNearest();
		}), () -> () -> TalentFamily.rat().buff( Blindness.class ) != null, "the thrown weapon blinded nothing" ));
		all.add( of( Talent.WAND_PRESERVATION, HeroClass.MAGE, ( world, kit ) -> {
			kit.emptyPack();
			MagesStaff staff = new MagesStaff( new WandOfMagicMissile() );
			staff.identify();
			kit.give( staff );
			kit.give( new WandOfFrost() );
		}, d -> {
			d.itemMenu( MagesStaff.class, MagesStaff.AC_IMBUE );
			d.step( Action.USE, d.slotOf( WandOfFrost.class ));
			d.menu( 0 );
		}, () -> () -> TalentFamily.has( Talent.WandPreservationCounter.class )
				&& TalentFamily.hero().belongings.getItem( WandOfMagicMissile.class ) != null,
				"the imbue destroyed the old wand" ));
		all.add( of( Talent.SHIELD_BATTERY, HeroClass.MAGE, ( world, kit ) -> {
			kit.sturdy();
			WandOfMagicMissile wand = kit.give( new WandOfMagicMissile() );
			wand.curCharges = wand.maxCharges;
		}, d -> {
			TalentFamily.use( d, WandOfMagicMissile.class );
			d.require( d.env().mode() == EnvMode.TARGETING, "the wand did not ask for a target" );
			d.step( Action.USE, ActionMapper.SELF_TARGET_ALIAS );
		}, TalentFamily::barrierGrew, "the self zap granted no shielding" ));
		all.add( of( Talent.SWIFT_EQUIP, HeroClass.DUELIST, ( world, kit ) -> {
			kit.strength( 14 );
			kit.give( new Quarterstaff().identify() );
			swiftCost = -1f;
		}, d -> {
			TalentFamily.unmask( d, Action.USE );
			d.act( Action.USE );
			float start = Actor.now();
			d.step( Action.USE, d.slotOf( Quarterstaff.class ));
			swiftCost = Actor.now() - start;
		}, () -> () -> swiftCost == 0f && TalentFamily.has( Talent.SwiftEquipCooldown.class ),
				"the swap cost a turn and left no swift equip cooldown" ));
		all.add( of( Talent.WEAPON_RECHARGING, HeroClass.DUELIST, ( world, kit ) -> {
			kit.sturdy().strength( 14 );
			kit.hero().belongings.weapon = (Quarterstaff) new Quarterstaff().identify();
			MeleeWeapon.Charger charger = kit.buff( MeleeWeapon.Charger.class );
			charger.charges = 0;
			charger.partialCharge = 0.95f;
			kit.buff( Recharging.class, 30f );
		}, d -> d.act( Action.WAIT ), () -> () -> TalentFamily.charges() >= 1, "one recharging turn gained no charge" ));
		all.add( of( Talent.DURABLE_PROJECTILES, HeroClass.HUNTRESS, ( world, kit ) -> kit.give( new ThrowingKnife() ),
				d -> d.act( Action.WAIT ), () -> {
			ThrowingKnife knife = TalentFamily.hero().belongings.getItem( ThrowingKnife.class );
			float before = knife.durabilityPerUse();
			return () -> knife.durabilityPerUse() < before;
		}, "the knife showed no extra durability" ));
		return all;
	}

	private static Scenario momentum(){
		List< Rat > rats = new ArrayList<>();
		int[] kills = { -1 };
		int[] refunds = { -1 };
		int[] strays = { -1 };
		float[] normal = { -1f };
		return of( Talent.LETHAL_MOMENTUM, HeroClass.WARRIOR, ( world, kit ) -> {
			kit.sturdy();
			kit.hero().belongings.weapon = (WornShortsword) new WornShortsword().identify();
			kills[ 0 ] = refunds[ 0 ] = strays[ 0 ] = -1;
			normal[ 0 ] = -1f;
			rats.clear();
			placeRats( world, rats );
		}, d -> {
			int killed = 0;
			int refunded = 0;
			int stray = 0;
			for (Rat rat : rats){
				d.walkTo( rat.pos, 12 );
				for (int i = 0; i < 6 && rat.isAlive(); i++){
					d.require( !TalentFamily.has( Talent.LethalMomentumTracker.class ),
							"a momentum point was already held before the attack on the rat at " + rat.pos );
					float start = Actor.now();
					d.move( rat.pos );
					float cost = Actor.now() - start;
					boolean dead = !rat.isAlive();
					if (dead) killed++;
					if (cost == 0f){
						if (dead) refunded++;
						else stray++;
					} else if (cost != normal[ 0 ]){
						stray++;
					}
				}
			}
			kills[ 0 ] = killed;
			refunds[ 0 ] = refunded;
			strays[ 0 ] = stray;
		}, () -> {
			normal[ 0 ] = TalentFamily.hero().attackDelay();
			return () -> normal[ 0 ] > 0f && kills[ 0 ] == KILLS && refunds[ 0 ] >= KILLS / 2
					&& refunds[ 0 ] <= kills[ 0 ] && strays[ 0 ] == 0;
		}, "a killing blow cost a turn, or a blow that cost nothing killed nothing" );
	}

	private static void placeRats( World world, List< Rat > rats ){
		int length = world.level().length();
		boolean[] passable = new boolean[ length ];
		for (int cell = 0; cell < length; cell++) passable[ cell ] = world.level().passable[ cell ] && !world.level().pit[ cell ];
		passable[ world.hero() ] = true;
		int origin = -1;
		for (int offset : PathFinder.NEIGHBOURS8){
			int cell = world.hero() + offset;
			if (cell >= 0 && cell < length && passable[ cell ]){ origin = cell; break; }
		}
		if (origin < 0) throw new NoFixture( "the hero stands in a dead end" );
		PathFinder.buildDistanceMap( origin, passable );
		for (int distance = 2; distance <= 8 && rats.size() < KILLS; distance++){
			for (int cell = 0; cell < length && rats.size() < KILLS; cell++){
				if (PathFinder.distance[ cell ] != distance || !world.free( cell )) continue;
				Rat rat = world.mob( new Rat(), cell );
				rat.HP = rat.HT = 1;
				rat.state = rat.SLEEPING;
				Buff.affect( rat, Paralysis.class, 50f );
				rats.add( rat );
			}
		}
		if (rats.size() != KILLS) throw new NoFixture( "only " + rats.size() + " reachable cells, need " + KILLS );
		java.util.Set< Integer > seen = new java.util.HashSet<>();
		for (Rat rat : rats) if (!seen.add( rat.pos )) throw new NoFixture( "two rats share cell " + rat.pos );
	}

	@SuppressWarnings("unchecked")
	private static Armor.Glyph uncommonGlyph(){
		return Armor.Glyph.randomUncommon();
	}

	private static Scenario of( Talent talent, HeroClass hero, Scenario.Setup setup, Scenario.Script trigger,
			Supplier< BooleanSupplier > effect, String failure ){
		return TalentFamily.of( new TalentFamily.Probe( talent, hero, setup, trigger, effect, failure ));
	}
}
