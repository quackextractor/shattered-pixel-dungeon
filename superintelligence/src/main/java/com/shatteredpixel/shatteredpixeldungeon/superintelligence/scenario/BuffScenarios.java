package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Challenges;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Degrade;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Doom;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Fury;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.GravityChaosTracker;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.HeroDisguise;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hex;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Invulnerability;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.LifeLink;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Paralysis;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Poison;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Speed;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.SuperNovaTracker;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.SwarmIntelTracker;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Vulnerable;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.KindOfWeapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;

import java.util.List;
import java.util.function.BiConsumer;

final class BuffScenarios {

	private BuffScenarios(){}

	private static final int STRIKE_LEVEL = 30;

	private static final int STRIKES = 12;

	private static final float POISON = 30f;

	static List< Scenario > all(){
		return List.of( degrade(), strike( Fury.class, "fury lifts the hero's hits at low health", ( kit, rat ) -> {
			kit.health( 400, 1000 );
			kit.buff( Fury.class );
		}, d -> {} ), gravity(), disguise(), hex(), invulnerability(), lifeLink(), speed(), superNova(), swarm(),
				strike( Vulnerable.class, "a vulnerable rat takes heavier hits", ( kit, rat ) -> Buff.affect( rat, Vulnerable.class, 14f ),
						d -> wearsOff( d, d.world().find( Rat.class ), Vulnerable.class, 30 )),
				strike( Doom.class, "a doomed rat takes heavier hits", ( kit, rat ) -> Buff.affect( rat, Doom.class ), d -> {} ));
	}

	private static Scenario buff( Class< ? extends Buff > type, String mechanic, Scenario.Setup setup, Scenario.Script script ){
		return new Scenario( "buff." + type.getSimpleName(), mechanic, HeroClass.WARRIOR, ( world, kit ) -> {
			world.clearMobs();
			kit.sturdy();
			setup.build( world, kit );
		}, script ).covers( type );
	}

	private static Scenario strike( Class< ? extends Buff > type, String mechanic, BiConsumer< HeroKit, Rat > apply, Scenario.Script after ){
		int[] cap = { 0 };
		return buff( type, mechanic, ( world, kit ) -> {
			Rat rat = paralysed( world.mob( new Rat(), world.adjacentFree() ), 1000 );
			kit.hero().belongings.weapon.level( STRIKE_LEVEL );
			cap[ 0 ] = maxHit();
			apply.accept( kit, rat );
		}, d -> {
			Rat rat = d.world().find( Rat.class );
			int[] best = { 0 };
			d.until( () -> best[ 0 ] > cap[ 0 ], STRIKES, () -> {
				int hp = rat.HP;
				d.move( rat.pos );
				best[ 0 ] = Math.max( best[ 0 ], hp - rat.HP );
			});
			d.expect( best[ 0 ] > cap[ 0 ], type.getSimpleName() + " never lifted a hit above the " + cap[ 0 ]
					+ " the weapon could deal before it; the best hit took " + best[ 0 ] );
			after.run( d );
		});
	}

	private static int maxHit(){
		Weapon weapon = (Weapon) Dungeon.hero.belongings.weapon;
		return weapon.max() + Math.max( 0, Dungeon.hero.STR() - weapon.STRReq() );
	}

	private static Scenario degrade(){
		int[] cap = { 0 };
		return buff( Degrade.class, "degradation lowers the worn weapon's effective level until it ends", ( world, kit ) -> {
			kit.hero().belongings.weapon.level( 10 );
			cap[ 0 ] = kit.hero().belongings.weapon.buffedLvl();
			kit.buff( Degrade.class, 4f );
		}, d -> {
			KindOfWeapon weapon = Dungeon.hero.belongings.weapon;
			d.act( Action.WAIT );
			d.expect( weapon.buffedLvl() < cap[ 0 ], "degradation left the weapon at effective level " + weapon.buffedLvl() + " of " + cap[ 0 ] );
			wearsOff( d, Dungeon.hero, Degrade.class, 10 );
			d.expect( weapon.buffedLvl() == cap[ 0 ], "the weapon stayed at level " + weapon.buffedLvl() + " after degradation ended" );
		});
	}

