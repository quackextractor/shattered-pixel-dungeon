package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Charm;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Frost;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Ooze;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.MirrorImage;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.curses.AntiEntropy;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.curses.Bulk;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.curses.Corrosion;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.curses.Displacement;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.curses.Metabolism;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.curses.Multiplicity;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.curses.Overgrowth;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.curses.Stench;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.Affection;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.Entanglement;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.Flow;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.Potential;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.Repulsion;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.Swiftness;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.Thorns;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.Viscosity;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.Earthroot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ArmourScenarios.GLYPH_LEVEL;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ArmourScenarios.fight;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.ArmourScenarios.stride;

final class GlyphFights {

	private GlyphFights(){}

	private static final Scenario.Setup PLAIN = ( world, kit ) -> {};

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( fight( "glyph.Affection", Affection.class, GLYPH_LEVEL, PLAIN, "affection never charmed the rat",
				() -> () -> rat().buff( Charm.class ) != null ));
		all.add( fight( "glyph.Entanglement", Entanglement.class, GLYPH_LEVEL, PLAIN, "entanglement never rooted earthen armour on the hero",
				() -> () -> Dungeon.hero.buff( Earthroot.Armor.class ) != null ));
		all.add( fight( "glyph.Potential", Potential.class, GLYPH_LEVEL, ( world, kit ) -> kit.give( new WandOfMagicMissile() ).curCharges = 0,
				"potential never charged the wand in one hit", () -> {
			Wand wand = Dungeon.hero.belongings.getItem( WandOfMagicMissile.class );
			float[] last = { charge( wand ) };
			return latch( () -> {
				float now = charge( wand );
				boolean jumped = now - last[ 0 ] >= 0.9f;
				last[ 0 ] = now;
				return jumped;
			});
		}));
		all.add( fight( "glyph.Repulsion", Repulsion.class, GLYPH_LEVEL, PLAIN, "repulsion never knocked the rat away", () -> {
			Mob rat = rat();
			return latch( () -> rat.isAlive() && Dungeon.level.distance( rat.pos, Dungeon.hero.pos ) > 1 );
		}));
		all.add( fight( "glyph.Thorns", Thorns.class, GLYPH_LEVEL, PLAIN, "thorns never made the rat bleed",
				() -> () -> rat().buff( Bleeding.class ) != null ));
		all.add( fight( "glyph.Viscosity", Viscosity.class, 0, PLAIN, "viscosity never deferred a hit's damage",
				() -> () -> Dungeon.hero.buff( Viscosity.DeferedDamage.class ) != null ));
		all.add( stride( "glyph.Flow", Flow.class, GLYPH_LEVEL, Terrain.WATER, false, "flow did not speed a step into water" ));
		all.add( stride( "glyph.Swiftness", Swiftness.class, GLYPH_LEVEL, -1, false, "swiftness did not speed a step with no foe near" ));
		all.add( stride( "acurse.Bulk", Bulk.class, 0, Terrain.DOOR, true, "bulk did not slow a step into a door" ));
		all.add( fight( "acurse.AntiEntropy", AntiEntropy.class, 0, PLAIN, "anti-entropy never froze the rat and set the hero alight",
				() -> () -> rat().buff( Frost.class ) != null && Dungeon.hero.buff( Burning.class ) != null ));
		all.add( fight( "acurse.Corrosion", Corrosion.class, 0, PLAIN, "corrosion never oozed the hero and the rat",
				() -> () -> Dungeon.hero.buff( Ooze.class ) != null && rat().buff( Ooze.class ) != null ).seeds( "SCN-1" ));
		all.add( fight( "acurse.Displacement", Displacement.class, 0, PLAIN, "displacement never teleported the waiting hero", () -> {
			int cell = Dungeon.hero.pos;
			return latch( () -> Dungeon.hero.pos != cell );
		}).seeds( "SCN-0" ));
		all.add( fight( "acurse.Metabolism", Metabolism.class, GLYPH_LEVEL, ( world, kit ) -> kit.health( 500, 1000 ),
				"metabolism never traded hunger for health in one hit", () -> {
			Hero hero = Dungeon.hero;
			int[] last = { hero.HP, hunger() };
			return latch( () -> {
				boolean traded = hero.HP > last[ 0 ] && hunger() >= last[ 1 ] + 30;
				last[ 0 ] = hero.HP;
				last[ 1 ] = hunger();
				return traded;
			});
		}));
		all.add( fight( "acurse.Multiplicity", Multiplicity.class, 0, PLAIN, "multiplicity never made a copy beside the hero", () -> {
			Set< Mob > before = new HashSet<>( Dungeon.level.mobs );
			return latch( () -> {
				for (Mob mob : Dungeon.level.mobs){
					if (!before.contains( mob ) && (mob instanceof MirrorImage || mob instanceof Rat)
							&& Dungeon.level.adjacent( mob.pos, Dungeon.hero.pos )) return true;
				}
				return false;
			});
		}).seeds( "SCN-0" ));
		all.add( fight( "acurse.Overgrowth", Overgrowth.class, 0, PLAIN, "overgrowth never set off a fadeleaf that teleported the waiting hero", () -> {
			int cell = Dungeon.hero.pos;
			return latch( () -> Dungeon.hero.pos != cell );
		}).seeds( "SCN-0" ));
		all.add( fight( "acurse.Stench", Stench.class, 0, PLAIN, "stench never released a toxic gas that hurt the rat", () -> {
			Mob rat = rat();
			int hp = rat.HP;
			return () -> PotionScenarios.volume( ToxicGas.class ) > 0 && rat.HP < hp;
		}));
		return all;
	}

	private static Mob rat(){
		return new World().find( Rat.class );
	}

	private static BooleanSupplier latch( BooleanSupplier once ){
		boolean[] seen = { false };
		return () -> seen[ 0 ] |= once.getAsBoolean();
	}

	private static float charge( Wand wand ){
		return wand.curCharges + wand.partialCharge;
	}

	private static int hunger(){
		return Dungeon.hero.buff( Hunger.class ).hunger();
	}
}
