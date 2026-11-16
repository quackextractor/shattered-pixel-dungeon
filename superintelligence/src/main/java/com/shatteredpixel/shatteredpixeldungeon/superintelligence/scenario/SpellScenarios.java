package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ArtifactRecharge;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Recharging;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Elemental;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.LiquidMetal;
import com.shatteredpixel.shatteredpixeldungeon.items.Recipe;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfMagicMapping;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfPassage;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRecharging;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRemoveCurse;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTransmutation;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade;
import com.shatteredpixel.shatteredpixeldungeon.items.spells.Alchemize;
import com.shatteredpixel.shatteredpixeldungeon.items.spells.BeaconOfReturning;
import com.shatteredpixel.shatteredpixeldungeon.items.spells.CurseInfusion;
import com.shatteredpixel.shatteredpixeldungeon.items.spells.MagicalInfusion;
import com.shatteredpixel.shatteredpixeldungeon.items.spells.PhaseShift;
import com.shatteredpixel.shatteredpixeldungeon.items.spells.ReclaimTrap;
import com.shatteredpixel.shatteredpixeldungeon.items.spells.Recycle;
import com.shatteredpixel.shatteredpixeldungeon.items.spells.SummonElemental;
import com.shatteredpixel.shatteredpixeldungeon.items.spells.TelekineticGrab;
import com.shatteredpixel.shatteredpixeldungeon.items.spells.UnstableSpell;
import com.shatteredpixel.shatteredpixeldungeon.items.spells.WildEnergy;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.Runestone;
import com.shatteredpixel.shatteredpixeldungeon.items.stones.StoneOfBlast;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.enchantments.Blazing;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Dagger;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.Embers;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.MetalShard;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.GrippingTrap;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SpellScenarios {

	private SpellScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( alchemize() );
		all.add( beacon() );
		all.add( curseInfusion() );
		all.add( magicalInfusion() );
		all.add( phaseShift() );
		all.add( reclaimTrap() );
		all.add( recycle() );
		all.add( summonElemental() );
		all.add( telekineticGrab() );
		all.add( unstableSpell() );
		all.add( wildEnergy() );
		return all;
	}

	private static Scenario alchemize(){
		return new Scenario( "aspell.Alchemize", "brew alchemize then convert an item to energy", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.emptyPack().energy( 20 );
					kit.give( new Sungrass.Seed() );
					kit.give( new StoneOfBlast() );
					kit.give( new com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify() );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					FoodScenarios.brewed( d, Alchemize.Recipe.class, Alchemize.class, 8 );
					int energy = Dungeon.energy;
					d.use( Alchemize.class );
					d.require( d.env().mode() == EnvMode.INVENTORY, "alchemize opened " + d.env().mode() );
					d.pick( com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify.class );
					d.require( d.env().mode() == EnvMode.MENU, "alchemize item window did not open: " + d.env().mode() );
					d.menu( 1 );
					d.cancel();
					d.expect( Dungeon.energy >= energy + new com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify().energyVal(),
							"alchemizing the item raised energy only to " + Dungeon.energy + " from " + energy );
				} ).covers( Alchemize.class, Alchemize.Recipe.class );
	}

	private static Scenario beacon(){
		return new Scenario( "aspell.BeaconOfReturning", "brew beacon then set it and return", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.emptyPack().energy( 20 );
					kit.give( new ScrollOfPassage() );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					FoodScenarios.brewed( d, BeaconOfReturning.Recipe.class, BeaconOfReturning.class, 5 );
					int origin = Dungeon.hero.pos;
					d.use( BeaconOfReturning.class );
					d.expect( Dungeon.hero.buff( BeaconOfReturning.BeaconTracker.class ) != null, "casting the beacon did not set it" );
					int away = d.world().adjacentFreeStraight();
					d.move( away );
					d.use( BeaconOfReturning.class );
					d.require( d.env().mode() == EnvMode.MENU, "the beacon opened " + d.env().mode() );
					d.menu( 1 );
					d.expect( Dungeon.hero.pos == origin, "returning left the hero on " + Dungeon.hero.pos + " instead of " + origin );
				} ).covers( BeaconOfReturning.class, BeaconOfReturning.Recipe.class );
	}

	private static Scenario curseInfusion(){
		return new Scenario( "aspell.CurseInfusion", "brew curse infusion then curse a weapon", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.emptyPack().energy( 20 );
					kit.give( new ScrollOfRemoveCurse() );
					kit.give( new MetalShard() );
					kit.give( new Dagger() );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					FoodScenarios.brewed( d, CurseInfusion.Recipe.class, CurseInfusion.class, 4 );
					Dagger dagger = Dungeon.hero.belongings.getItem( Dagger.class );
					d.use( CurseInfusion.class );
					d.require( d.env().mode() == EnvMode.INVENTORY, "curse infusion opened " + d.env().mode() );
					d.pick( Dagger.class );
					d.expect( dagger.cursed && dagger.curseInfusionBonus, "the weapon was not cursed by the infusion" );
				} ).covers( CurseInfusion.class, CurseInfusion.Recipe.class );
	}

	private static Scenario magicalInfusion(){
		return new Scenario( "aspell.MagicalInfusion", "brew magical infusion then upgrade a weapon keeping its enchant", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.emptyPack().energy( 20 );
					kit.give( new ScrollOfUpgrade() );
					Dagger dagger = new Dagger();
					dagger.enchant( new Blazing() );
					kit.give( dagger );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					FoodScenarios.brewed( d, MagicalInfusion.Recipe.class, MagicalInfusion.class, 1 );
					Dagger dagger = Dungeon.hero.belongings.getItem( Dagger.class );
					int level = dagger.level();
					Class< ? > enchant = dagger.enchantment.getClass();
					d.use( MagicalInfusion.class );
					d.require( d.env().mode() == EnvMode.INVENTORY, "magical infusion opened " + d.env().mode() );
					d.pick( Dagger.class );
					d.require( d.env().mode() == EnvMode.MENU, "the upgrade window did not open: " + d.env().mode() );
					d.menu( 0 );
					d.expect( dagger.level() > level && dagger.enchantment != null && dagger.enchantment.getClass() == enchant,
							"the infusion did not upgrade the weapon or keep its enchant" );
				} ).covers( MagicalInfusion.class, MagicalInfusion.Recipe.class );
	}

	private static Scenario phaseShift(){
		return new Scenario( "aspell.PhaseShift", "brew phase shift then teleport a rat", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.emptyPack().energy( 20 );
					kit.give( new com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation() );
					PotionScenarios.target( world, 100 );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					FoodScenarios.brewed( d, PhaseShift.Recipe.class, PhaseShift.class, 6 );
					Rat rat = (Rat) PotionScenarios.rat();
					int pos = rat.pos;
					d.use( PhaseShift.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "phase shift opened " + d.env().mode() );
					d.aimNearest();
					d.expect( rat.pos != pos || rat.buff( Paralysis.class ) != null, "the rat was neither teleported nor paralysed" );
				} ).covers( PhaseShift.class, PhaseShift.Recipe.class );
	}

	private static Scenario reclaimTrap(){
		return new Scenario( "aspell.ReclaimTrap", "brew reclaim trap then reclaim and place a trap", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.emptyPack().energy( 20 );
					kit.give( new ScrollOfMagicMapping() );
					kit.give( new MetalShard() );
					int trapCell = world.adjacentFreeStraight();
					world.trap( new GrippingTrap(), trapCell, Terrain.EMPTY );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					FoodScenarios.brewed( d, ReclaimTrap.Recipe.class, ReclaimTrap.class, 5 );
					int trapCell = -1;
					for (int offset : com.watabou.utils.PathFinder.NEIGHBOURS8){
						int cell = Dungeon.hero.pos + offset;
						if (Dungeon.level.traps.get( cell ) != null){ trapCell = cell; break; }
					}
					d.use( ReclaimTrap.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "reclaim trap opened " + d.env().mode() );
					d.step( Action.USE, d.direction( trapCell ));
					d.require( Dungeon.hero.buff( ReclaimTrap.ReclaimedTrap.class ) != null, "the trap was not reclaimed" );
					int placeCell = d.world().adjacentFreeStraight();
					d.use( ReclaimTrap.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "reclaim trap opened " + d.env().mode() );
					d.step( Action.USE, d.direction( placeCell ));
					d.act( Action.WAIT );
					d.expect( Dungeon.hero.buff( ReclaimTrap.ReclaimedTrap.class ) == null, "the reclaimed trap was not placed" );
				} ).covers( ReclaimTrap.class, ReclaimTrap.Recipe.class );
	}

	private static Scenario recycle(){
		return new Scenario( "aspell.Recycle", "brew recycle then transform a potion", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.emptyPack().energy( 20 );
					kit.give( new ScrollOfTransmutation() );
					kit.give( new StoneOfBlast() );
					kit.give( new PotionOfHealing() );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					FoodScenarios.brewed( d, Recycle.Recipe.class, Recycle.class, 12 );
					d.use( Recycle.class );
					d.require( d.env().mode() == EnvMode.INVENTORY, "recycle opened " + d.env().mode() );
					d.pick( PotionOfHealing.class );
					d.expect( Dungeon.hero.belongings.getItem( PotionOfHealing.class ) == null, "the potion was not recycled" );
				} ).covers( Recycle.class, Recycle.Recipe.class );
	}

	private static Scenario summonElemental(){
		return new Scenario( "aspell.SummonElemental", "brew summon elemental then summon an ally that fights a rat", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.emptyPack().energy( 20 );
					kit.give( new Embers() );
					PotionScenarios.target( world, 100 );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					FoodScenarios.brewed( d, SummonElemental.Recipe.class, SummonElemental.class, 6 );
					Rat rat = (Rat) PotionScenarios.rat();
					int hp = rat.HP;
					d.use( SummonElemental.class );
					d.expect( new World().find( Elemental.AllyNewBornElemental.class ) != null, "no newborn elemental was summoned" );
					d.expect( d.until( () -> rat.HP < hp, 8, () -> d.act( Action.WAIT )), "the elemental never hurt the rat" );
				} ).covers( SummonElemental.class, SummonElemental.Recipe.class, Elemental.AllyNewBornElemental.class );
	}

	private static Scenario telekineticGrab(){
		return new Scenario( "aspell.TelekineticGrab", "brew telekinetic grab then pull a heap into the pack", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.emptyPack().energy( 20 );
					kit.give( new LiquidMetal().quantity( 10 ));
					world.drop( new Gold( 5 ), world.adjacentFreeStraight() );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					FoodScenarios.brewed( d, TelekineticGrab.Recipe.class, TelekineticGrab.class, 8 );
					int cell = -1;
					for (int offset : com.watabou.utils.PathFinder.NEIGHBOURS8){
						int c = Dungeon.hero.pos + offset;
						if (Dungeon.level.heaps.get( c ) != null){ cell = c; break; }
					}
					d.require( cell >= 0, "no heap beside the hero" );
					int gold = Dungeon.gold;
					d.use( TelekineticGrab.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "telekinetic grab opened " + d.env().mode() );
					d.step( Action.USE, d.direction( cell ));
					d.expect( Dungeon.level.heaps.get( cell ) == null || Dungeon.level.heaps.get( cell ).isEmpty(),
							"the heap was not grabbed" );
				} ).covers( TelekineticGrab.class, TelekineticGrab.Recipe.class );
	}

	private static Scenario unstableSpell(){
		return new Scenario( "aspell.UnstableSpell", "brew unstable spell then unleash a scroll effect", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.emptyPack().energy( 20 );
					kit.give( new ScrollOfTransmutation() );
					kit.give( new StoneOfBlast() );
					kit.giveUnknown( new Dagger() );
					PotionScenarios.target( world, 100 );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					FoodScenarios.brewed( d, UnstableSpell.Recipe.class, UnstableSpell.class, 1 );
					Set< Class< ? > > buffs = new HashSet<>();
					for (Buff b : Dungeon.hero.buffs()) buffs.add( b.getClass() );
					int mobs = Dungeon.level.mobs.size();
					int pos = Dungeon.hero.pos;
					Item dagger = Dungeon.hero.belongings.getItem( Dagger.class );
					boolean idBefore = dagger != null && dagger.isIdentified();
					d.use( UnstableSpell.class );
					d.expect( Dungeon.hero.buffs().size() != buffs.size()
							|| Dungeon.level.mobs.size() != mobs
							|| Dungeon.hero.pos != pos
							|| (dagger != null && dagger.isIdentified() != idBefore)
							|| PotionScenarios.rat().HP < 100
							|| PotionScenarios.rat().buff( Paralysis.class ) != null,
							"the unstable spell produced no scroll effect" );
				} ).covers( UnstableSpell.class, UnstableSpell.Recipe.class );
	}

	private static Scenario wildEnergy(){
		return new Scenario( "aspell.WildEnergy", "brew wild energy then charge wands and artifacts", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.emptyPack().energy( 20 );
					kit.give( new ScrollOfRecharging() );
					kit.give( new MetalShard() );
					WandOfMagicMissile wand = new WandOfMagicMissile();
					wand.curCharges = 1;
					kit.give( wand );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					FoodScenarios.brewed( d, WildEnergy.Recipe.class, WildEnergy.class, 5 );
					WandOfMagicMissile wand = Dungeon.hero.belongings.getItem( WandOfMagicMissile.class );
					int charges = wand.curCharges;
					d.use( WildEnergy.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "wild energy opened " + d.env().mode() );
					d.step( Action.USE, 1 );
					d.expect( wand.curCharges > charges && Dungeon.hero.buff( Recharging.class ) != null
							&& Dungeon.hero.buff( ArtifactRecharge.class ) != null,
							"wild energy charged neither wands nor artifacts" );
				} ).covers( WildEnergy.class, WildEnergy.Recipe.class );
	}
}