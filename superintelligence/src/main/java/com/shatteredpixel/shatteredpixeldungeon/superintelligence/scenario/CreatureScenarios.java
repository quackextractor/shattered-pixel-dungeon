package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.blobs.ToxicGas;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Brute;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DM200;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Ghoul;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Golem;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.RipperDemon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Statue;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.Wand;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfCorruption;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfLivingEarth;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfWarding;

import java.util.ArrayList;
import java.util.List;

final class CreatureScenarios {

	private CreatureScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( bruteRage() );
		all.add( MobScenarios.watch( "mob.Ripper", RipperDemon.class, 4, 10, () -> Dungeon.hero.HP < Dungeon.hero.HT,
				"the Ripper never reached the hero from 4 cells" ));
		all.add( MobScenarios.watch( "mob.Golem", Golem.class, 4, 12, () -> Dungeon.hero.HP < Dungeon.hero.HT,
				"the Golem never reached the hero from 4 cells" ));
		all.add( MobScenarios.watch( "mob.DM200", DM200.class, 4, 10, () -> Dungeon.level.blobs.get( ToxicGas.class ) != null
				|| Dungeon.hero.HP < Dungeon.hero.HT, "the DM-200 neither vented nor hit" ));
		all.add( statue() );
		all.add( ghoulRevives() );
		all.add( ally( "ally.LivingEarth", WandOfLivingEarth::new, WandOfLivingEarth.class, 1000, true,
				() -> MobScenarios.count( WandOfLivingEarth.EarthGuardian.class ) > 0, "no Earth Guardian formed" ));
		all.add( ally( "ally.Corruption", WandOfCorruption::new, WandOfCorruption.class, 1, true, CreatureScenarios::ratAllied, "the rat was not corrupted" ));
		all.add( ally( "ally.Ward", WandOfWarding::new, WandOfWarding.class, 8, false,
				() -> MobScenarios.count( WandOfWarding.Ward.class ) > 0, "no Ward was placed" ));
		return all;
	}

	private static Scenario bruteRage(){
		return new Scenario( "mob.Brute", "a felled brute rages", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					world.hunting( new Brute(), world.adjacentFree() ).HP = 1;
				},
				d -> {
					Brute brute = d.world().find( Brute.class );
					d.until( () -> brute.HP <= 0, 10, () -> d.move( brute.pos ));
					d.expect( brute.HP <= 0 && brute.isAlive(), "a brute struck to " + brute.HP + " HP did not rage" );
				} );
	}

	private static Scenario statue(){
		return new Scenario( "mob.Statue", "an animated statue fights with its weapon", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					world.hunting( Statue.random( false ), world.adjacentFree() );
				},
				d -> {
					d.waitFor( () -> Dungeon.hero.HP < Dungeon.hero.HT, 8 );
					d.expect( Dungeon.hero.HP < Dungeon.hero.HT, "the Statue never hit the hero" );
				} );
	}

	private static Scenario ghoulRevives(){
		return new Scenario( "mob.Ghoul", "a felled ghoul rises beside its partner", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					int first = world.adjacentFree();
					world.hunting( new Ghoul(), first ).HP = 1;
					world.hunting( new Ghoul(), world.adjacentFree() );
				},
				d -> {
					Ghoul ghoul = d.world().find( Ghoul.class );
					d.until( () -> ghoul.HP <= 0 || !ghoul.isAlive(), 10, () -> d.move( ghoul.pos ));
					d.expect( MobScenarios.count( Ghoul.class ) == 2, "the felled ghoul left the level beside a live partner" );
				} );
	}

	private static Scenario ally( String id, java.util.function.Supplier< Wand > make, Class< ? extends Wand > type, int ratHp, boolean atRat, java.util.function.BooleanSupplier made, String failure ){
		return new Scenario( id, id.substring( 5 ) + " makes an ally", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Wand wand = make.get();
					wand.upgrade( 6 );
					kit.give( wand ).curCharges = wand.maxCharges;
					Rat rat = world.hunting( new Rat(), world.cellAtRange( 3, true ));
					rat.HP = Math.min( ratHp, rat.HT );
					if (ratHp > rat.HT) rat.HP = rat.HT = ratHp;
				},
				d -> {
					for (int zap = 0; zap < 8 && !made.getAsBoolean(); zap++){
						d.use( type );
						if (d.env().mode() != com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode.TARGETING) break;
						if (atRat) d.aimNearest();
						else d.step( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.USE, d.direction( d.world().adjacentFree() ));
					}
					d.expect( made.getAsBoolean(), failure );
				} );
	}

	private static boolean ratAllied(){
		for (Mob mob : Dungeon.level.mobs) if (mob instanceof Rat && mob.alignment == Char.Alignment.ALLY) return true;
		return false;
	}
}
