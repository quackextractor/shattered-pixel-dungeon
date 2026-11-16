package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invisibility;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.AuraOfProtection;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Cleanse;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.ClericSpell;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.HallowedGround;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.HolyLance;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.LayOnHands;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.MnemonicPrayer;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.WallOfLight;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class TalentTier3Cleric {

	private TalentTier3Cleric(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( TalentTier3.of( Talent.CLEANSE, HeroClass.CLERIC, ( world, kit ) -> {
			kit.health( 400, 1000 );
			chargeTome();
		}, d -> cast( d, Cleanse.INSTANCE ), () -> {
			int base = TalentTier3.barrier();
			return () -> TalentTier3.barrier() > base;
		}, "cleanse gave no shield" ));
		all.add( TalentTier3.of( Talent.LIGHT_READING, HeroClass.CLERIC, ( world, kit ) -> {
			kit.sturdy();
			HolyTome tome = (HolyTome) TalentTier3.hero().belongings.artifact;
			TalentTier3.hero().belongings.artifact = null;
			kit.give( tome );
		}, d -> d.act( Action.WAIT ), () -> {
			HolyTome tome = TalentTier3.hero().belongings.getItem( HolyTome.class );
			boolean base = tome.actions( TalentTier3.hero() ).contains( HolyTome.AC_CAST );
			return () -> !base && tome.actions( TalentTier3.hero() ).contains( HolyTome.AC_CAST );
		}, "the unworn tome offered no cast" ));
		all.add( TalentTier3.of( Talent.HOLY_LANCE, HeroClass.CLERIC, ( world, kit ) -> {
			kit.sturdy();
			chargeTome();
			TalentTier3.rat( world, world.cellAtRange( 3, true ));
		}, d -> cast( d, HolyLance.INSTANCE ), () -> {
			int base = TalentTier3.rat().HP;
			return () -> TalentTier3.rat().HP <= base - 28;
		}, "the lance dealt under the boosted minimum" ));
		all.add( TalentTier3.of( Talent.HALLOWED_GROUND, HeroClass.CLERIC, ( world, kit ) -> {
			kit.sturdy();
			chargeTome();
			Rat rat = world.mob( new Rat(), world.cellAtRange( 3, true ) );
			rat.HP = rat.HT = 1000;
		}, d -> cast( d, HallowedGround.INSTANCE ), () -> {
			Rat rat = TalentTier3.rat();
			return () -> rat.buff( com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Roots.class ) != null
					|| rat.buff( com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.GuidingLight.Illuminated.class ) != null;
		}, "the hallowed ground caught no one" ));
		all.add( TalentTier3.of( Talent.MNEMONIC_PRAYER, HeroClass.CLERIC, ( world, kit ) -> {
			kit.sturdy();
			chargeTome();
			com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Wraith ally =
					world.mob( new com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Wraith(), world.adjacentFree() );
			ally.alignment = com.shatteredpixel.shatteredpixeldungeon.actors.Char.Alignment.ALLY;
			ally.HP = ally.HT = 1000;
			Buff.affect( ally, com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste.class, 12f );
		}, d -> {
			com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Wraith ally =
					new World().find( com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Wraith.class );
			TalentTier3.use( d, HolyTome.class );
			int option = option( MnemonicPrayer.INSTANCE );
			d.require( option >= 0, "MnemonicPrayer is not offered by the tome" );
			d.menu( option );
			d.require( d.env().mode() == EnvMode.TARGETING, "the prayer asked for no target" );
			d.step( Action.USE, d.direction( ally.pos ));
		}, () -> {
			com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Wraith ally =
					new World().find( com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Wraith.class );
			com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste haste =
					ally.buff( com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste.class );
			float base = haste.cooldown();
			return () -> {
				com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste now =
						ally.buff( com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Haste.class );
				return now != null && now.cooldown() > base + 1f;
			};
		}, "the prayer did not lengthen the spell" ));
		all.add( TalentTier3.of( Talent.LAY_ON_HANDS, HeroClass.CLERIC, ( world, kit ) -> {
			kit.sturdy();
			chargeTome();
			Rat rat = world.mob( new Rat(), world.adjacentFree() );
			rat.HT = 1000;
			rat.HP = 500;
			Buff.affect( rat, com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis.class, 50f );
		}, d -> cast( d, LayOnHands.INSTANCE ), () -> {
			int base = TalentTier3.rat().HP;
			return () -> TalentTier3.rat().HP >= base + 12;
		}, "the prayer of healing gave under twelve health" ));
		all.add( TalentTier3.of( Talent.AURA_OF_PROTECTION, HeroClass.CLERIC, ( world, kit ) -> {
			kit.sturdy();
			chargeTome();
		}, d -> cast( d, AuraOfProtection.INSTANCE ), () -> {
			float base = com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor.Glyph
					.genericProcChanceMultiplier( TalentTier3.hero() );
			return () -> TalentTier3.has( AuraOfProtection.AuraBuff.class )
					&& com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor.Glyph
							.genericProcChanceMultiplier( TalentTier3.hero() ) > base;
		}, "the aura raised no armour proc chance" ));
		all.add( TalentTier3.of( Talent.WALL_OF_LIGHT, HeroClass.CLERIC, ( world, kit ) -> {
			kit.sturdy();
			chargeTome();
			Rat rat = world.mob( new Rat(), world.cellAtRange( 3, true ) );
			rat.HP = rat.HT = 1000;
		}, d -> {
			cast( d, WallOfLight.INSTANCE );
			d.waitFor( () -> wall() > 0, 3 );
		}, () -> () -> wall() > 0, "no wall of light stood" ));
		return all;
	}

	private static void chargeTome(){
		HolyTome tome = (HolyTome) TalentTier3.hero().belongings.artifact;
		for (int i = 0; i < 7; i++) tome.upgrade();
		tome.directCharge( 10f );
	}

	private static int wall(){
		com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob light =
				com.shatteredpixel.shatteredpixeldungeon.Dungeon.level.blobs.get( WallOfLight.LightWall.class );
		return light == null ? 0 : light.volume;
	}

	private static void cast( Driver d, ClericSpell spell ){
		TalentTier3.use( d, HolyTome.class );
		int option = option( spell );
		d.require( option >= 0, spell.name() + " is not offered by the tome" );
		d.menu( option );
		if (d.env().mode() == EnvMode.TARGETING) d.aimNearest();
	}

	private static int option( ClericSpell spell ){
		int index = 0;
		for (int tier = 1; tier <= Talent.MAX_TALENT_TIERS; tier++){
			for (ClericSpell offered : ClericSpell.getSpellList( TalentTier3.hero(), tier )){
				if (offered == spell) return 1 + index;
				if (offered.canCast( TalentTier3.hero() )) index++;
			}
		}
		return -1;
	}
}
