package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Amok;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Blindness;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Hunger;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Regeneration;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.TimeStasis;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Ghost;
import com.shatteredpixel.shatteredpixeldungeon.items.EquipableItem;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.Torch;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.AlchemistsToolkit;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.Artifact;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.CapeOfThorns;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.ChaliceOfBlood;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.DriedRose;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.HornOfPlenty;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.LloydsBeacon;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.MasterThievesArmband;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.SandalsOfNature;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.SkeletonKey;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.TalismanOfForesight;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.TimekeepersHourglass;
import com.shatteredpixel.shatteredpixeldungeon.items.artifacts.UnstableSpellbook;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.WornShortsword;
import com.shatteredpixel.shatteredpixeldungeon.levels.Terrain;
import com.shatteredpixel.shatteredpixeldungeon.plants.Firebloom;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sorrowmoss;
import com.shatteredpixel.shatteredpixeldungeon.plants.Sungrass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.ActionMapper;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.AlchemyAction;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.WindowBridge;
import com.watabou.utils.Bundle;
import com.watabou.utils.PathFinder;

import java.util.ArrayList;
import java.util.List;

final class ArtifactScenarios {

	private ArtifactScenarios(){}

	private static final int WAITS = 8;

	private static final int ATTACKS = 12;

	private static final int PRICKS = 5;

	private static final int REGEN_TURNS = 25;

	private static final int LEVEL_TOP = 10;

	private static final float STASIS_TURNS = 6f;

	private static final int FOE_HP = 300;

	private static final int RAT_HP = 100;

