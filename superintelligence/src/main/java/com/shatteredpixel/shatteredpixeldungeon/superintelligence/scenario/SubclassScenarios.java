package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Barkskin;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Berserk;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Combo;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MonkEnergy;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Momentum;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Ooze;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Preparation;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Roots;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.SnipersMark;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.SoulMark;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.ClericSpell;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.GuidingLight;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Radiance;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Smite;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.KindOfWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.TengusMask;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfCorrosion;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.SpiritBow;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Dagger;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MeleeWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Shortsword;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingKnife;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.Earthroot;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;
import com.shatteredpixel.shatteredpixeldungeon.ui.ActionIndicator;

import java.util.ArrayList;
import java.util.List;

final class SubclassScenarios {

	private SubclassScenarios(){}

	private static final int TOME_LEVEL = 7;

	private static final int TOME_CHARGE = 7;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();

		all.add( new Scenario( "subclass.BERSERKER", "berserker rage fills and shields on the indicator", HeroClass.WARRIOR,
				( world, kit ) -> {
					kit.sturdy().level( 10 );
					kit.give( new TengusMask() );
					kit.buff( Berserk.class ).damage( 100000 );
				},
				d -> {
					choose( d, 0, HeroSubClass.BERSERKER );
					int before = Dungeon.hero.shielding();
					d.indicator();
					d.expect( Dungeon.hero.shielding() > before, "the berserk indicator raised no shield" );
				} ).covers( Berserk.class ).covers( "subclass:BERSERKER" ) );