	private static Scenario gravity(){
		int[] cap = { 0, 0 };
		return buff( GravityChaosTracker.class, "gravity chaos throws characters about", ( world, kit ) -> {
			Rat rat = paralysed( world.mob( new Rat(), world.cellAtRange( 2, false )), 100 );
			cap[ 0 ] = Dungeon.hero.pos;
			cap[ 1 ] = rat.pos;
			kit.buff( GravityChaosTracker.class );
		}, d -> {
			Rat rat = d.world().find( Rat.class );
			d.waitFor( () -> Dungeon.hero.pos != cap[ 0 ] || rat.pos != cap[ 1 ], 12 );
			d.expect( Dungeon.hero.pos != cap[ 0 ] || rat.pos != cap[ 1 ], "gravity chaos moved neither the waiting hero nor the paralysed rat" );
		});
	}

	private static Scenario disguise(){
		HeroClass[] cap = { null };
		return buff( HeroDisguise.class, "a disguise shows the hero as another class until it ends", ( world, kit ) -> {
			cap[ 0 ] = shownClass();
			kit.buff( HeroDisguise.class, 4f );
		}, d -> {
			d.act( Action.WAIT );
			d.expect( shownClass() != cap[ 0 ], "the disguise still showed the hero as " + shownClass() );
			wearsOff( d, Dungeon.hero, HeroDisguise.class, 10 );
			d.expect( shownClass() == cap[ 0 ], "the hero still showed as " + shownClass() + " after the disguise ended" );
		});
	}

	private static HeroClass shownClass(){
		HeroDisguise disguise = Dungeon.hero.buff( HeroDisguise.class );
		return disguise == null || disguise.getDisguise() == null ? Dungeon.hero.heroClass : disguise.getDisguise();
	}

	private static Scenario hex(){
		float[] cap = { 0 };
		int[] ids = { 0, 0 };
		return buff( Hex.class, "a hexed rat lands fewer blows", ( world, kit ) -> {
			Rat attacker = passive( world.mob( new Rat(), world.cellAtRange( 2, false )));
			Rat defender = passive( world.mob( new Rat(), world.cellAtRange( 3, false )));
			ids[ 0 ] = attacker.id();
			ids[ 1 ] = defender.id();
			cap[ 0 ] = accuracy( attacker, defender );
			Buff.affect( attacker, Hex.class, 4f );
		}, d -> {
			Char attacker = (Char) Actor.findById( ids[ 0 ] );
			Char defender = (Char) Actor.findById( ids[ 1 ] );
			d.act( Action.WAIT );
			float hexed = accuracy( attacker, defender );
			d.expect( hexed < cap[ 0 ], "the hexed rat hit " + hexed + " of the time against " + cap[ 0 ] + " before the hex" );
			wearsOff( d, attacker, Hex.class, 10 );
		});
	}

	private static float accuracy( Char attacker, Char defender ){
		return PotionScenarios.mean( () -> Char.hit( attacker, defender, false ) ? 1 : 0 );
	}

	private static Scenario invulnerability(){
		int[] cap = { 0 };
		return buff( Invulnerability.class, "an invulnerable hero takes no poison damage until it ends", ( world, kit ) -> {
			kit.buff( Poison.class ).set( POISON );
			cap[ 0 ] = Dungeon.hero.HP;
			kit.buff( Invulnerability.class, 3f );
		}, d -> {
			Poison poison = Dungeon.hero.buff( Poison.class );
			int left = poisonLeft( poison );
			d.act( Action.WAIT );
			d.act( Action.WAIT );
			d.expect( Dungeon.hero.buff( Invulnerability.class ) != null && poisonLeft( poison ) < left && Dungeon.hero.HP == cap[ 0 ],
					"the poison ticked from " + left + " to " + poisonLeft( poison ) + " and took the hero from " + cap[ 0 ] + " to " + Dungeon.hero.HP );
			wearsOff( d, Dungeon.hero, Invulnerability.class, 6 );
			d.waitFor( () -> Dungeon.hero.HP < cap[ 0 ], 3 );
			d.expect( Dungeon.hero.HP < cap[ 0 ], "the poison never hurt the hero after invulnerability ended" );
		});
	}

	private static int poisonLeft( Poison poison ){
		return Integer.parseInt( poison.iconTextDisplay() );
	}

	private static Scenario lifeLink(){
		int[] cap = { 0 };
		return buff( LifeLink.class, "a life link passes half the hero's poison damage to a rat", ( world, kit ) -> {
			Rat rat = paralysed( world.mob( new Rat(), world.cellAtRange( 2, false )), 100 );
			kit.buff( Poison.class ).set( POISON );
			cap[ 0 ] = rat.HP;
			kit.buff( LifeLink.class, 3f ).object = rat.id();
		}, d -> {
			Rat rat = d.world().find( Rat.class );
			d.act( Action.WAIT );
			d.expect( rat.HP < cap[ 0 ], "the linked rat kept " + rat.HP + " of " + cap[ 0 ] + " HP while the hero was poisoned" );
			wearsOff( d, Dungeon.hero, LifeLink.class, 6 );
			int hp = rat.HP;
			d.act( Action.WAIT );
			d.act( Action.WAIT );
			d.expect( rat.HP == hp, "the rat lost HP from " + hp + " to " + rat.HP + " after the link ended" );
		});
	}

