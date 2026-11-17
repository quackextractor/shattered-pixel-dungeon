package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.Ratmogrify;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Bat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Rat {

	private TalentTier4Rat(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( TalentTier4.of( Talent.RATSISTANCE, HeroClass.ROGUE, new Ratmogrify(), ( world, kit ) -> {
			bat( world );
		}, d -> TalentTier4.useAbility( d ), () -> () -> {
			Ratmogrify.TransmogRat rat = transmog();
			if (rat == null) return false;
			return TalentTier4.mean( rat::damageRoll ) < TalentTier4.mean( original( rat )::damageRoll ) * 0.95f;
		}, "the turned rat hit just as hard" ));
		all.add( TalentTier4.of( Talent.RATLOMACY, HeroClass.ROGUE, new Ratmogrify(), ( world, kit ) -> {
			bat( world );
		}, d -> {
			TalentTier4.useAbility( d );
			TalentTier4.armor().charge = 100f;
			TalentTier4.useAbility( d );
		}, () -> () -> {
			Ratmogrify.TransmogRat rat = transmog();
			return rat != null && rat.alignment == Char.Alignment.ALLY;
		}, "the turned rat stayed hostile" ));
		all.add( TalentTier4.of( Talent.RATFORCEMENTS, HeroClass.ROGUE, new Ratmogrify(), ( world, kit ) -> {},
				d -> {
					TalentFamily.unmask( d, Action.USE );
					d.use( ClassArmor.class );
					if (d.env().mode() == EnvMode.TARGETING) d.step( Action.USE, ActionMapper.SELF_TARGET_ALIAS );
				}, () -> () -> {
					for (Mob mob : new ArrayList<>( new World().level().mobs ))
						if (mob instanceof Rat && mob.alignment == Char.Alignment.ALLY && mob.isAlive()) return true;
					return false;
				}, "ratmogrify summoned no rats" ));
		return all;
	}

	private static void bat( World world ){
		Bat bat = world.mob( new Bat(), world.adjacentFree() );
		bat.HP = bat.HT = 1000;
		Buff.affect( bat, Paralysis.class, 50f );
		com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator.instance =
				new com.shatteredpixel.shatteredpixeldungeon.ui.TargetHealthIndicator();
	}

	private static Ratmogrify.TransmogRat transmog(){
		for (Mob mob : new World().level().mobs) if (mob instanceof Ratmogrify.TransmogRat) return (Ratmogrify.TransmogRat) mob;
		return null;
	}

	private static Mob original( Ratmogrify.TransmogRat rat ){
		try {
			java.lang.reflect.Field field = Ratmogrify.TransmogRat.class.getDeclaredField( "original" );
			field.setAccessible( true );
			return (Mob) field.get( rat );
		} catch (Exception e){
			throw new NoFixture( e.toString() );
		}
	}
}
