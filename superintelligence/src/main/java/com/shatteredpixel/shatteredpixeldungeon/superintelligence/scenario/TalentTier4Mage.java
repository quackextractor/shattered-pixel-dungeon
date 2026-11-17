package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Light;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.ElementalBlast;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.WarpBeacon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.mage.WildMagic;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfCorrosion;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfDisintegration;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfFireblast;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfFrost;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfLightning;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfPrismaticLight;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfRegrowth;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Mage {

	private TalentTier4Mage(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( TalentTier4.of( Talent.BLAST_RADIUS, HeroClass.MAGE, new ElementalBlast(), ( world, kit ) -> {
			kit.hero().belongings.weapon = new MagesStaff( new WandOfMagicMissile() );
			Rat aim = world.mob( new Rat(), world.cellAtRange( 5, true ));
			aim.HP = aim.HT = 100000;
			Buff.affect( aim, Paralysis.class, 50f );
			int outside = 0;
			for (int cell = 0; cell < world.level().length(); cell++){
				if (!world.free( cell )) continue;
				if (Dungeon.level.distance( Dungeon.hero.pos, cell ) != 5) continue;
				if (new Ballistica( aim.pos, cell, Ballistica.PROJECTILE ).collisionPos != cell) continue;
				outside = cell;
				break;
			}
			if (outside == 0) throw new NoFixture( "no free cell five tiles from the aim" );
			Rat edge = world.mob( new Rat(), outside );
			edge.HP = edge.HT = 100000;
			Buff.affect( edge, Paralysis.class, 50f );
		}, d -> TalentTier4.useAbility( d ), () -> {
			Rat aim = new World().find( Rat.class );
			Rat edge = null;
			for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob mob
					: new ArrayList<>( new World().level().mobs ))
				if (mob instanceof Rat && mob != aim && Dungeon.level.distance( Dungeon.hero.pos, mob.pos ) == 5) edge = (Rat) mob;
			if (edge == null) throw new NoFixture( "the outer rat was not placed" );
			final Rat target = edge;
			int base = target.HP;
			return () -> target.HP < base || Dungeon.hero.pointsInTalent( Talent.BLAST_RADIUS ) == 1;
		}, "the blast did not reach the outer cell" ));
		all.add( TalentTier4.of( Talent.ELEMENTAL_POWER, HeroClass.MAGE, new ElementalBlast(), ( world, kit ) -> {
			kit.hero().belongings.weapon = new MagesStaff( new WandOfPrismaticLight() );
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 1000;
			Buff.affect( rat, Paralysis.class, 50f );
		}, d -> TalentTier4.useAbility( d ), () -> () -> {
			Light light = Dungeon.hero.buff( Light.class );
			return light != null && light.visualcooldown() > 50f;
		}, "the blast lit no stronger light" ));
		all.add( TalentTier4.of( Talent.REACTIVE_BARRIER, HeroClass.MAGE, new ElementalBlast(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 1000;
			Buff.affect( rat, Paralysis.class, 50f );
		}, d -> TalentTier4.useAbility( d ), () -> {
			int base = TalentFamily.barrier();
			return () -> TalentFamily.barrier() > base;
		}, "the blast did not raise a barrier" ));
		all.add( TalentTier4.of( Talent.REMOTE_BEACON, HeroClass.MAGE, new WarpBeacon(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.cellAtRange( 4, true ));
			rat.HP = rat.HT = 1000;
			Buff.affect( rat, Paralysis.class, 50f );
		}, d -> TalentTier4.useAbility( d ), () -> () -> {
			WarpBeacon.WarpBeaconTracker tracker = Dungeon.hero.buff( WarpBeacon.WarpBeaconTracker.class );
			return tracker != null && (int) TalentFamily.saved( tracker, "pos" ) == new World().find( Rat.class ).pos;
		}, "the beacon was not placed four tiles away" ));
		all.add( TalentTier4.of( Talent.TELEFRAG, HeroClass.MAGE, new WarpBeacon(), ( world, kit ) -> {},
				d -> d.act( Action.WAIT ), () -> () -> Dungeon.hero.pointsInTalent( Talent.TELEFRAG ) == 1,
				"telefrag was not chosen" ));
		all.add( TalentTier4.of( Talent.LONGRANGE_WARP, HeroClass.MAGE, new WarpBeacon(), ( world, kit ) -> {},
				d -> d.act( Action.WAIT ), () -> () -> Dungeon.hero.pointsInTalent( Talent.LONGRANGE_WARP ) == 1,
				"longrange warp was not chosen" ));
		all.add( TalentTier4.of( Talent.WILD_POWER, HeroClass.MAGE, new WildMagic(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 1000;
			Buff.affect( rat, Paralysis.class, 50f );
			Wand wand = new WandOfMagicMissile();
			wand.level( 3 );
			wand.curCharges = 10;
			wand.partialCharge = 5f;
			kit.give( wand );
			Buff.affect( kit.hero(), WildMagic.WildMagicTracker.class, 5f );
		}, d -> TalentTier4.useAbility( d ), () -> () -> {
			Wand wand = TalentTier4.hero().belongings.getItem( WandOfMagicMissile.class );
			if (Dungeon.hero.pointsInTalent( Talent.WILD_POWER ) == 0) return false;
			if (Dungeon.hero.buff( WildMagic.WildMagicTracker.class ) == null) Buff.affect( Dungeon.hero, WildMagic.WildMagicTracker.class, 5f );
			int[] level = new int[1];
			TalentTier4.quietly( () -> level[0] = wand.buffedLvl() );
			return level[0] > 3;
		}, "wild magic raised no wand power" ));
		all.add( TalentTier4.of( Talent.FIRE_EVERYTHING, HeroClass.MAGE, new WildMagic(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 100000;
			Buff.affect( rat, Paralysis.class, 50f );
			Wand[] wands = { new WandOfMagicMissile(), new WandOfPrismaticLight(), new WandOfFireblast(),
					new WandOfFrost(), new WandOfLightning(), new WandOfDisintegration(), new WandOfRegrowth(),
					new WandOfCorrosion() };
			for (Wand wand : wands){
				wand.curCharges = 10;
				wand.partialCharge = 5f;
				kit.give( wand );
			}
		}, d -> TalentTier4.useAbility( d ), () -> () -> {
			int fired = 0;
			for (Wand wand : Dungeon.hero.belongings.getAllItems( Wand.class )) if (wand.partialCharge < 4.9f) fired++;
			return fired == 5;
		}, "wild magic fired four wands instead of five" ));
		all.add( TalentTier4.of( Talent.CONSERVED_MAGIC, HeroClass.MAGE, new WildMagic(), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 100000;
			Buff.affect( rat, Paralysis.class, 50f );
			Wand wand = new WandOfMagicMissile();
			wand.curCharges = 0;
			wand.partialCharge = 0.8f;
			kit.give( wand );
		}, d -> TalentTier4.useAbility( d ), () -> {
			Wand wand = Dungeon.hero.belongings.getItem( WandOfMagicMissile.class );
			float base = wand.partialCharge;
			return () -> wand.partialCharge < base - 0.1f;
		}, "wild magic wasted the wand's stored charge" ));
		return all;
	}
}
