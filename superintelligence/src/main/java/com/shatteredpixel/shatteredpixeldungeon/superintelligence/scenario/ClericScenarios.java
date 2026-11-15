package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bless;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.LifeLink;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Weakness;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.AscendedForm;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.PowerOfMany;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.Trinity;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.AuraOfProtection;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.BeamingRay;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.BlessSpell;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.BodyForm;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Cleanse;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.ClericSpell;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.DivineIntervention;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.DivineSense;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Flash;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.GuidingLight;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.HallowedGround;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.HolyIntuition;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.HolyLance;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.HolyWard;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.HolyWeapon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Judgement;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.LayOnHands;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.LifeLinkSpell;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.MindForm;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.MnemonicPrayer;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Radiance;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.RecallInscription;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.ShieldOfLight;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Smite;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.SpiritForm;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Stasis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Sunray;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.WallOfLight;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.LeatherArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.RingOfMight;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Blazing;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Longsword;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;
import com.watabou.utils.Bundle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class ClericScenarios {

	private ClericScenarios(){}

	private static final int BUTTONS_BEFORE_SPELLS = 1;

	private static final int TOME_UPGRADES = 7;

	private static final int TOME_TOP_UP = 7;

	private static final int WAITS = 3;

	private static final int SENSE_RANGE = 8;

	interface Aim {
		void at( Driver d );
	}

	private static final Aim NONE = d -> {};

	private static final Aim NEAREST = Driver::aimNearest;

	private static final Aim AT_ALLY = d -> d.step( Action.USE, d.direction( ally().pos ));

	private static final Aim BESIDE = d -> d.step( Action.USE, d.direction( d.world().adjacentFree() ));

	private static final Aim FORM = d -> {
		StoneScenarios.answer( d, "WndItemtypeSelect", 0 );
		StoneScenarios.answer( d, "WndItemConfirm", 0 );
	};

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( spell( GuidingLight.class, ClericScenarios::target, NEAREST, "the guiding light left the rat unhurt or unlit", () -> {
			Mob rat = PotionScenarios.rat();
			int hp = rat.HP;
			return () -> rat.HP < hp && rat.buff( GuidingLight.Illuminated.class ) != null;
		}).covers( HolyTome.class ).covers( Coverage.heroClass( HeroClass.CLERIC )));
		all.add( spell( HolyWeapon.class, ( world, kit ) -> {}, NONE, "the holy weapon added no damage to the weapon's hit", () -> {
			int bonus = SpellProbes.weaponBonus();
			return () -> Dungeon.hero.buff( HolyWeapon.HolyWepBuff.class ) != null && SpellProbes.weaponBonus() > bonus;
		}));
		all.add( spell( HolyWard.class, ( world, kit ) -> {}, NONE, "the holy ward made the armour block no more damage", () -> {
			int blocked = SpellProbes.armourBlocks();
			return () -> Dungeon.hero.buff( HolyWard.HolyArmBuff.class ) != null && SpellProbes.armourBlocks() > blocked;
		}));
		all.add( spell( HolyIntuition.class, with( learn( Talent.HOLY_INTUITION ), ( world, kit ) -> kit.giveUnknown( new Longsword() )),
				d -> d.pick( Longsword.class ), "the intuition left the longsword's curse unknown", () -> {
			Item sword = Dungeon.hero.belongings.getItem( Longsword.class );
			return () -> sword.cursedKnown;
		}));
		all.add( spell( ShieldOfLight.class, with( learn( Talent.SHIELD_OF_LIGHT ), ClericScenarios::target ), NEAREST,
				"the shield of light cut no damage from the rat's hits", () -> {
			Mob rat = PotionScenarios.rat();
			int cut = SpellProbes.shieldCut( rat );
			return () -> {
				ShieldOfLight.ShieldOfLightTracker shield = Dungeon.hero.buff( ShieldOfLight.ShieldOfLightTracker.class );
				return shield != null && shield.object == rat.id() && SpellProbes.shieldCut( rat ) > cut;
			};
		}));
		all.add( recall() );
		all.add( spell( Sunray.class, with( learn( Talent.SUNRAY ), ClericScenarios::target ), NEAREST, "the sunray left the rat unhurt or sighted", () -> {
			Mob rat = PotionScenarios.rat();
			int hp = rat.HP;
			return () -> rat.HP < hp && rat.buff( Blindness.class ) != null;
		}));
		all.add( spell( DivineSense.class, with( learn( Talent.DIVINE_SENSE ), ClericScenarios::hidden ), NONE,
				"the divine sense revealed no rat out of sight", () -> {
			Mob rat = PotionScenarios.rat();
			return () -> Dungeon.level.heroFOV[ rat.pos ];
		}));
		all.add( spell( BlessSpell.class, with( learn( Talent.BLESS ), ClericScenarios::ally ), AT_ALLY, "the bless gave the ally no bless or barrier", () -> {
			Mob ally = ally();
			int shield = ally.shielding();
			return () -> ally.buff( Bless.class ) != null && ally.shielding() > shield;
		}));
		all.add( spell( Radiance.class, with( sub( HeroSubClass.PRIEST ), ClericScenarios::target ), NONE,
				"the radiance neither lit nor stunned the rat", () -> {
			Mob rat = PotionScenarios.rat();
			return () -> rat.buff( GuidingLight.Illuminated.class ) != null && rat.buff( Paralysis.class ) != null;
		}));
		all.add( spell( Smite.class, with( sub( HeroSubClass.PALADIN ), ClericScenarios::beside ), NEAREST, "the smite left the rat unhurt",
				ClericScenarios::ratHurt ));
		all.add( spell( Cleanse.class, with( learn( Talent.CLEANSE ), ( world, kit ) -> {
			kit.buff( Poison.class ).set( 50 );
			kit.buff( Weakness.class, 50f );
			kit.buff( Cripple.class, 50f );
		}), NONE, "the cleanse left a negative buff or gave no barrier", () -> {
			int shield = Dungeon.hero.shielding();
			return () -> !PotionScenarios.negative() && Dungeon.hero.shielding() > shield;
		}));
		all.add( spell( HolyLance.class, with( sub( HeroSubClass.PRIEST ), learn( Talent.HOLY_LANCE ),
				( world, kit ) -> PotionScenarios.target( world, 200 )), NEAREST, "the holy lance left the rat unhurt", ClericScenarios::ratHurt ));
		all.add( spell( HallowedGround.class, with( sub( HeroSubClass.PRIEST ), learn( Talent.HALLOWED_GROUND ),
				( world, kit ) -> world.terrain( world.adjacentFree(), Terrain.EMPTY )), BESIDE,
				"the hallowed ground grew no grass or shielded no hero", () -> {
			int grass = grass(), shield = Dungeon.hero.shielding();
			return () -> grass() > grass && Dungeon.hero.shielding() > shield;
		}));
		all.add( spell( MnemonicPrayer.class, with( sub( HeroSubClass.PRIEST ), learn( Talent.MNEMONIC_PRAYER ),
				( world, kit ) -> Buff.affect( PotionScenarios.target( world, 100 ), Cripple.class, 10f )), NEAREST,
				"the prayer extended no cripple on the rat", () -> {
			Cripple cripple = PotionScenarios.rat().buff( Cripple.class );
			float left = cripple.cooldown();
			return () -> cripple.cooldown() > left;
		}));
		all.add( spell( LayOnHands.class, with( sub( HeroSubClass.PALADIN ), learn( Talent.LAY_ON_HANDS ), ClericScenarios::ally,
				( world, kit ) -> ally().HP = 1 ), AT_ALLY, "the laying on of hands healed no ally", () -> {
			Mob ally = ally();
			int hp = ally.HP;
			return () -> ally.HP > hp;
		}));
		all.add( spell( AuraOfProtection.class, with( sub( HeroSubClass.PALADIN ), learn( Talent.AURA_OF_PROTECTION )), NONE,
				"the aura of protection spared a nearby ally no damage", () -> {
			int taken = SpellProbes.auraTaken();
			return () -> Dungeon.hero.buff( AuraOfProtection.AuraBuff.class ) != null && SpellProbes.auraTaken() < taken;
		}));
		all.add( spell( WallOfLight.class, with( sub( HeroSubClass.PALADIN ), learn( Talent.WALL_OF_LIGHT ), ( world, kit ) -> {
			Rat rat = world.mob( new Rat(), world.adjacentFreeWithRoomBehind( 2 ));
			rat.HP = rat.HT = 100;
		}), NEAREST, "the wall of light raised no wall or neither pushed nor held the rat", () -> {
			Mob rat = PotionScenarios.rat();
			int near = Dungeon.level.distance( rat.pos, Dungeon.hero.pos );
			return () -> PotionScenarios.volume( WallOfLight.LightWall.class ) > 0
					&& (Dungeon.level.distance( rat.pos, Dungeon.hero.pos ) > near || rat.buff( Paralysis.class ) != null);
		}));
		all.add( spell( DivineIntervention.class, ascended( Talent.DIVINE_INTERVENTION ), NONE, "the divine intervention shielded no hero",
				ClericScenarios::shielded ));
		all.add( spell( Judgement.class, with( ascended( Talent.JUDGEMENT ), ClericScenarios::target ), NONE, "the judgement left the rat unhurt",
				ClericScenarios::ratHurt ));
		all.add( spell( Flash.class, ascended( Talent.FLASH ), BESIDE, "the flash left the hero in place", () -> {
			int cell = new World().adjacentFree();
			return () -> Dungeon.hero.pos == cell;
		}));
		all.add( form( BodyForm.class, Talent.BODY_FORM, Blazing.class, "body_form" ));
		all.add( form( MindForm.class, Talent.MIND_FORM, WandOfMagicMissile.class, "mind_form" ));
		all.add( form( SpiritForm.class, Talent.SPIRIT_FORM, RingOfMight.class, "spirit_form" ));
		all.add( spell( BeamingRay.class, powered( Talent.BEAMING_RAY ), BESIDE, "the beaming ray moved no ally", () -> {
			Mob ally = ally();
			int cell = new World().adjacentFree();
			return () -> ally.pos == cell && ally.buff( BeamingRay.BeamingRayBoost.class ) != null;
		}));
		all.add( spell( LifeLinkSpell.class, powered( Talent.LIFE_LINK ), NONE, "the life link bound no ally to the hero", () -> {
			Mob ally = ally();
			return () -> {
				LifeLink link = Dungeon.hero.buff( LifeLink.class );
				return link != null && link.object == ally.id() && ally.buff( LifeLinkSpell.LifeLinkSpellBuff.class ) != null;
			};
		}));
		all.add( spell( Stasis.class, powered( Talent.STASIS ), NONE, "the stasis left the ally on the level", () -> {
			Mob ally = ally();
			return () -> !Dungeon.level.mobs.contains( ally ) && Stasis.getStasisAlly() == ally;
		}));
		return all;
	}

	private static Scenario spell( Class< ? extends ClericSpell > type, Scenario.Setup arena, Aim aim, String failure, Supplier< BooleanSupplier > arm ){
		return spell( type, arena, d -> {}, aim, failure, arm );
	}

	private static Scenario spell( Class< ? extends ClericSpell > type, Scenario.Setup arena, Scenario.Script before, Aim aim, String failure,
			Supplier< BooleanSupplier > arm ){
		return new Scenario( "spell." + type.getSimpleName(), "cast " + type.getSimpleName() + " from the holy tome", HeroClass.CLERIC,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					HolyTome tome = tome();
					tome.upgrade( TOME_UPGRADES );
					tome.directCharge( TOME_TOP_UP );
					arena.build( world, kit );
				},
				d -> {
					before.run( d );
					BooleanSupplier holds = arm.get();
					d.require( !holds.getAsBoolean(), "the effect held before " + type.getSimpleName() + " was cast" );
					int charge = charge();
					cast( d, type );
					aim.at( d );
					d.expect( d.until( holds, WAITS, () -> d.act( Action.WAIT )), failure );
					d.expect( charge() < charge, "the tome kept " + charge + " charges after " + type.getSimpleName() );
					d.witness( type );
				} ).covers( type );
	}

	private static void cast( Driver d, Class< ? extends ClericSpell > type ){
		d.use( HolyTome.class );
		d.require( d.env().mode() == EnvMode.MENU && WindowBridge.describe().startsWith( "WndClericSpells(" ),
				"the tome opened " + d.env().mode() + " " + WindowBridge.describe() );
		d.menu( option( type ));
	}

	private static int option( Class< ? extends ClericSpell > type ){
		int option = BUTTONS_BEFORE_SPELLS;
		for (int tier = 1; tier <= Talent.MAX_TALENT_TIERS; tier++){
			for (ClericSpell spell : ClericSpell.getSpellList( Dungeon.hero, tier )){
				if (type.isInstance( spell )) return option;
				option++;
			}
		}
		throw new Driver.Stop( type.getSimpleName() + " is not in the tome's spell list" );
	}

	private static Scenario recall(){
		return spell( RecallInscription.class, with( learn( Talent.RECALL_INSCRIPTION ), ( world, kit ) -> {
			kit.give( new ScrollOfIdentify() );
			kit.giveUnknown( new Longsword() );
			kit.giveUnknown( new LeatherArmor() );
		}), d -> {
			d.use( ScrollOfIdentify.class );
			d.pick( Longsword.class );
			d.require( Dungeon.hero.buff( RecallInscription.UsedItemTracker.class ) != null, "reading the scroll left nothing to recall" );
		}, d -> d.pick( LeatherArmor.class ), "the recalled identify left the leather armour unknown", () -> {
			Item armor = Dungeon.hero.belongings.getItem( LeatherArmor.class );
			return () -> armor.isIdentified();
		});
	}

	private static Scenario form( Class< ? extends ClericSpell > type, Talent talent, Class< ? > discovered, String imbued ){
		return spell( type, ( world, kit ) -> {
			kit.ability( new Trinity() );
			kit.talent( talent, 1 );
			Statistics.itemTypesDiscovered.add( discovered );
		}, FORM, "the " + type.getSimpleName() + " imbued nothing into the trinity", () -> () -> {
			Bundle bundle = new Bundle();
			Dungeon.hero.armorAbility.storeInBundle( bundle );
			return bundle.contains( imbued );
		});
	}

	private static Scenario.Setup with( Scenario.Setup... parts ){
		return ( world, kit ) -> {
			for (Scenario.Setup part : parts) part.build( world, kit );
		};
	}

	private static Scenario.Setup learn( Talent talent ){
		return ( world, kit ) -> kit.talent( talent, 1 );
	}

	private static Scenario.Setup sub( HeroSubClass subClass ){
		return ( world, kit ) -> kit.subclass( subClass );
	}

	private static Scenario.Setup ascended( Talent talent ){
		return ( world, kit ) -> {
			kit.ability( new AscendedForm() );
			kit.talent( talent, 1 );
			kit.buff( AscendedForm.AscendBuff.class ).reset();
		};
	}

	private static Scenario.Setup powered( Talent talent ){
		return with( ( world, kit ) -> kit.ability( new PowerOfMany() ), learn( talent ), ClericScenarios::ally );
	}

	private static void ally( World world, HeroKit kit ){
		PowerOfMany.LightAlly ally = world.mob( new PowerOfMany.LightAlly( kit.hero().lvl ), world.adjacentFree() );
		Buff.affect( ally, PowerOfMany.PowerBuff.class, PowerOfMany.PowerBuff.DURATION );
	}

	private static void target( World world, HeroKit kit ){
		PotionScenarios.target( world, 100 );
	}

	private static void beside( World world, HeroKit kit ){
		Rat rat = world.mob( new Rat(), world.adjacentFree() );
		rat.HP = rat.HT = 100;
	}

	private static void hidden( World world, HeroKit kit ){
		for (int cell = 0; cell < world.level().length(); cell++){
			if (world.free( cell ) && !world.level().heroFOV[ cell ] && world.level().distance( world.hero(), cell ) <= SENSE_RANGE){
				Rat rat = world.mob( new Rat(), cell );
				rat.state = rat.PASSIVE;
				return;
			}
		}
		throw new NoFixture( "no free cell out of the hero's sight" );
	}

	private static BooleanSupplier ratHurt(){
		Mob rat = PotionScenarios.rat();
		int hp = rat.HP;
		return () -> !rat.isAlive() || rat.HP < hp;
	}

	private static BooleanSupplier shielded(){
		int shield = Dungeon.hero.shielding();
		return () -> Dungeon.hero.shielding() > shield;
	}

	private static Mob ally(){
		return new World().find( PowerOfMany.LightAlly.class );
	}

	private static HolyTome tome(){
		return Dungeon.hero.belongings.getItem( HolyTome.class );
	}

	private static int charge(){
		return Integer.parseInt( tome().status().split( "/" )[ 0 ] );
	}

	private static int grass(){
		int grass = 0;
		for (int terrain : Dungeon.level.map){
			if (terrain == Terrain.GRASS || terrain == Terrain.HIGH_GRASS || terrain == Terrain.FURROWED_GRASS) grass++;
		}
		return grass;
	}
}
