package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Charm;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Bat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DM100;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Elemental;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Eye;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GnollTrickster;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Goo;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Necromancer;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Scorpio;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Shaman;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Skeleton;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Spinner;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Succubus;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Swarm;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Thief;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Warlock;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfMirrorImage;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.MirrorImage;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.watabou.utils.Reflection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

final class MobScenarios {

	private MobScenarios(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( ranged( "zap.RedShaman", Shaman.RedShaman.class ));
		all.add( ranged( "zap.BlueShaman", Shaman.BlueShaman.class ));
		all.add( ranged( "zap.PurpleShaman", Shaman.PurpleShaman.class ));
		all.add( ranged( "zap.Warlock", Warlock.class ));
		all.add( ranged( "zap.DM100", DM100.class ));
		all.add( ranged( "zap.Scorpio", Scorpio.class ));
		all.add( ranged( "zap.GnollTrickster", GnollTrickster.class ));
		all.add( ranged( "zap.FireElemental", Elemental.FireElemental.class ));
		all.add( ranged( "zap.FrostElemental", Elemental.FrostElemental.class ));
		all.add( ranged( "zap.ShockElemental", Elemental.ShockElemental.class ));
		all.add( ranged( "zap.Spinner", Spinner.class ));
		all.add( ranged( "eye-beam", Eye.class ));
		all.add( ranged( "mob.Bat", Bat.class ));
		all.add( watch( "goo-pump", Goo.class, 2, 8, () -> Dungeon.hero.HP < Dungeon.hero.HT, "Goo never hit the hero" ));
		all.add( watch( "necro-summon", Necromancer.class, 4, 10, () -> count( Skeleton.class ) > 0,
				"the Necromancer raised no Skeleton" ));
		all.add( watch( "mob.Succubus", Succubus.class, 1, 10, () -> Dungeon.hero.buff( Charm.class ) != null,
				"the Succubus never charmed the hero" ));
		all.add( watch( "mob.Thief", Thief.class, 1, 10, () -> thiefHolds(), "the Thief stole nothing" ));
		all.add( swarmSplit() );
		all.add( mirrorImage() );
		return all;
	}

	static int count( Class< ? extends Mob > type ){
		int n = 0;
		for (Mob mob : Dungeon.level.mobs) if (type.isInstance( mob )) n++;
		return n;
	}

	private static boolean thiefHolds(){
		for (Mob mob : Dungeon.level.mobs) if (mob instanceof Thief && ((Thief) mob).item != null) return true;
		return count( Thief.class ) == 0;
	}

	private static Scenario ranged( String id, Class< ? extends Mob > type ){
		return watch( id, type, 4, 8, () -> Dungeon.hero.HP < Dungeon.hero.HT || !Dungeon.hero.buffs().isEmpty()
				&& debuffed(), "the mob did nothing to the hero in 8 turns" );
	}

	private static boolean debuffed(){
		for (com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff buff : Dungeon.hero.buffs()){
			if (buff.type == com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff.buffType.NEGATIVE) return true;
		}
		return false;
	}

	static Scenario watch( String id, Class< ? extends Mob > type, int range, int waits, BooleanSupplier effect,
			String failure ){
		return new Scenario( id, type.getSimpleName() + " acts on the hero", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					int cell = range == 1 ? world.adjacentFree() : world.cellAtRange( range, true );
					world.hunting( Reflection.newInstance( type ), cell );
				},
				d -> {
					d.waitFor( effect, waits );
					d.expect( effect.getAsBoolean(), failure );
					d.expect( d.env().running(), "the episode ended (" + d.env().endReason() + ", " + d.env().stallCause() + ")" );
				} );
	}

	private static Scenario swarmSplit(){
		return new Scenario( "mob.Swarm", "a hit swarm splits", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					world.hunting( new Swarm(), world.adjacentFree() );
				},
				d -> {
					Swarm swarm = d.world().find( Swarm.class );
					d.until( () -> count( Swarm.class ) > 1 || !swarm.isAlive(), 8, () -> d.move( swarm.pos ));
					d.expect( count( Swarm.class ) > 1, "eight attacks on a swarm split nothing off" );
				} );
	}

	private static Scenario mirrorImage(){
		return new Scenario( "ally.MirrorImage", "mirror images fight beside the hero", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new ScrollOfMirrorImage() );
					world.hunting( new Rat(), world.cellAtRange( 3, true ));
				},
				d -> {
					d.use( ScrollOfMirrorImage.class );
					d.expect( count( MirrorImage.class ) > 0, "the scroll made no mirror image" );
					Rat rat = d.world().find( Rat.class );
					int hp = rat.HP;
					d.waitFor( () -> !rat.isAlive() || rat.HP < hp, 6 );
					d.expect( !rat.isAlive() || rat.HP < hp, "the images and the hero left the rat untouched for 6 turns" );
				} );
	}
}
