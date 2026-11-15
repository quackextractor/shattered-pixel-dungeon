package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Burning;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invisibility;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DM100;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClothArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.LeatherArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.MailArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.PlateArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ScaleArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.AntiMagic;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.Brimstone;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.Camouflage;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.Obfuscation;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.glyphs.Stone;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class ArmourScenarios {

	private ArmourScenarios(){}

	static final int GLYPH_LEVEL = 30;

	static final int FOE_HP = 1000;

	static final int FIGHT_TURNS = 20;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		for (Class< ? extends Armor > type : List.of( MailArmor.class, ScaleArmor.class, PlateArmor.class )) all.add( tier( type ));
		all.add( antiMagic() );
		all.add( brimstone() );
		all.add( camouflage() );
		all.add( obfuscation() );
		all.add( stone() );
		all.addAll( GlyphFights.all() );
		return all;
	}

	private static Scenario tier( Class< ? extends Armor > type ){
		return new Scenario( "armor." + type.getSimpleName(), "wear " + type.getSimpleName(), HeroClass.ROGUE,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength( 18 );
					kit.give( Reflection.newInstance( type ));
				},
				d -> {
					float dr = PotionScenarios.meanDr();
					d.use( type );
					Armor worn = Dungeon.hero.belongings.armor();
					d.require( type.isInstance( worn ), "USE did not put on the " + type.getSimpleName() );
					int cloth = new ClothArmor().DRMax( worn.buffedLvl() );
					float after = PotionScenarios.meanDr();
					d.expect( worn.DRMax() > cloth && after > dr + 0.5f, "the " + type.getSimpleName() + " gave DRMax " + worn.DRMax()
							+ " against cloth " + cloth + " and mean damage reduction " + after + " against " + dr + " before" );
				} ).covers( type );
	}

	static Scenario worn( String id, Class< ? extends Armor.Glyph > glyph, int level, Scenario.Setup arena, Scenario.Script script ){
		return new Scenario( id, "wear " + glyph.getSimpleName() + " armour", HeroClass.ROGUE,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.strength( 18 );
					LeatherArmor armor = new LeatherArmor();
					armor.level( level );
					armor.inscribe( Reflection.newInstance( glyph ));
					armor.cursed = armor.glyph.curse();
					kit.give( armor );
					arena.build( world, kit );
				}, script ).covers( glyph );
	}

	static void wear( Driver d, Class< ? extends Armor.Glyph > glyph ){
		d.use( LeatherArmor.class );
		Armor worn = Dungeon.hero.belongings.armor();
		d.require( worn instanceof LeatherArmor && glyph.isInstance( worn.glyph ), "USE did not put on the " + glyph.getSimpleName() + " armour" );
	}

	static Rat foe( World world ){
		Rat rat = world.hunting( new Rat(), world.adjacentFreeWithRoomBehind( 2 ));
		rat.HP = rat.HT = FOE_HP;
		return rat;
	}

	static Scenario fight( String id, Class< ? extends Armor.Glyph > glyph, int level, Scenario.Setup arena, String failure,
			Supplier< BooleanSupplier > arm ){
		return worn( id, glyph, level, ( world, kit ) -> {
			arena.build( world, kit );
			foe( world );
		}, d -> {
			BooleanSupplier holds = arm.get();
			d.require( !holds.getAsBoolean(), "the " + glyph.getSimpleName() + " effect held before the armour was worn" );
			wear( d, glyph );
			d.expect( d.until( holds, FIGHT_TURNS, () -> d.act( Action.WAIT )), failure );
		});
	}

	static Scenario stride( String id, Class< ? extends Armor.Glyph > glyph, int level, int terrain, boolean slower, String failure ){
		return worn( id, glyph, level, ( world, kit ) -> {
			if (terrain >= 0) world.terrain( world.adjacentFreeStraight(), terrain );
		}, d -> {
			int home = Dungeon.hero.pos;
			int away = terrain >= 0 ? d.world().beside( terrain ) : d.world().adjacentFreeStraight();
			float base = PotionScenarios.cost( () -> d.move( away ));
			d.move( home );
			wear( d, glyph );
			d.act( Action.WAIT );
			float now = PotionScenarios.cost( () -> d.move( away ));
			d.expect( slower ? now > base + 0.5f : now < base - 0.1f, failure + ": a step cost " + now + " turns against " + base + " before" );
		});
	}

	private static Scenario antiMagic(){
		return worn( "glyph.AntiMagic", AntiMagic.class, GLYPH_LEVEL,
				( world, kit ) -> world.hunting( new DM100(), world.cellAtRange( 3, true )),
				d -> {
					Hero hero = Dungeon.hero;
					int hp = hero.HP;
					d.require( d.until( () -> hero.HP < hp, 12, () -> d.act( Action.WAIT )), "the DM-100 never hurt the hero before the armour" );
					float resisted = magicResisted();
					wear( d, AntiMagic.class );
					int worn = hero.HP;
					for (int i = 0; i < 12 && d.env().running(); i++) d.act( Action.WAIT );
					float now = magicResisted();
					d.expect( hero.HP >= worn && now > resisted + 10f, "the DM-100 took the hero from " + worn + " to " + hero.HP
							+ " HP with a mean lightning-bolt reduction of " + now + " against " + resisted + " before" );
				} );
	}

	private static float magicResisted(){
		return AntiMagic.RESISTS.contains( DM100.LightningBolt.class )
				? PotionScenarios.mean( () -> AntiMagic.drRoll( Dungeon.hero, Dungeon.hero.glyphLevel( AntiMagic.class ))) : 0f;
	}

	private static Scenario brimstone(){
		return worn( "glyph.Brimstone", Brimstone.class, GLYPH_LEVEL,
				( world, kit ) -> Buff.affect( kit.hero(), Burning.class ).reignite( kit.hero(), 30f ),
				d -> {
					Hero hero = Dungeon.hero;
					int hp = hero.HP;
					d.require( d.until( () -> hero.HP < hp, 3, () -> d.act( Action.WAIT )) && hero.buff( Burning.class ) != null,
							"the fire did not hurt the hero before the armour" );
					wear( d, Brimstone.class );
					int worn = hero.HP;
					for (int i = 0; i < 3 && d.env().running(); i++) d.act( Action.WAIT );
					d.expect( hero.HP >= worn && hero.buff( Burning.class ) == null, "the hero burned from " + worn + " to " + hero.HP
							+ " HP under brimstone" );
				} );
	}

	private static Scenario camouflage(){
		return worn( "glyph.Camouflage", Camouflage.class, GLYPH_LEVEL,
				( world, kit ) -> {
					int width = world.level().width();
					int grass = 0;
					for (int offset : new int[]{ -width, 1, width, -1 }){
						if (grass < 2 && world.free( world.hero() + offset )){
							world.terrain( world.hero() + offset, Terrain.HIGH_GRASS );
							grass++;
						}
					}
					if (grass < 2) throw new NoFixture( "no two free cells straight beside the hero" );
				},
				d -> {
					Hero hero = Dungeon.hero;
					int home = hero.pos;
					d.move( d.world().beside( Terrain.HIGH_GRASS ));
					d.require( hero.buff( Invisibility.class ) == null, "the grass hid the hero before the armour" );
					d.move( home );
					wear( d, Camouflage.class );
					d.move( d.world().beside( Terrain.HIGH_GRASS ));
					d.expect( hero.buff( Invisibility.class ) != null && hero.invisible > 0, "trampling grass under camouflage left the hero visible" );
				} );
	}

	private static Scenario obfuscation(){
		return worn( "glyph.Obfuscation", Obfuscation.class, GLYPH_LEVEL, ( world, kit ) -> {}, d -> {
			float stealth = Dungeon.hero.stealth();
			wear( d, Obfuscation.class );
			d.expect( Dungeon.hero.stealth() > stealth + 0.5f, "obfuscation left the hero's stealth at " + Dungeon.hero.stealth() + " from " + stealth );
		} );
	}

	private static Scenario stone(){
		return worn( "glyph.Stone", Stone.class, GLYPH_LEVEL, ( world, kit ) -> foe( world ), d -> {
			Hero hero = Dungeon.hero;
			Rat rat = d.world().find( Rat.class );
			int evasion = hero.defenseSkill( rat );
			float taken = PotionScenarios.mean( () -> hero.belongings.armor().proc( rat, hero, 100 ));
			d.require( evasion > 0 && taken >= 100f, "the hero had no evasion or a reduced hit before the armour" );
			wear( d, Stone.class );
			float now = PotionScenarios.mean( () -> hero.belongings.armor().proc( rat, hero, 100 ));
			d.expect( hero.defenseSkill( rat ) == 0 && now < taken, "stone left evasion " + hero.defenseSkill( rat ) + " and a 100 hit at "
					+ now + " against " + taken + " before" );
		} );
	}
}