	private static final float HUNGER = Hunger.HUNGRY + 110;

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( toolkit() );
		all.add( cape() );
		all.add( chalice() );
		all.add( rose() );
		all.add( horn() );
		all.add( beacon() );
		all.add( armband() );
		all.add( sandals() );
		all.add( skeletonKey() );
		all.add( talisman() );
		all.add( hourglass() );
		all.add( timeStasis() );
		all.add( spellbook() );
		return all;
	}

	private static Scenario toolkit(){
		return new Scenario( "artifact.AlchemistsToolkit", "brew from an equipped toolkit with no pot in reach", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.emptyPack().sturdy().energy( 20 );
					kit.give( new Torch() );
					kit.give( new Sungrass.Seed() );
					kit.give( new Firebloom.Seed() );
					kit.give( new Sorrowmoss.Seed() );
					kit.give( new AlchemistsToolkit() ).upgrade( LEVEL_TOP );
				},
				d -> {
					d.require( AlchemyAction.adjacentPot( Dungeon.hero, ActionMapper.TARGET_COUNT ) < 0,
							"a pot stands beside the hero, so the toolkit proves nothing" );
					AlchemistsToolkit toolkit = equip( d, AlchemistsToolkit.class );
					d.act( Action.WAIT );
					d.require( toolkit.isWarm(), "the toolkit never finished warming up" );
					int crafted = Statistics.itemsCrafted;
					d.brewRecipe( Potion.SeedToPotion.class );
					d.expect( Statistics.itemsCrafted == crafted + 1,
							"brewing with the toolkit moved itemsCrafted by " + (Statistics.itemsCrafted - crafted) );
					d.expect( carries( Potion.class ), "the toolkit brewed no potion" );
				} ).covers( AlchemistsToolkit.class );
	}

	private static Scenario cape(){
		return new Scenario( "artifact.CapeOfThorns", "a charged cape reflects an attacker's blow back onto it", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					CapeOfThorns cape = kit.give( new CapeOfThorns() );
					cape.resetForTrinity( LEVEL_TOP );
					hostile( world, FOE_HP );
				},
				d -> {
					Artifact cape = equip( d, CapeOfThorns.class );
					d.require( cape.status() != null, "the cape shows no charge" );
					String charged = cape.status();
					Mob foe = PotionScenarios.rat();
					int hp = foe.HP;
					d.expect( d.until( () -> !foe.isAlive() || foe.HP < hp, ATTACKS, () -> d.act( Action.WAIT )),
							"the cape reflected nothing: the rat stayed at " + foe.HP + " of " + hp );
					d.expect( !charged.equals( cape.status() ), "the cape kept its charge of " + charged );
				} ).covers( CapeOfThorns.class );
	}

	private static Scenario chalice(){
		return new Scenario( "artifact.ChaliceOfBlood", "pricking the chalice costs health and speeds regeneration", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.health( 600, 1000 );
					kit.buff( Regeneration.class );
					kit.give( new ChaliceOfBlood() );
				},
				d -> {
					equip( d, ChaliceOfBlood.class );
					int slow = healed( d, REGEN_TURNS );
					int hp = Dungeon.hero.HP;
					for (int i = 0; i < PRICKS; i++){
						d.itemMenu( ChaliceOfBlood.class, ChaliceOfBlood.AC_PRICK );
						d.require( d.env().mode() == EnvMode.MENU,
								"the prick offered no confirmation: " + d.env().mode() + " " + WindowBridge.describe() );
						d.menu( 0 );
					}
					d.expect( Dungeon.hero.HP < hp, "the prick cost no health: " + Dungeon.hero.HP + " of " + hp );
					int fast = healed( d, REGEN_TURNS );
					d.expect( fast > slow, "the pricked chalice healed " + fast + " in " + REGEN_TURNS + " turns, the untouched one " + slow );
				} ).covers( ChaliceOfBlood.class );
	}

	private static Scenario rose(){
		return new Scenario( "artifact.DriedRose", "summon the ghost hero, outfit it, and let it fight", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					questDone();
					kit.give( new DriedRose() ).resetForTrinity( LEVEL_TOP );
					kit.give( new WornShortsword() );
					Rat rat = world.mob( new Rat(), world.adjacentFree() );
					rat.HP = rat.HT = RAT_HP;
				},
				d -> {
					equip( d, DriedRose.class );
					d.use( DriedRose.class );
					DriedRose.GhostHero ghost = ghost();
					d.expect( ghost != null, "no ghost hero appeared" );
					d.require( ghost != null, "the rose summoned nothing" );
					d.itemMenu( DriedRose.class, DriedRose.AC_OUTFIT );
					d.require( d.env().mode() == EnvMode.MENU,
							"the outfit window did not open: " + d.env().mode() + " " + WindowBridge.describe() );
					d.menu( 0 );
					d.require( d.env().mode() == EnvMode.INVENTORY,
							"the outfit button opened no picker: " + d.env().mode() + " " + WindowBridge.describe() );
					d.pick( WornShortsword.class );
					if (d.env().mode() == EnvMode.MENU) d.cancel();
					d.expect( ghost.weapon() instanceof WornShortsword, "the ghost took no weapon" );
					Mob rat = PotionScenarios.rat();
					int hp = rat.HP;
					d.expect( d.until( () -> !rat.isAlive() || rat.HP < hp, WAITS, () -> d.act( Action.WAIT )),
							"the ghost hero left the rat at " + rat.HP + " of " + hp );
				} ).covers( DriedRose.class, DriedRose.GhostHero.class );
	}

	private static Scenario horn(){
		return new Scenario( "artifact.HornOfPlenty", "snack from a charged horn to lower hunger", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					Buff.affect( kit.hero(), Hunger.class ).affectHunger( -HUNGER );
					kit.give( new HornOfPlenty() ).resetForTrinity( 5 );
				},
				d -> {
					equip( d, HornOfPlenty.class );
					Hunger hunger = Dungeon.hero.buff( Hunger.class );
					d.require( hunger != null && hunger.hunger() > 0, "the hero is not hungry before the snack" );
					int before = hunger.hunger();
					d.use( HornOfPlenty.class );
					d.expect( hunger.hunger() < before, "the snack left hunger at " + hunger.hunger() + " of " + before );
				} ).covers( HornOfPlenty.class );
	}

	private static Scenario beacon(){
		return new Scenario( "artifact.LloydsBeacon", "set the beacon, walk away, and return to it", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new LloydsBeacon() );
				},
				d -> {
					d.itemMenu( LloydsBeacon.class, EquipableItem.AC_EQUIP );
					d.itemMenu( LloydsBeacon.class, LloydsBeacon.AC_SET );
					int marked = Dungeon.hero.pos;
					d.walkTo( d.world().cellAtRange( 3, false ), 4 );
					d.require( Dungeon.hero.pos != marked, "the hero never left the beacon" );
					d.itemMenu( LloydsBeacon.class, LloydsBeacon.AC_RETURN );
					d.expect( Dungeon.hero.pos == marked, "returning left the hero at " + Dungeon.hero.pos + ", not " + marked );
				} ).covers( LloydsBeacon.class );
	}

	private static Scenario armband(){
		return new Scenario( "artifact.MasterThievesArmband", "steal from a rat to blind and cripple it", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new MasterThievesArmband() ).resetForTrinity( 5 );
					hostile( world, FOE_HP );
				},
				d -> {
					Artifact armband = equip( d, MasterThievesArmband.class );
					String charged = armband.status();
					Mob rat = PotionScenarios.rat();
					d.use( MasterThievesArmband.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the steal asked for no target: " + d.env().mode() );
					d.step( Action.USE, d.direction( rat.pos ));
					d.expect( rat.buff( Blindness.class ) != null && rat.buff( Cripple.class ) != null,
							"the steal left the rat neither blind nor crippled" );
					d.expect( !java.util.Objects.equals( charged, armband.status() ), "the steal spent no charge" );
				} ).covers( MasterThievesArmband.class );
	}

	private static Scenario sandals(){
		return new Scenario( "artifact.SandalsOfNature", "root a seed into the floor through the sandals", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					SandalsOfNature sandals = kit.give( new SandalsOfNature() );
					sandals.charge( kit.hero(), 100 );
					sandals.curSeedEffect = Firebloom.Seed.class;
					Rat rat = world.mob( new Rat(), world.adjacentFree() );
					rat.HP = rat.HT = RAT_HP;
				},
				d -> {
					SandalsOfNature sandals = equip( d, SandalsOfNature.class );
					Mob rat = PotionScenarios.rat();
					int cell = rat.pos;
					int hp = rat.HP;
					String charged = sandals.status();
					d.require( sandals.actions( Dungeon.hero ).contains( SandalsOfNature.AC_ROOT ),
							"the sandals offer no root: " + sandals.status() );
					d.use( SandalsOfNature.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the root asked for no cell: " + d.env().mode() );
					d.step( Action.USE, d.direction( cell ));
					d.expect( fire( cell ) > 0, "the firebloom root lit nothing at the aimed cell" );
					d.expect( d.until( () -> !rat.isAlive() || rat.HP < hp, WAITS, () -> d.act( Action.WAIT )),
							"the rooted rat took no harm: " + rat.HP + " of " + hp );
					d.expect( !java.util.Objects.equals( charged, sandals.status() ), "the root spent no charge" );
				} ).covers( SandalsOfNature.class );
	}

	private static Scenario skeletonKey(){
		return new Scenario( "artifact.SkeletonKey", "insert the key into a locked door", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new SkeletonKey() );
					int cell = world.adjacentFreeStraight();
					world.terrain( cell, Terrain.LOCKED_DOOR );
					Dungeon.level.mapped[ cell ] = true;
				},
				d -> {
					SkeletonKey key = equip( d, SkeletonKey.class );
					int cell = door();
					String charged = key.status();
					d.use( SkeletonKey.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the key asked for no door: " + d.env().mode() );
					d.step( Action.USE, d.direction( cell ));
					d.expect( Dungeon.level.map[ cell ] == Terrain.DOOR,
							"the key left the door at terrain " + Dungeon.level.map[ cell ] );
					d.expect( !java.util.Objects.equals( charged, key.status() ), "the key spent no charge" );
				} ).covers( SkeletonKey.class );
	}

	private static Scenario talisman(){
		return new Scenario( "artifact.TalismanOfForesight", "scry a far cell outside the hero's surroundings onto the map", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					TalismanOfForesight talisman = kit.give( new TalismanOfForesight() );
					talisman.charge( kit.hero(), 20 );
				},
				d -> {
					equip( d, TalismanOfForesight.class );
					int cell = d.env().mapper().targetCell( ActionMapper.FAR_TARGET );
					d.fixture( cell != Dungeon.hero.pos && !Dungeon.level.adjacent( Dungeon.hero.pos, cell )
							&& Dungeon.level.passable[ cell ], "no free cell 3 to 5 tiles from the hero in a clear line" );
					d.use( TalismanOfForesight.class );
					d.require( d.env().mode() == EnvMode.TARGETING, "the scry asked for no cell: " + d.env().mode() );
					d.require( d.env().targetMask()[ ActionMapper.FAR_TARGET ] > 0.5f,
							"the far seat is masked while the scry aims" );
					darken( cell );
					d.require( !Dungeon.level.mapped[ cell ] && !Dungeon.level.visited[ cell ],
							"the fixture left cell " + cell + " on the map before the scry" );
					d.step( Action.USE, ActionMapper.FAR_TARGET );
					d.expect( Dungeon.level.mapped[ cell ], "the scry left cell " + cell + " unmapped" );
				} ).covers( TalismanOfForesight.class );
	}

	private static Scenario hourglass(){
		return new Scenario( "artifact.TimekeepersHourglass", "stasis from the hourglass, then a time stasis", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.give( new TimekeepersHourglass() );
				},
				d -> {
					TimekeepersHourglass hourglass = equip( d, TimekeepersHourglass.class );
					String charged = hourglass.status();
					d.use( TimekeepersHourglass.class );
					d.require( d.env().mode() == EnvMode.MENU, "the hourglass offered no choice: " + d.env().mode() );
					float frozen = cost( d, () -> d.menu( 0 ));
					d.expect( frozen >= 5, "the stasis froze the hero for only " + frozen + " turns" );
					d.expect( Dungeon.hero.buff( TimekeepersHourglass.timeStasis.class ) == null,
							"the hourglass stasis never ended" );
					d.expect( !java.util.Objects.equals( charged, hourglass.status() ), "the stasis spent no charge" );
				} ).covers( TimekeepersHourglass.class );
	}

	private static Scenario timeStasis(){
		return new Scenario( "artifact.TimeStasis", "a time stasis freezes the hero for its duration", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy();
					kit.buff( TimeStasis.class, STASIS_TURNS );
				},
				d -> {
					d.require( Dungeon.hero.buff( TimeStasis.class ) != null, "the time stasis was not applied" );
					float frozen = cost( d, () -> d.act( Action.WAIT ));
					float plain = cost( d, () -> d.act( Action.WAIT ));
					d.expect( frozen > plain, "the stasis cost " + frozen + " turns where a plain turn costs " + plain );
					d.expect( Dungeon.hero.buff( TimeStasis.class ) == null, "the time stasis never expired" );
				} ).covers( TimeStasis.class );
	}

	private static Scenario spellbook(){
		return new Scenario( "artifact.UnstableSpellbook", "read a scroll out of the spellbook", HeroClass.WARRIOR,
				( world, kit ) -> {
					world.clearMobs();
					kit.sturdy().strength( 30 );
					kit.give( new UnstableSpellbook() );
					hostile( world, RAT_HP );
				},
				d -> {
					Artifact book = equip( d, UnstableSpellbook.class );
					Mob rat = PotionScenarios.rat();
					String charged = book.status();
					d.use( UnstableSpellbook.class );
					d.expect( rat.buff( Amok.class ) != null, "the scroll read from the book left the rat calm" );
					d.expect( !java.util.Objects.equals( charged, book.status() ), "the read spent no charge" );
				} ).covers( UnstableSpellbook.class ).seeds( "SCN-D" );
	}

	private static < T extends Artifact > T equip( Driver d, Class< T > type ){
		d.use( type );
		T artifact = Dungeon.hero.belongings.getItem( type );
		d.require( artifact != null && artifact.isEquipped( Dungeon.hero ),
				type.getSimpleName() + " was not equipped: " + d.env().mode() + " " + WindowBridge.describe() );
		return artifact;
	}

	private static Mob hostile( World world, int hp ){
		Rat rat = world.hunting( new Rat(), world.adjacentFree() );
		rat.HP = rat.HT = hp;
		return rat;
	}

	private static int healed( Driver d, int turns ){
		int hp = Dungeon.hero.HP;
		for (int i = 0; i < turns; i++) d.act( Action.WAIT );
		return Dungeon.hero.HP - hp;
	}

	private static float cost( Driver d, Runnable action ){
		float now = Actor.now();
		action.run();
		return Actor.now() - now;
	}

	private static int fire( int cell ){
		com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire blaze =
				(com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire)
						Dungeon.level.blobs.get( com.shatteredpixel.shatteredpixeldungeon.actors.blobs.Fire.class );
		return blaze == null ? 0 : blaze.cur[ cell ];
	}

	private static boolean carries( Class< ? > type ){
		for (Item item : Dungeon.hero.belongings.backpack){
			if (type.isInstance( item )) return true;
		}
		return false;
	}

	private static void questDone(){
		Bundle node = new Bundle();
		node.put( "spawned", true );
		node.put( "type", 1 );
		node.put( "given", true );
		node.put( "processed", true );
		node.put( "depth", Dungeon.depth );
		Bundle quests = new Bundle();
		quests.put( "sadGhost", node );
		Ghost.Quest.restoreFromBundle( quests );
	}

	private static DriedRose.GhostHero ghost(){
		for (Mob mob : Dungeon.level.mobs){
			if (mob instanceof DriedRose.GhostHero) return (DriedRose.GhostHero) mob;
		}
		return null;
	}

	private static int door(){
		for (int offset : PathFinder.NEIGHBOURS8){
			int cell = Dungeon.hero.pos + offset;
			if (cell >= 0 && cell < Dungeon.level.length() && Dungeon.level.map[ cell ] == Terrain.LOCKED_DOOR) return cell;
		}
		throw new Driver.Stop( "no locked door beside the hero" );
	}

	private static void darken( int cell ){
		for (int other = 0; other < Dungeon.level.length(); other++){
			if (Dungeon.level.distance( Dungeon.hero.pos, other ) < 2) continue;
			if (Dungeon.level.distance( Dungeon.hero.pos, other ) > 5) continue;
			Dungeon.level.mapped[ other ] = false;
			Dungeon.level.visited[ other ] = false;
		}
		Dungeon.level.mapped[ cell ] = false;
		Dungeon.level.visited[ cell ] = false;
	}
}
