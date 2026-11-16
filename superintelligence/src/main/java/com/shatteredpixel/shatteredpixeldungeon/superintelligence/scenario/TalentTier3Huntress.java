package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barkskin;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.RevealedArea;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.SnipersMark;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Dewdrop;
import com.shatteredpixel.shatteredpixeldungeon.items.Waterskin;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.SpiritBow;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Blazing;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingKnife;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.PoisonDart;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.watabou.utils.Random;

import java.util.ArrayList;
import java.util.List;

final class TalentTier3Huntress {

	private TalentTier3Huntress(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( TalentTier3.of( Talent.POINT_BLANK, HeroClass.HUNTRESS, ( world, kit ) -> {
			kit.sturdy();
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HP = rat.HT = 1000;
		}, d -> d.act( Action.WAIT ), () -> {
			float base = new ThrowingKnife().accuracyFactor( TalentTier3.hero(), TalentTier3.rat() );
			return () -> new ThrowingKnife().accuracyFactor( TalentTier3.hero(), TalentTier3.rat() ) > base;
		}, "the point blank throw stayed as accurate as before" ));
		all.add( TalentTier3.of( Talent.SEER_SHOT, HeroClass.HUNTRESS, ( world, kit ) -> kit.sturdy(), d -> {
			TalentTier3.use( d, SpiritBow.class );
			d.require( d.env().mode() == com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode.TARGETING,
					"the bow asked for no target: " + d.env().mode() );
			int cell = new World().adjacentFree();
			d.step( Action.USE, d.direction( cell ));
		}, () -> () -> TalentTier3.has( RevealedArea.class ), "the bow shot revealed no area" ).covers( RevealedArea.class ));
		all.add( TalentTier3.of( Talent.FARSIGHT, HeroClass.HUNTRESS, ( world, kit ) -> kit.sturdy(),
				d -> d.act( Action.WAIT ), () -> {
			int base = com.shatteredpixel.shatteredpixeldungeon.Dungeon.observationDistance();
			return () -> com.shatteredpixel.shatteredpixeldungeon.Dungeon.observationDistance() > base;
		}, "the hero saw no further" ));
		all.add( TalentTier3.of( Talent.SHARED_ENCHANTMENT, HeroClass.HUNTRESS, ( world, kit ) -> {
			kit.sturdy();
			SpiritBow bow = TalentTier3.hero().belongings.getItem( SpiritBow.class );
			bow.enchantment = new Blazing();
			Rat rat = world.mob( new Rat(), world.cellAtRange( 2, true ) );
			rat.HP = rat.HT = 1000;
		}, d -> d.act( Action.WAIT ), () -> {
			Rat rat = TalentTier3.rat();
			ThrowingKnife knife = new ThrowingKnife();
			return () -> {
				Random.pushGenerator( 0x5A2EL );
				try {
					for (int i = 0; i < 40 && rat.buff( Burning.class ) == null; i++){
						knife.proc( TalentTier3.hero(), rat, 10 );
					}
				} finally {
					Random.popGenerator();
				}
				boolean caught = rat.buff( Burning.class ) != null;
				com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff.detach( rat, Burning.class );
				return caught;
			};
		}, "the bow enchant reached no thrown knife" ));
		all.add( TalentTier3.of( Talent.SHARED_UPGRADES, HeroClass.HUNTRESS, ( world, kit ) -> {
			kit.sturdy();
			ThrowingKnife knife = new ThrowingKnife();
			knife.upgrade( 3 );
			kit.give( knife );
			Rat rat = world.mob( new Rat(), world.cellAtRange( 3, true ) );
			rat.HP = rat.HT = 1000;
		}, d -> {
			TalentFamily.unmask( d, Action.THROW );
			d.throwItem( ThrowingKnife.class );
			d.aimNearest();
		}, () -> {
			SnipersMark mark = TalentTier3.hero().buff( SnipersMark.class );
			float base = mark == null ? 0f : mark.percentDmgBonus;
			return () -> {
				SnipersMark now = TalentTier3.hero().buff( SnipersMark.class );
				return now != null && now.percentDmgBonus > base;
			};
		}, "the thrown weapon added no mark damage" ));
		all.add( TalentTier3.of( Talent.DURABLE_TIPS, HeroClass.HUNTRESS, ( world, kit ) -> kit.sturdy(),
				d -> d.act( Action.WAIT ), () -> {
			float base = new PoisonDart().durabilityPerUse( 0 );
			return () -> new PoisonDart().durabilityPerUse( 0 ) < base;
		}, "the tipped dart wore no slower" ));
		all.add( TalentTier3.of( Talent.BARKSKIN, HeroClass.HUNTRESS, ( world, kit ) -> {
			kit.sturdy();
			for (int offset : com.watabou.utils.PathFinder.NEIGHBOURS8){
				int cell = world.hero() + offset;
				if (world.free( cell )) world.terrain( cell, Terrain.HIGH_GRASS );
			}
		}, d -> {
			int cell = new World().beside( Terrain.HIGH_GRASS );
			d.move( cell );
		}, () -> {
			int base = Barkskin.currentLevel( TalentTier3.hero() );
			return () -> Barkskin.currentLevel( TalentTier3.hero() ) > base;
		}, "trampling grass gave no barkskin" ));
		all.add( TalentTier3.of( Talent.SHIELDING_DEW, HeroClass.HUNTRESS, ( world, kit ) -> {
			kit.sturdy();
			Waterskin skin = TalentTier3.hero().belongings.getItem( Waterskin.class );
			if (skin != null) TalentTier3.hero().belongings.backpack.items.remove( skin );
			world.drop( new Dewdrop(), world.adjacentFree() );
		}, d -> {
			int cell = -1;
			for (int offset : com.watabou.utils.PathFinder.NEIGHBOURS8){
				if (new World().level().heaps.get( new World().hero() + offset ) != null) cell = new World().hero() + offset;
			}
			d.require( cell >= 0, "the dewdrop heap vanished" );
			d.move( cell );
		}, () -> {
			int base = TalentTier3.barrier();
			return () -> TalentTier3.barrier() > base;
		}, "drinking the dew gave no shield" ));
		return all;
	}
}
