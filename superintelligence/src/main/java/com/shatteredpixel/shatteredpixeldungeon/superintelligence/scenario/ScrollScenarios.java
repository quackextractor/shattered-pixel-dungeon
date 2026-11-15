package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.ArtifactRecharge;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Dread;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Foresight;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.MagicImmune;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.PrismaticGuard;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Weakness;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.PrismaticImage;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.SandalsOfNature;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.Ring;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRetribution;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfAntiMagic;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfChallenge;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfDivination;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfDread;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfForesight;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfMetamorphosis;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfMysticalEnergy;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfPassage;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfPrismaticImage;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfPsionicBlast;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfSirensSong;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.AIM;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.USE;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.effect;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.rat;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.target;
import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario.PotionScenarios.wearsOff;

final class ScrollScenarios {

	private ScrollScenarios(){}

	private static final Scenario.Setup NOTHING = ( world, kit ) -> {};

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( effect( "scroll.Retribution", ScrollOfRetribution.class, USE, ( world, kit ) -> target( world, 100 ), 0,
				"the retribution never hurt the rat", ScrollScenarios::ratHurt ).covers());
		all.add( effect( "scroll.AntiMagic", ScrollOfAntiMagic.class, USE, ( world, kit ) -> kit.buff( Weakness.class, 50f ), 0,
				"the anti-magic left the hero weakened or unshielded from magic",
				() -> () -> Dungeon.hero.buff( MagicImmune.class ) != null && Dungeon.hero.buff( Weakness.class ) == null )
				.then( d -> wearsOff( d, MagicImmune.class, 30 )).covers( MagicImmune.class ));
		all.add( effect( "scroll.Challenge", ScrollOfChallenge.class, USE, ScrollScenarios::sleeperOutOfSight, 0,
				"the challenge raised no arena or left the far rat asleep", () -> {
			Mob rat = rat();
			return () -> Dungeon.hero.buff( ScrollOfChallenge.ChallengeArena.class ) != null && rat.state != rat.SLEEPING;
		}).covers());
		all.add( divination() );
		all.add( effect( "scroll.Dread", ScrollOfDread.class, USE, ( world, kit ) -> world.hunting( new Rat(), world.cellAtRange( 3, true )), 4,
				"the dread never drove the rat away", () -> {
			Mob rat = rat();
			int distance = Dungeon.level.distance( rat.pos, Dungeon.hero.pos );
			return () -> rat.buff( Dread.class ) != null && Dungeon.level.distance( rat.pos, Dungeon.hero.pos ) > distance;
		}).covers( Dread.class ));
		all.add( foresight() );
		all.add( metamorphosis() );
		all.add( effect( "scroll.MysticalEnergy", ScrollOfMysticalEnergy.class, USE, ScrollScenarios::sandals, 3,
				"the mystical energy never charged the worn sandals", () -> {
			int charge = sandalsCharge();
			return () -> Dungeon.hero.buff( ArtifactRecharge.class ) != null && sandalsCharge() > charge;
		}).covers( ArtifactRecharge.class ));
		all.add( effect( "scroll.Passage", ScrollOfPassage.class, USE, NOTHING, 0, "the passage did not return the hero to depth 1", () -> {
			int depth = Dungeon.depth;
			return () -> depth == 3 && Dungeon.depth == 1;
		}).covers().depth( 3 ));
		all.add( prismaticImage() );
		all.add( effect( "scroll.PsionicBlast", ScrollOfPsionicBlast.class, USE, ( world, kit ) -> target( world, 100 ), 0,
				"the psionic blast spared the rat or left the hero unhurt, sighted or strong", () -> {
			BooleanSupplier hurt = ratHurt();
			int hp = Dungeon.hero.HP;
			return () -> hurt.getAsBoolean() && Dungeon.hero.HP < hp
					&& Dungeon.hero.buff( Weakness.class ) != null && Dungeon.hero.buff( Blindness.class ) != null;
		}).covers());
		all.add( effect( "scroll.SirensSong", ScrollOfSirensSong.class, AIM, ( world, kit ) -> target( world, 100 ), 0,
				"the siren's song never enthralled the rat", () -> {
			Mob rat = rat();
			return () -> rat.alignment == Char.Alignment.ALLY && rat.buff( ScrollOfSirensSong.Enthralled.class ) != null;
		}).covers());
		return all;
	}

	private static BooleanSupplier ratHurt(){
		Mob rat = rat();
		int hp = rat.HP;
		return () -> !rat.isAlive() || rat.HP < hp;
	}

	private static Scenario divination(){
		return new Scenario( "scroll.Divination", "read divination and dismiss its list", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.give( new ScrollOfDivination() );
				},
				d -> {
					int unknown = unknown();
					d.require( unknown >= 4, "only " + unknown + " unknown item types to divine" );
					d.use( ScrollOfDivination.class );
					d.expect( unknown() == unknown - 4, "divination left " + unknown() + " unknown types of " + unknown );
					d.expect( d.env().mode() == EnvMode.WORLD, "the divination list stayed open: " + d.env().mode() );
				} ).covers( ScrollOfDivination.class );
	}

	private static Scenario foresight(){
		Scenario s = effect( "scroll.Foresight", ScrollOfForesight.class, USE, NOTHING, 0, "the foresight mapped nothing around the hero", () -> {
			int mapped = mapped();
			return () -> Dungeon.hero.buff( Foresight.class ) != null && mapped() > mapped;
		}).then( d -> wearsOff( d, Foresight.class, 420 )).covers( Foresight.class );
		s.maxSteps = 440;
		return s;
	}

	private static Scenario metamorphosis(){
		return new Scenario( "scroll.Metamorphosis", "metamorphose the first talent through both talent windows", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.give( new ScrollOfMetamorphosis() );
				},
				d -> {
					Talent old = Dungeon.hero.talents.get( 0 ).keySet().iterator().next();
					int morphed = Dungeon.hero.metamorphedTalents.size();
					d.use( ScrollOfMetamorphosis.class );
					answer( d, "WndMetamorphChoose" );
					answer( d, "WndInfoTalent" );
					answer( d, "WndMetamorphReplace" );
					answer( d, "WndInfoTalent" );
					Map< Talent, Talent > now = Dungeon.hero.metamorphedTalents;
					d.expect( now.size() > morphed && now.containsKey( old ) && !Dungeon.hero.talents.get( 0 ).containsKey( old )
							&& Dungeon.hero.talents.get( 0 ).containsKey( now.get( old )), "the talent " + old + " was not replaced: " + now );
					d.expect( d.env().mode() == EnvMode.WORLD, "a metamorphosis window stayed open: " + WindowBridge.describe() );
				} ).covers( ScrollOfMetamorphosis.class );
	}

	private static void answer( Driver d, String window ){
		d.require( d.env().mode() == EnvMode.MENU && WindowBridge.describe().startsWith( window + "(" ),
				"expected " + window + ", saw " + d.env().mode() + " " + WindowBridge.describe() );
		d.menu( 0 );
	}

	private static Scenario prismaticImage(){
		return new Scenario( "scroll.PrismaticImage", "read prismatic image beside a hunting rat", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new ScrollOfPrismaticImage() );
					world.hunting( new Rat(), world.cellAtRange( 3, true ));
				},
				d -> {
					Mob rat = rat();
					d.require( Dungeon.hero.buff( PrismaticGuard.class ) == null && image() == null, "a prismatic guard stood before the scroll" );
					d.use( ScrollOfPrismaticImage.class );
					d.expect( Dungeon.hero.buff( PrismaticGuard.class ) != null || image() != null, "the scroll gave the hero no prismatic guard" );
					int hp = rat.HP;
					d.expect( d.until( () -> image() != null, 6, () -> d.act( Action.WAIT )), "no prismatic image stepped out against the rat" );
					d.expect( d.until( () -> !rat.isAlive() || rat.HP < hp, 12, () -> d.act( Action.WAIT )), "the prismatic image never hurt the rat" );
					d.expect( d.until( () -> image() == null && Dungeon.hero.buff( PrismaticGuard.class ) != null, 15, () -> d.act( Action.WAIT )),
							"the image never returned to the hero as a prismatic guard" );
				} ).covers( ScrollOfPrismaticImage.class, PrismaticImage.class, PrismaticGuard.class );
	}

	private static PrismaticImage image(){
		for (Mob mob : Dungeon.level.mobs) if (mob instanceof PrismaticImage && mob.isAlive()) return (PrismaticImage) mob;
		return null;
	}

	private static void sleeperOutOfSight( World world, HeroKit kit ){
		for (int cell = 0; cell < world.level().length(); cell++){
			if (world.free( cell ) && !world.level().heroFOV[ cell ] && world.level().distance( cell, world.hero() ) > 8){
				Rat rat = world.mob( new Rat(), cell );
				rat.state = rat.SLEEPING;
				return;
			}
		}
		throw new NoFixture( "no free cell out of the hero's sight" );
	}

	private static void sandals( World world, HeroKit kit ){
		SandalsOfNature sandals = new SandalsOfNature();
		sandals.identify();
		kit.hero().belongings.artifact = sandals;
		sandals.activate( kit.hero() );
	}

	private static int sandalsCharge(){
		return Integer.parseInt( Dungeon.hero.belongings.artifact.status().replaceAll( "\\D", "" ));
	}

	private static int unknown(){
		return Potion.getUnknown().size() + Scroll.getUnknown().size() + Ring.getUnknown().size();
	}

	static int mapped(){
		int cells = 0;
		for (boolean m : Dungeon.level.mapped) if (m) cells++;
		return cells;
	}
}
