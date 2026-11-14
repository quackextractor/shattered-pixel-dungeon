package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Blob;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfLiquidFlame;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfBlastWave;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfCorrosion;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfCorruption;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfDisintegration;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfFireblast;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfFrost;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfLightning;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfLivingEarth;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfPrismaticLight;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfRegrowth;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfTransfusion;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfWarding;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.SpiritBow;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.MagesStaff;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingKnife;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.ThrowingStone;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.Dart;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.missiles.darts.ParalyticDart;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class RangedScenarios {

	private RangedScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( shoot( "bow", HeroClass.HUNTRESS, SpiritBow.class, null ));
		all.add( shoot( "staff", HeroClass.MAGE, MagesStaff.class, null ));
		all.add( thrown( "thrown.ThrowingKnife", ThrowingKnife.class ));
		all.add( thrown( "thrown.Dart", Dart.class ));
		all.add( thrown( "thrown.ThrowingStone", ThrowingStone.class ));
		all.add( thrown( "thrown.ParalyticDart", ParalyticDart.class ));
		List< Class< ? extends Wand > > wands = List.of( WandOfMagicMissile.class, WandOfFireblast.class, WandOfFrost.class,
				WandOfLightning.class, WandOfDisintegration.class, WandOfCorrosion.class, WandOfBlastWave.class,
				WandOfLivingEarth.class, WandOfPrismaticLight.class, WandOfRegrowth.class, WandOfTransfusion.class,
				WandOfWarding.class, WandOfCorruption.class );
		for (Class< ? extends Wand > wand : wands){
			all.add( shoot( "wand." + wand.getSimpleName().replace( "WandOf", "" ), HeroClass.WARRIOR, wand,
					() -> Reflection.newInstance( wand )));
		}
		all.add( potionThrow() );
		return all;
	}

	private static Rat target( World world, int range ){
		Rat rat = world.mob( new Rat(), world.cellAtRange( range, true ));
		rat.HP = rat.HT = 100;
		return rat;
	}

	private static boolean affected( Rat rat, int hp, int pos ){
		return !rat.isAlive() || rat.HP < hp || rat.pos != pos || !rat.buffs().isEmpty()
				|| rat.alignment != com.shatteredpixel.shatteredpixeldungeon.actors.Char.Alignment.ENEMY;
	}

	private static Scenario shoot( String id, HeroClass hero, Class< ? extends Item > type, Supplier< ? extends Item > give ){
		return new Scenario( id, "aim " + type.getSimpleName() + " at an enemy", hero,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					if (give != null) kit.give( give.get() );
					target( world, 3 );
				},
				d -> {
					Rat rat = d.world().find( Rat.class );
					Item item = d.slotOf( type ) < 0 ? null : d.env().mapper().slot( d.slotOf( type ));
					d.require( item != null, "no " + type.getSimpleName() + " in the slot list" );
					String charges = item.status();
					int hp = rat.HP, pos = rat.pos;
					d.until( () -> affected( rat, hp, pos ), 3, () -> {
						d.use( type );
						d.require( d.env().mode() == EnvMode.TARGETING, type.getSimpleName() + " did not ask for a target: "
								+ d.env().mode() );
						d.aimNearest();
					});
					d.expect( affected( rat, hp, pos ), "three shots of " + type.getSimpleName() + " left the rat untouched" );
					if (charges != null) d.expect( !charges.equals( item.status() ), type.getSimpleName() + " still shows "
							+ charges + " charges" );
				} );
	}

	private static Scenario thrown( String id, Class< ? extends Item > type ){
		return new Scenario( id, "throw " + type.getSimpleName() + " at an enemy", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( Reflection.newInstance( type ).quantity( 3 ));
					target( world, 3 );
				},
				d -> {
					Rat rat = d.world().find( Rat.class );
					Item pack = Dungeon.hero.belongings.getItem( type );
					int quantity = pack.quantity();
					int hp = rat.HP, pos = rat.pos;
					d.use( type );
					d.require( d.env().mode() == EnvMode.TARGETING, type.getSimpleName() + " did not ask for a target: "
							+ d.env().mode() );
					d.aimNearest();
					d.expect( pack.quantity() == quantity - 1, "a throw left " + pack.quantity() + " of " + quantity );
					boolean landed = false;
					for (int cell : com.watabou.utils.PathFinder.NEIGHBOURS9){
						landed |= Dungeon.level.heaps.get( pos + cell ) != null;
					}
					d.expect( affected( rat, hp, pos ) || landed, "the " + type.getSimpleName() + " neither hit nor landed by the rat" );
					if (type == ParalyticDart.class && rat.HP < hp){
						d.expect( rat.buff( Paralysis.class ) != null, "a paralytic dart hit and did not paralyse" );
					}
				} );
	}

	private static Scenario potionThrow(){
		return new Scenario( "potion-throw", "throw a known Liquid Flame", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new PotionOfLiquidFlame() );
					target( world, 3 );
				},
				d -> {
					d.use( PotionOfLiquidFlame.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the potion did not ask for a target: " + d.env().mode() );
					d.aimNearest();
					Blob fire = Dungeon.level.blobs.get( Fire.class );
					d.expect( fire != null && fire.volume > 0, "a thrown Liquid Flame started no fire" );
				} );
	}
}