	private static Scenario speed(){
		float[] cap = { 0 };
		return buff( Speed.class, "a hasted hero steps in less time until it ends", ( world, kit ) -> {
			cap[ 0 ] = 1f / kit.hero().speed();
			kit.buff( Speed.class, 3f );
		}, d -> {
			int home = Dungeon.hero.pos;
			int away = d.world().adjacentFreeStraight();
			float fast = PotionScenarios.cost( () -> d.move( away ));
			d.expect( fast < cap[ 0 ] - 0.1f, "a hasted step cost " + fast + " turns against " + cap[ 0 ] + " before the haste" );
			PotionScenarios.wearsOff( d, Speed.class, 20 );
			float slow = PotionScenarios.cost( () -> d.move( Dungeon.hero.pos == home ? away : home ));
			d.expect( Math.abs( slow - cap[ 0 ] ) < 0.01f, "a step after the haste cost " + slow + " turns against " + cap[ 0 ] );
		});
	}

	private static Scenario superNova(){
		int[] cap = { 0 };
		return buff( SuperNovaTracker.class, "a supernova counts down, then blasts everything around its centre", ( world, kit ) -> {
			Rat rat = paralysed( world.mob( new Rat(), world.cellAtRange( 3, true )), 100 );
			cap[ 0 ] = rat.HP;
			kit.buff( SuperNovaTracker.class ).pos = rat.pos;
		}, d -> {
			Rat rat = d.world().find( Rat.class );
			for (int i = 0; i < 5; i++) d.act( Action.WAIT );
			d.expect( rat.isAlive() && rat.HP == cap[ 0 ], "the supernova struck the rat before its countdown ran out" );
			d.waitFor( () -> !rat.isAlive() || rat.HP < cap[ 0 ], 12 );
			d.expect( !rat.isAlive() || rat.HP < cap[ 0 ], "the supernova never blasted the rat at its centre" );
		});
	}

	private static Scenario swarm(){
		int[] ids = { 0 };
		return buff( SwarmIntelTracker.class, "swarm intelligence wakes an unseen sleeper toward the hero", ( world, kit ) -> {
			Dungeon.challenges |= Challenges.SWARM_INTELLIGENCE;
			world.hunting( new Rat(), world.cellAtRange( 2, true ));
			Rat sleeper = world.mob( new Rat(), unseen( world ));
			sleeper.state = sleeper.SLEEPING;
			ids[ 0 ] = sleeper.id();
			kit.buff( SwarmIntelTracker.class );
		}, d -> {
			Rat sleeper = (Rat) Actor.findById( ids[ 0 ] );
			d.waitFor( () -> alertRange() > 0 && sleeper.state != sleeper.SLEEPING, 10 );
			d.expect( alertRange() > 0 && sleeper.state != sleeper.SLEEPING, "the swarm tracker showed alert range " + alertRange()
					+ " and the unseen rat stayed asleep" );
		});
	}

	private static int alertRange(){
		SwarmIntelTracker tracker = Dungeon.hero.buff( SwarmIntelTracker.class );
		return tracker == null ? 0 : Integer.parseInt( tracker.iconTextDisplay() );
	}

	private static int unseen( World world ){
		Level level = world.level();
		for (int cell = 0; cell < level.length(); cell++){
			int range = level.distance( world.hero(), cell );
			if (world.free( cell ) && !level.heroFOV[ cell ] && range >= 3 && range <= 8) return cell;
		}
		throw new NoFixture( "no free cell 3-8 from the hero out of its sight" );
	}

	private static void wearsOff( Driver d, Char target, Class< ? extends Buff > type, int max ){
		d.expect( d.until( () -> target.buff( type ) == null, max, () -> d.act( Action.WAIT )),
				type.getSimpleName() + " never wore off in " + max + " turns" );
	}

	private static Rat paralysed( Rat rat, int hp ){
		rat.HP = rat.HT = hp;
		Buff.affect( rat, Paralysis.class, 100f );
		return rat;
	}

	private static Rat passive( Rat rat ){
		rat.state = rat.PASSIVE;
		return rat;
	}
}