		all.add( new Scenario( "subclass.GLADIATOR", "gladiator combo builds and its finisher spends it", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().level( 10 ).strength( 20 );
					kit.give( new TengusMask() );
					Rat rat = world.hunting( new Rat(), world.adjacentFree() );
					rat.HP = rat.HT = 1000;
					rat.defenseSkill = 0;
				},
				d -> {
					choose( d, 1, HeroSubClass.GLADIATOR );
					Rat rat = d.world().find( Rat.class );
					boolean built = d.until( () -> {
						Combo combo = Dungeon.hero.buff( Combo.class );
						return combo != null && combo.getComboCount() >= 2;
					}, 6, () -> d.move( rat.pos ));
					d.require( built, "the combo built no count in six hits" );
					Combo combo = Dungeon.hero.buff( Combo.class );
					int count = combo.getComboCount();
					d.indicator();
					d.require( d.env().mode() == EnvMode.MENU && WindowBridge.describe().startsWith( "WndCombo(" ),
							"the combo indicator opened " + d.env().mode() + " " + WindowBridge.describe() );
					d.menu( 0 );
					d.require( d.env().mode() == EnvMode.TARGETING, "the clobber asked for no target: " + d.env().mode() );
					d.aimNearest();
					d.waitFor( () -> combo.getComboCount() != count, 6 );
					d.expect( combo.getComboCount() != count, "the clobber finisher spent no combo" );
				} ).covers( Combo.class ).covers( "subclass:GLADIATOR" ) );

		all.add( new Scenario( "subclass.BATTLEMAGE", "battlemage staff melee procs its wand", HeroClass.MAGE,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().level( 10 ).strength( 20 );
					kit.give( new TengusMask() );
					WandOfCorrosion wand = new WandOfCorrosion();
					wand.upgrade( 5 );
					wand.identify();
					kit.hero().belongings.weapon = (MagesStaff) new MagesStaff( wand ).identify();
					int cell = world.adjacentFree();
					world.terrain( cell, Terrain.EMPTY );
					Rat rat = world.hunting( new Rat(), cell );
					rat.HP = rat.HT = 2000;
					rat.defenseSkill = 0;
					Buff.affect( rat, Roots.class, 1000f );
				},
				d -> {
					choose( d, 0, HeroSubClass.BATTLEMAGE );
					Rat rat = d.world().find( Rat.class );
					int before = rat.HP;
					boolean procced = d.until( () -> rat.buff( Ooze.class ) != null, 6, () -> d.move( rat.pos ));
					d.expect( rat.HP < before, "the staff strike hurt no rat" );
					d.expect( procced, "the staff proc put no corrosion on the rat" );
				} ).covers( "subclass:BATTLEMAGE" ) );

		all.add( new Scenario( "subclass.WARLOCK", "warlock zap marks and a hit heals the hero", HeroClass.MAGE,
				( world, kit ) -> {
					world.clearMobs();
					kit.health( 500, 1000 ).level( 10 );
					kit.give( new TengusMask() );
					WandOfMagicMissile wand = new WandOfMagicMissile();
					wand.upgrade( 5 );
					wand.curCharges = 20;
					kit.give( wand );
					Rat rat = world.mob( new Rat(), world.cellAtRange( 3, true ));
					rat.HP = rat.HT = 1000;
					rat.defenseSkill = 0;
					Buff.affect( rat, Roots.class, 1000f );
				},
				d -> {
					choose( d, 1, HeroSubClass.WARLOCK );
					Rat rat = d.world().find( Rat.class );
					boolean marked = d.until( () -> rat.buff( SoulMark.class ) != null, 8, () -> {
						d.use( WandOfMagicMissile.class );
						d.aimNearest();
					} );
					d.expect( marked, "eight zaps marked no rat" );
					if (!marked) return;
					d.walkTo( rat.pos, 4 );
					int hp = Dungeon.hero.HP;
					d.move( rat.pos );
					d.expect( Dungeon.hero.HP > hp, "hitting the marked rat healed no hero" );
				} ).covers( SoulMark.class ).covers( "subclass:WARLOCK" ) );

		all.add( new Scenario( "subclass.ASSASSIN", "assassin blinks to the preparation target", HeroClass.ROGUE,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().level( 10 ).strength( 20 );
					kit.give( new TengusMask() );
					kit.hero().invisible = 20;
					Preparation preparation = kit.buff( Preparation.class );
					TalentFamily.prime( preparation, "turnsInvis", 9 );
					ActionIndicator.setAction( preparation );
					Rat rat = world.mob( new Rat(), world.cellAtRange( 3, true ));
					rat.HP = rat.HT = 1;
					rat.defenseSkill = 0;
					Buff.affect( rat, Roots.class, 1000f );
				},
				d -> {
					choose( d, 0, HeroSubClass.ASSASSIN );
					Rat rat = d.world().find( Rat.class );
					Preparation preparation = Dungeon.hero.buff( Preparation.class );
					d.require( preparation != null, "choosing assassin while invisible attached no preparation" );
					d.indicator();
					d.require( d.env().mode() == EnvMode.TARGETING, "the preparation indicator opened " + d.env().mode() );
					int hp = rat.HP;
					d.aimNearest();
					d.waitFor( () -> !rat.isAlive() || rat.HP < hp, 6 );
					d.expect( !rat.isAlive() || rat.HP < hp, "the blink-strike hurt no rat" );
				} ).covers( Preparation.class ).covers( "subclass:ASSASSIN" ) );

		all.add( new Scenario( "subclass.FREERUNNER", "freerunner momentum freerun doubles speed", HeroClass.ROGUE,
				( world, kit ) -> {
					kit.sturdy().level( 10 );
					kit.give( new TengusMask() );
					kit.buff( Momentum.class );
				},
				d -> {
					choose( d, 1, HeroSubClass.FREERUNNER );
					Momentum momentum = Dungeon.hero.buff( Momentum.class );
					d.require( momentum != null, "no momentum on the freerunner" );
					d.move( d.world().adjacentFree() );
					d.require( !momentum.freerunning(), "the freerunner was already freerunning" );
					float before = momentum.speedMultiplier();
					d.indicator();
					d.expect( momentum.freerunning() && momentum.speedMultiplier() > before,
							"the freerun indicator multiplied no speed" );
				} ).covers( Momentum.class ).covers( "subclass:FREERUNNER" ) );

		all.add( new Scenario( "subclass.SNIPER", "sniper marks with a missile and the special shot hits", HeroClass.HUNTRESS,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().level( 10 ).strength( 20 );
					kit.give( new TengusMask() );
					kit.hero().belongings.weapon = (ThrowingKnife) new ThrowingKnife().identify();
					kit.give( new SpiritBow() );
					Rat rat = world.hunting( new Rat(), world.adjacentFree() );
					rat.HP = rat.HT = 2000;
					rat.defenseSkill = 0;
					Buff.affect( rat, Roots.class, 1000f );
				},
				d -> {
					choose( d, 0, HeroSubClass.SNIPER );
					Rat rat = d.world().find( Rat.class );
					d.move( rat.pos );
					d.require( d.until( () -> Dungeon.hero.buff( SnipersMark.class ) != null, 3, () -> d.act( Action.WAIT )),
							"the missile hit marked no rat" );
					SnipersMark mark = Dungeon.hero.buff( SnipersMark.class );
					d.require( mark.object == rat.id(), "the mark named no rat" );
					int hp = rat.HP;
					d.indicator();
					d.waitFor( () -> !rat.isAlive() || rat.HP < hp, 6 );
					d.expect( !rat.isAlive() || rat.HP < hp, "the special shot hurt no rat" );
				} ).covers( SnipersMark.class ).covers( "subclass:SNIPER" ) );

		all.add( new Scenario( "subclass.WARDEN", "warden earthroot grass grants barkskin", HeroClass.HUNTRESS,
				( world, kit ) -> {
					kit.sturdy().level( 10 );
					kit.give( new TengusMask() );
					kit.give( new Earthroot.Seed() );
				},
				d -> {
					choose( d, 1, HeroSubClass.WARDEN );
					int cell = d.world().adjacentFreeStraight();
					d.throwItem( Earthroot.Seed.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the seed asked for no target: " + d.env().mode() );
					d.step( Action.USE, d.direction( cell ));
					d.require( Dungeon.level.plants.get( cell ) != null, "no plant grew at " + cell );
					Barkskin before = Dungeon.hero.buff( Barkskin.class );
					d.move( cell );
					d.expect( Dungeon.hero.buff( Barkskin.class ) != before, "the earthroot granted no barkskin" );
				} ).covers( "subclass:WARDEN" ) );

		all.add( new Scenario( "subclass.CHAMPION", "champion swaps to the second weapon", HeroClass.DUELIST,
				( world, kit ) -> {
					kit.sturdy().level( 10 );
					kit.give( new TengusMask() );
					kit.hero().belongings.weapon = (Shortsword) new Shortsword().identify();
					kit.hero().belongings.secondWep = (Dagger) new Dagger().identify();
					Buff.affect( kit.hero(), MeleeWeapon.Charger.class );
				},
				d -> {
					choose( d, 0, HeroSubClass.CHAMPION );
					d.require( d.until( () -> ActionIndicator.action != null, 3, () -> d.act( Action.WAIT )),
							"the champion raised no weapon swap indicator" );
					KindOfWeapon before = Dungeon.hero.belongings.weapon;
					d.indicator();
					d.expect( Dungeon.hero.belongings.weapon instanceof Dagger, "the swap did not wear the second weapon" );
					d.expect( Dungeon.hero.belongings.secondWep == before, "the swap kept no weapon in reserve" );
				} ).covers( "subclass:CHAMPION" ) );

		all.add( new Scenario( "subclass.MONK", "monk focus spends energy", HeroClass.DUELIST,
				( world, kit ) -> {
					kit.sturdy().level( 10 );
					kit.give( new TengusMask() );
					MonkEnergy energy = kit.buff( MonkEnergy.class );
					energy.energy = 5;
					ActionIndicator.setAction( energy );
				},
				d -> {
					choose( d, 1, HeroSubClass.MONK );
					MonkEnergy energy = Dungeon.hero.buff( MonkEnergy.class );
					d.require( energy != null, "no monk energy" );
					float before = energy.energy;
					d.indicator();
					d.require( d.env().mode() == EnvMode.MENU && WindowBridge.describe().startsWith( "WndMonkAbilities(" ),
							"the monk indicator opened " + d.env().mode() + " " + WindowBridge.describe() );
					d.menu( 1 );
					d.expect( energy.energy < before, "focus spent no energy" );
					d.expect( Dungeon.hero.buff( MonkEnergy.MonkAbility.Focus.FocusBuff.class ) != null,
							"focus granted nothing" );
				} ).covers( MonkEnergy.class ).covers( "subclass:MONK" ) );

		all.add( new Scenario( "subclass.PRIEST", "priest radiance lights and stuns the room", HeroClass.CLERIC,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().level( 10 );
					kit.give( new TengusMask() );
					HolyTome tome = tome();
					tome.upgrade( TOME_LEVEL );
					tome.directCharge( TOME_CHARGE );
					Rat rat = world.mob( new Rat(), world.cellAtRange( 3, true ));
					rat.HP = rat.HT = 100;
					Buff.affect( rat, Roots.class, 1000f );
				},
				d -> {
					choose( d, 0, HeroSubClass.PRIEST );
					Rat rat = d.world().find( Rat.class );
					d.use( HolyTome.class );
					d.require( d.env().mode() == EnvMode.MENU && WindowBridge.describe().startsWith( "WndClericSpells(" ),
							"the tome opened " + d.env().mode() + " " + WindowBridge.describe() );
					d.menu( spellOption( Radiance.class ));
					d.expect( rat.buff( GuidingLight.Illuminated.class ) != null, "the radiance lit no rat" );
					d.expect( rat.buff( Paralysis.class ) != null, "the radiance stunned no rat" );
				} ).covers( "subclass:PRIEST" ) );

		all.add( new Scenario( "subclass.PALADIN", "paladin smite strikes the adjacent rat", HeroClass.CLERIC,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().level( 10 );
					kit.give( new TengusMask() );
					HolyTome tome = tome();
					tome.upgrade( TOME_LEVEL );
					tome.directCharge( TOME_CHARGE );
					Rat rat = world.mob( new Rat(), world.adjacentFree() );
					rat.HP = rat.HT = 100;
					rat.defenseSkill = 0;
					Buff.affect( rat, Roots.class, 1000f );
				},
				d -> {
					choose( d, 1, HeroSubClass.PALADIN );
					Rat rat = d.world().find( Rat.class );
					int hp = rat.HP;
					d.use( HolyTome.class );
					d.require( d.env().mode() == EnvMode.MENU && WindowBridge.describe().startsWith( "WndClericSpells(" ),
							"the tome opened " + d.env().mode() + " " + WindowBridge.describe() );
					d.menu( spellOption( Smite.class ));
					d.require( d.env().mode() == EnvMode.TARGETING, "the smite asked for no target: " + d.env().mode() );
					d.aimNearest();
					d.waitFor( () -> !rat.isAlive() || rat.HP < hp, 6 );
					d.expect( !rat.isAlive() || rat.HP < hp, "the smite hurt no rat" );
				} ).covers( "subclass:PALADIN" ) );

		return all;
	}

	private static void choose( Driver d, int index, HeroSubClass expected ){
		HeroSubClass before = Dungeon.hero.subClass;
		d.use( TengusMask.class );
		d.require( d.env().mode() == EnvMode.MENU, "wearing the mask opened " + d.env().mode() );
		d.menu( index );
		d.require( d.env().mode() == EnvMode.MENU, "picking a subclass opened no confirm: " + d.env().mode() );
		d.menu( 0 );
		d.require( d.env().mode() == EnvMode.WORLD, "the choice left the env in " + d.env().mode() );
		d.expect( Dungeon.hero.subClass != before, "the mask changed no subclass" );
		d.require( Dungeon.hero.subClass == expected, "the mask chose " + Dungeon.hero.subClass + " not " + expected );
	}

	private static HolyTome tome(){
		return (HolyTome) Dungeon.hero.belongings.artifact;
	}

	private static int spellOption( Class< ? extends ClericSpell > type ){
		int option = 1;
		for (int tier = 1; tier <= Talent.MAX_TALENT_TIERS; tier++){
			for (ClericSpell spell : ClericSpell.getSpellList( Dungeon.hero, tier )){
				if (type.isInstance( spell )) return option;
				option++;
			}
		}
		throw new Driver.Stop( type.getSimpleName() + " is not in the tome's spell list" );
	}
}
