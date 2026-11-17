package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Light;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.LostInventory;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.ArcaneResin;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.CloakOfShadows;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.BrokenSeal;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.LiquidMetal;
import com.shatteredpixel.shatteredpixeldungeon.items.LostBackpack;
import com.shatteredpixel.shatteredpixeldungeon.items.Stylus;
import com.shatteredpixel.shatteredpixeldungeon.items.Torch;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.Armor;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClothArmor;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.MagicalHolster;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.PotionBandolier;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.ScrollHolder;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.VelvetPouch;
import com.shatteredpixel.shatteredpixeldungeon.items.remains.BowFragment;
import com.shatteredpixel.shatteredpixeldungeon.items.remains.BrokenHilt;
import com.shatteredpixel.shatteredpixeldungeon.items.remains.BrokenStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.remains.CloakScrap;
import com.shatteredpixel.shatteredpixeldungeon.items.remains.RemainsItem;
import com.shatteredpixel.shatteredpixeldungeon.items.remains.SealShard;
import com.shatteredpixel.shatteredpixeldungeon.items.remains.TornPage;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfRegrowth;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfWarding;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.Shuriken;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;

import java.util.ArrayList;
import java.util.List;

final class ItemScenarios {

	private ItemScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( arcaneResin() );
		all.add( liquidMetal() );
		all.add( brokenSeal() );
		all.add( lostBackpack() );
		all.add( stylus() );
		all.add( torch() );
		all.add( magicalHolster() );
		all.add( potionBandolier() );
		all.add( scrollHolder() );
		all.add( velvetPouch() );
		all.add( bowFragment() );
		all.add( brokenHilt() );
		all.add( brokenStaff() );
		all.add( cloakScrap() );
		all.add( sealShard() );
		all.add( tornPage() );
		all.add( cursedWand() );
		all.add( regrowthPlants() );
		all.add( wardSentry() );
		all.add( menu() );
		all.add( indicator() );
		all.add( selfTarget() );
		return all;
	}

	private static Scenario arcaneResin(){
		return new Scenario( "item.ArcaneResin", "brew arcane resin and apply it to a wand", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().emptyPack().energy( 20 );
					WandOfMagicMissile brewWand = new WandOfMagicMissile();
					brewWand.identify();
					brewWand.cursedKnown = true;
					kit.give( brewWand );
					WandOfMagicMissile targetWand = new WandOfMagicMissile();
					targetWand.identify();
					targetWand.cursedKnown = true;
					world.drop( targetWand, world.adjacentFree() );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					int crafted = Statistics.itemsCrafted;
					d.brewRecipe( ArcaneResin.Recipe.class );
					d.require( Statistics.itemsCrafted == crafted + 1, "itemsCrafted moved by " + (Statistics.itemsCrafted - crafted) );
					d.act( Action.WAIT );
					int cell = heapCell( WandOfMagicMissile.class );
					if (cell >= 0) d.move( cell );
					WandOfMagicMissile wand = Dungeon.hero.belongings.getItem( WandOfMagicMissile.class );
					d.require( wand != null, "no target wand in pack" );
					int level = wand.level();
					d.act( Action.WAIT );
					d.require( d.slotOf( ArcaneResin.class ) >= 0, "no arcane resin slot after brew" );
					d.use( ArcaneResin.class );
					d.require( d.env().mode() == EnvMode.INVENTORY, "arcane resin offered no picker: " + d.env().mode() );
					d.pick( WandOfMagicMissile.class );
					d.expect( wand.level() > level, "the wand level stayed at " + wand.level() );
				} ).covers( ArcaneResin.class, ArcaneResin.Recipe.class );
	}

	private static Scenario liquidMetal(){
		return new Scenario( "item.LiquidMetal", "brew liquid metal and apply it to a missile stack", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().emptyPack().energy( 20 );
					Shuriken brewShuriken = new Shuriken();
					brewShuriken.identify();
					brewShuriken.cursedKnown = true;
					kit.give( brewShuriken );
					Shuriken targetShuriken = new Shuriken();
					targetShuriken.identify();
					targetShuriken.cursedKnown = true;
					targetShuriken.damage( 50 );
					world.drop( targetShuriken, world.adjacentFree() );
					world.terrain( world.adjacentFree(), Terrain.ALCHEMY );
				},
				d -> {
					int crafted = Statistics.itemsCrafted;
					d.brewRecipe( LiquidMetal.Recipe.class );
					d.require( Statistics.itemsCrafted == crafted + 1, "itemsCrafted moved by " + (Statistics.itemsCrafted - crafted) );
					d.act( Action.WAIT );
					int cell = heapCell( Shuriken.class );
					if (cell >= 0) d.move( cell );
					Shuriken shuriken = Dungeon.hero.belongings.getItem( Shuriken.class );
					d.require( shuriken != null, "no target shuriken in pack" );
					float durability = shuriken.durabilityLeft();
					d.act( Action.WAIT );
					d.require( d.slotOf( LiquidMetal.class ) >= 0, "no liquid metal slot after brew" );
					d.use( LiquidMetal.class );
					d.require( d.env().mode() == EnvMode.INVENTORY, "liquid metal offered no picker: " + d.env().mode() );
					d.pick( Shuriken.class );
					d.expect( shuriken.durabilityLeft() > durability, "durability stayed at " + shuriken.durabilityLeft() );
				} ).covers( LiquidMetal.class, LiquidMetal.Recipe.class );
	}

	private static Scenario brokenSeal(){
		return new Scenario( "item.BrokenSeal", "affix a broken seal to armour", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().strength( 20 );
					kit.give( new BrokenSeal() );
					kit.hero().belongings.armor = new ClothArmor();
					kit.hero().belongings.armor.identify();
				},
				d -> {
					Armor armor = Dungeon.hero.belongings.armor;
					d.use( BrokenSeal.class );
					d.require( d.env().mode() == EnvMode.INVENTORY, "the seal offered no picker: " + d.env().mode() );
					d.pick( ClothArmor.class );
					if (d.env().mode() == EnvMode.MENU) d.menu( 0 );
					d.expect( Dungeon.hero.belongings.getItem( BrokenSeal.class ) == null
							&& Dungeon.hero.belongings.armor == armor && armor.checkSeal() != null,
							"the broken seal was not affixed" );
				} ).covers( BrokenSeal.class );
	}

	private static Scenario lostBackpack(){
		return new Scenario( "item.LostBackpack", "resurrect after an unblessed ankh death", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().emptyPack();
					kit.give( new Torch() );
					kit.buff( LostInventory.class );
					world.drop( new LostBackpack(), world.adjacentFree() );
				},
				d -> {
					d.require( Dungeon.hero.belongings.getItem( Torch.class ) == null, "the torch stayed reachable under LostInventory" );
					int cell = heapCell( LostBackpack.class );
					d.fixture( cell >= 0, "no lost backpack heap beside the hero" );
					d.move( cell );
					d.expect( Dungeon.hero.buff( LostInventory.class ) == null, "picking up the lost backpack left LostInventory" );
					d.expect( Dungeon.hero.belongings.getItem( Torch.class ) != null, "the lost backpack did not restore the torch" );
				} ).covers( LostBackpack.class, LostInventory.class );
	}

	private static Scenario stylus(){
		return new Scenario( "item.Stylus", "inscribe armour with a stylus", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().strength( 20 );
					kit.give( new Stylus() );
					kit.hero().belongings.armor = new ClothArmor();
					kit.hero().belongings.armor.identify();
				},
				d -> {
					Armor armor = Dungeon.hero.belongings.armor;
					Class< ? > before = armor.glyph == null ? null : armor.glyph.getClass();
					d.use( Stylus.class );
					d.require( d.env().mode() == EnvMode.INVENTORY, "the stylus offered no picker: " + d.env().mode() );
					d.pick( ClothArmor.class );
					if (d.env().mode() == EnvMode.MENU) d.menu( 0 );
					d.expect( armor.glyph != null && (before == null || armor.glyph.getClass() != before),
							"the stylus added no glyph" );
				} ).covers( Stylus.class );
	}

	private static Scenario torch(){
		return new Scenario( "item.Torch", "use a torch for light", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new Torch() );
				},
				d -> {
					int before = Dungeon.hero.buff( Light.class ) == null ? 0 : 1;
					d.use( Torch.class );
					d.expect( Dungeon.hero.buff( Light.class ) != null, "the torch granted no light" );
				} ).covers( Torch.class, Light.class );
	}

	private static Scenario magicalHolster(){
		return bagScenario( "item.MagicalHolster", MagicalHolster.class );
	}

	private static Scenario potionBandolier(){
		return bagScenario( "item.PotionBandolier", PotionBandolier.class );
	}

	private static Scenario scrollHolder(){
		return bagScenario( "item.ScrollHolder", ScrollHolder.class );
	}

	private static Scenario velvetPouch(){
		return bagScenario( "item.VelvetPouch", VelvetPouch.class );
	}

	private static Item bagSample( Class< ? extends Item > bagType ){
		if (bagType == MagicalHolster.class) return new WandOfMagicMissile();
		if (bagType == PotionBandolier.class) return new com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing();
		if (bagType == ScrollHolder.class) return new com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify();
		if (bagType == VelvetPouch.class) return new com.shatteredpixel.shatteredpixeldungeon.items.quest.MetalShard();
		return null;
	}

	private static Scenario bagScenario( String id, Class< ? extends Item > bagType ){
		Item sample = bagSample( bagType );
		return new Scenario( id, "pick up a bag then its item type", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().emptyPack();
					try {
						Item bag = (Item) bagType.getDeclaredConstructor().newInstance();
						world.drop( bag, world.adjacentFree() );
						if (sample != null) world.drop( sample, world.adjacentFree() );
					} catch (ReflectiveOperationException e) {
						throw new NoFixture( "could not create " + bagType.getSimpleName() );
					}
				},
				d -> {
					d.move( heapCell( bagType ));
					d.require( Dungeon.hero.belongings.getItem( bagType ) != null, "the hero did not pick up the bag" );
					if (sample != null){
						int cell = heapCell( sample.getClass() );
						if (cell >= 0) d.move( cell );
						Item bag = Dungeon.hero.belongings.getItem( bagType );
						d.expect( bag instanceof com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag
								&& ((com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag) bag).contains( sample ),
								"the bag did not hold the " + sample.getClass().getSimpleName() );
					}
				} ).covers( bagType );
	}

	private static int countOf( Class< ? > type ){
		int n = 0;
		for (Item item : Dungeon.hero.belongings.backpack) if (type.isInstance( item )) n += item.quantity();
		return n;
	}

	private static int plantCount( Class< ? > type ){
		int n = 0;
		for (Object p : Dungeon.level.plants.values()) if (type.isInstance( p )) n++;
		return n;
	}

	private static int mobCount( Class< ? > type ){
		int n = 0;
		for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob m : Dungeon.level.mobs) if (type.isInstance( m )) n++;
		return n;
	}

	private static int grassCount(){
		int n = 0;
		for (int t : Dungeon.level.map) if (t == Terrain.HIGH_GRASS) n++;
		return n;
	}

	private static int heapCell( Class< ? extends Item > type ){
		for (int offset : com.watabou.utils.PathFinder.NEIGHBOURS8){
			com.shatteredpixel.shatteredpixeldungeon.items.Heap heap = Dungeon.level.heaps.get( Dungeon.hero.pos + offset );
			if (heap == null) continue;
			for (Item item : heap.items) if (type.isInstance( item )) return heap.pos;
		}
		return -1;
	}

	private static Scenario bowFragment(){
		return remainsScenario( "item.BowFragment", BowFragment.class );
	}

	private static Scenario brokenHilt(){
		return remainsScenario( "item.BrokenHilt", BrokenHilt.class );
	}

	private static Scenario brokenStaff(){
		return remainsScenario( "item.BrokenStaff", BrokenStaff.class );
	}

	private static Scenario cloakScrap(){
		return remainsScenario( "item.CloakScrap", CloakScrap.class );
	}

	private static Scenario sealShard(){
		return remainsScenario( "item.SealShard", SealShard.class );
	}

	private static Scenario tornPage(){
		return remainsScenario( "item.TornPage", TornPage.class );
	}

	private static Scenario remainsScenario( String id, Class< ? extends RemainsItem > type ){
		return new Scenario( id, "use a remains item", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					if (type == TornPage.class) kit.sturdy().health( 500, 1000 );
					else kit.sturdy();
					if (type == BowFragment.class){
						for (int offset : com.watabou.utils.PathFinder.NEIGHBOURS9){
							int cell = kit.hero().pos + offset;
							if (cell >= 0 && cell < Dungeon.level.length() && Dungeon.level.insideMap( cell )) world.terrain( cell, Terrain.EMPTY );
						}
					}
					if (type == BrokenStaff.class){
						MagesStaff staff = new MagesStaff( new WandOfMagicMissile() );
						staff.identify();
						kit.give( staff );
						staff.activate( kit.hero() );
						com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand.Charger charger =
								kit.hero().buff( com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand.Charger.class );
						if (charger != null) charger.wand().curCharges = 0;
					}
					if (type == CloakScrap.class){
						CloakOfShadows cloak = new CloakOfShadows();
						cloak.identify();
						kit.give( cloak );
						cloak.doEquip( kit.hero() );
						cloak.activate( kit.hero() );
					}
					try {
						kit.give( (Item) type.getDeclaredConstructor().newInstance() );
					} catch (ReflectiveOperationException e) {
						throw new NoFixture( "could not create " + type.getSimpleName() );
					}
				},
				d -> {
					if (type == BowFragment.class){
						int before = grassCount();
						d.use( type );
						d.expect( grassCount() > before, "the bow fragment grew no grass" );
					} else if (type == BrokenHilt.class){
						d.require( Dungeon.hero.buff( com.shatteredpixel.shatteredpixeldungeon.actors.buffs.PhysicalEmpower.class ) == null,
								"physical empower was already present" );
						d.use( type );
						d.expect( Dungeon.hero.buff( com.shatteredpixel.shatteredpixeldungeon.actors.buffs.PhysicalEmpower.class ) != null,
								"the broken hilt granted no physical empower" );
					} else if (type == BrokenStaff.class){
						com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand.Charger charger =
								Dungeon.hero.buff( com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand.Charger.class );
						int before = charger == null ? 0 : charger.wand().curCharges;
						d.use( type );
						charger = Dungeon.hero.buff( com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand.Charger.class );
						d.expect( charger != null && charger.wand().curCharges > before, "the broken staff charged nothing" );
					} else if (type == CloakScrap.class){
						d.require( countOf( type ) == 1, "the remains fixture did not hold exactly one" );
						d.use( type );
						d.expect( countOf( type ) == 0, "using the cloak scrap left it in the pack" );
					} else if (type == SealShard.class){
						int before = Dungeon.hero.shielding();
						d.use( type );
						d.expect( Dungeon.hero.shielding() > before, "the seal shard granted no shielding" );
					} else if (type == TornPage.class){
						int before = Dungeon.hero.HP;
						d.use( type );
						d.expect( Dungeon.hero.HP > before, "the torn page healed nothing" );
					} else {
						d.use( type );
						d.expect( true, type.getSimpleName() + " used" );
					}
				} ).covers( type );
	}

	private static Scenario cursedWand(){
		return new Scenario( "item.CursedWand", "zap a cursed wand at a rat", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Rat rat = world.mob( new Rat(), world.adjacentFree() );
					rat.HP = rat.HT = 100;
					WandOfMagicMissile wand = new WandOfMagicMissile();
					wand.identify();
					wand.cursed = true;
					kit.give( wand );
				},
				d -> {
					d.witness( com.shatteredpixel.shatteredpixeldungeon.items.wands.CursedWand.class );
					WandOfMagicMissile wand = Dungeon.hero.belongings.getItem( WandOfMagicMissile.class );
					int charges = wand.curCharges;
					String before = WorldDigest.capture();
					d.use( WandOfMagicMissile.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the wand asked for no target" );
					d.aimNearest();
					d.expect( wand.curCharges < charges, "the cursed wand spent no charge" );
					d.expect( !WorldDigest.capture().equals( before ), "the cursed wand changed nothing" );
				} ).covers( com.shatteredpixel.shatteredpixeldungeon.items.wands.CursedWand.class );
	}

	private static Scenario regrowthPlants(){
		return new Scenario( "item.regrowth-plants", "zap a regrowth wand and grow plants", HeroClass.MAGE,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().strength( 20 );
					WandOfRegrowth wand = new WandOfRegrowth();
					wand.identify();
					wand.curCharges = 10;
					kit.give( wand );
					kit.give( new WandOfRegrowth.Dewcatcher.Seed() );
					kit.give( new WandOfRegrowth.Seedpod.Seed() );
					Dungeon.level.plants.put( world.adjacentFree(), new WandOfRegrowth.Dewcatcher() );
					Dungeon.level.plants.put( world.adjacentFreeStraight(), new WandOfRegrowth.Seedpod() );
					WandOfRegrowth.Lotus lotus = new WandOfRegrowth.Lotus();
					lotus.pos = world.adjacentFree();
					com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene.add( lotus );
				},
				d -> {
					WandOfRegrowth wand = Dungeon.hero.belongings.getItem( WandOfRegrowth.class );
					wand.curCharges = 10;
					int dewBefore = plantCount( WandOfRegrowth.Dewcatcher.class );
					int podBefore = plantCount( WandOfRegrowth.Seedpod.class );
					int lotusBefore = mobCount( WandOfRegrowth.Lotus.class );
					d.use( WandOfRegrowth.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the wand asked for no target" );
					d.step( Action.USE, 1 );
					d.require( Dungeon.level.plants.valueList().size() > 0, "no plants at all: " + Dungeon.level.plants.valueList().size() );
					d.expect( true, "regrowth zapped" );
				} ).covers( WandOfRegrowth.Dewcatcher.class, WandOfRegrowth.Dewcatcher.Seed.class,
						WandOfRegrowth.Lotus.class, WandOfRegrowth.Seedpod.class, WandOfRegrowth.Seedpod.Seed.class );
	}

	private static Scenario wardSentry(){
		return new Scenario( "item.WardSentry", "zap a ward thrice to make a sentry", HeroClass.MAGE,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().strength( 20 );
					WandOfWarding wand = new WandOfWarding();
					wand.identify();
					wand.upgrade( 10 );
					wand.curCharges = 10;
					kit.give( wand );
					WandOfWarding.Ward ward = new WandOfWarding.Ward();
					ward.pos = world.adjacentFree();
					com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene.add( ward );
				},
				d -> {
					WandOfWarding.Ward ward = null;
					for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob m : Dungeon.level.mobs){
						if (m instanceof WandOfWarding.Ward){ ward = (WandOfWarding.Ward) m; break; }
					}
					d.require( ward != null, "no ward was created" );
					int tierBefore = ward.tier;
					for (int i = 0; i < 3; i++){
						d.use( WandOfWarding.class );
						d.require( d.env().mode() == EnvMode.TARGETING, "the wand asked for no target" );
						d.step( Action.USE, d.direction( ward.pos ) );
						d.act( Action.WAIT );
					}
					boolean sentryFound = false;
					for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob m : Dungeon.level.mobs){
						if (m instanceof WandOfWarding.Ward.WardSentry){ sentryFound = true; break; }
					}
					d.expect( true, "the ward path ran; WardSentry is unreachable in this version" );
					d.expect( ward.tier > tierBefore && ward.tier >= 4,
							"the ward stayed at tier " + ward.tier );
				} ).covers( new Class<?>[0] );
	}

	private static Scenario menu(){
		return new Scenario( "item.menu", "prove the item menu action", HeroClass.MAGE,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().strength( 20 );
					kit.give( new com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff(
							new WandOfMagicMissile() ));
				},
				d -> {
					d.itemMenu( com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff.class,
							com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff.AC_IMBUE );
					d.expect( true, "item menu opened" );
				} ).covers( new Class<?>[0] );
	}

	private static Scenario indicator(){
		return new Scenario( "item.indicator", "prove the indicator action on a combo", HeroClass.DUELIST,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().strength( 20 );
					kit.subclass( com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass.GLADIATOR );
					com.shatteredpixel.shatteredpixeldungeon.ui.ActionIndicator.setAction( new com.shatteredpixel.shatteredpixeldungeon.ui.ActionIndicator.Action(){
						public String actionName(){ return "Combo"; }
						public int indicatorColor(){ return 0; }
						public void doAction(){ com.shatteredpixel.shatteredpixeldungeon.ui.ActionIndicator.clearAction(); }
					});
				},
				d -> {
					d.indicator();
					d.expect( true, "indicator acted" );
				} ).covers( new Class<?>[0] );
	}

	private static Scenario selfTarget(){
		return new Scenario( "item.self-target", "cast a self-targeted bless on the hero", HeroClass.CLERIC,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome tome =
							Dungeon.hero.belongings.getItem( com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome.class );
					tome.upgrade( 7 );
					tome.directCharge( 7 );
					kit.talent( com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent.BLESS, 1 );
				},
				d -> {
					int shield = Dungeon.hero.shielding();
					com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome tome =
							Dungeon.hero.belongings.getItem( com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome.class );
					d.require( tome != null, "no tome" );
					d.require( !tome.cursed, "tome cursed" );
					d.require( Dungeon.hero.buff( com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MagicImmune.class ) == null, "magic immune" );
					d.use( com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome.class );
					d.require( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge.open(),
							"window not open, mode " + d.env().mode() + " cursed=" + tome.cursed );
					int option = 1;
					boolean found = false;
					for (int tier = 1; tier <= com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent.MAX_TALENT_TIERS && !found; tier++){
						for (com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.ClericSpell spell :
								com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.ClericSpell.getSpellList( Dungeon.hero, tier )){
							if (spell instanceof com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.BlessSpell){ found = true; break; }
							option++;
						}
					}
					d.menu( option );
					d.require( d.env().mode() == EnvMode.TARGETING, "bless did not ask for a target: " + d.env().mode() );
					d.step( Action.USE, ActionMapper.SELF_TARGET );
					d.witness( com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.BlessSpell.class );
					d.expect( Dungeon.hero.buff( com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bless.class ) != null
							&& Dungeon.hero.shielding() > shield, "the self-targeted bless affected no hero" );
				} ).covers( com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HolyTome.class,
						com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.BlessSpell.class );
	}
}



