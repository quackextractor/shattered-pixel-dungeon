package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.CrystalGuardian;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.CrystalSpire;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DM300;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Eye;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Ghoul;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GnollGeomancer;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.GnollGuard;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Golem;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Goo;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Necromancer;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Pylon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.RipperDemon;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.SpectralNecromancer;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.PrismaticImage;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.RatKing;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.quest.vault.VaultBossElemental;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfWarding;
import com.shatteredpixel.shatteredpixeldungeon.mechanics.Ballistica;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CrystalGuardianSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CrystalSpireSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.DM300Sprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.EyeSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GhoulSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GnollGeomancerSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GnollGuardSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GolemSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.GooSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.NecromancerSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.PrismaticSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.PylonSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.RatKingSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.RipperSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.SpectralNecromancerSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.VaultBossElementalSprite;
import com.shatteredpixel.shatteredpixeldungeon.sprites.WardSprite;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.watabou.utils.PathFinder;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GateReport.fail;

final class SpriteCastCases {

	static final int CHECKS = 5;

	private SpriteCastCases(){}

	static void run(){
		checkEveryCastSpriteMethodRuns();
		checkAnEyeChargesAndFires();
		checkGooPumpsAndAttacks();
		checkDM300Slams();
		checkAGhoulCrumples();
	}

	private static final class Cast {
		final Supplier< Mob > mob;
		final Class< ? > sprite;
		final Consumer< Mob > exercise;

		Cast( Supplier< Mob > mob, Class< ? > sprite, Consumer< Mob > exercise ){
			this.mob = mob;
			this.sprite = sprite;
			this.exercise = exercise;
		}
	}

	private static Cast[] casts(){
		return new Cast[]{
			new Cast( Eye::new, EyeSprite.class, m -> ((EyeSprite) m.sprite).charge( Dungeon.hero.pos ) ),
			new Cast( DM300::new, DM300Sprite.class, m -> {
				((DM300Sprite) m.sprite).updateChargeState( true );
				((DM300Sprite) m.sprite).charge();
				((DM300Sprite) m.sprite).updateChargeState( false );
			} ),
			new Cast( Goo::new, GooSprite.class, m -> {
				((GooSprite) m.sprite).pumpUp( 2 );
				((GooSprite) m.sprite).triggerEmitters();
				((GooSprite) m.sprite).spray( true );
				((GooSprite) m.sprite).spray( false );
			} ),
			new Cast( Ghoul::new, GhoulSprite.class, m -> ((GhoulSprite) m.sprite).crumple() ),
			new Cast( CrystalGuardian::new, CrystalGuardianSprite.class, m -> {
				((CrystalGuardianSprite) m.sprite).crumple();
				((CrystalGuardianSprite) m.sprite).endCrumple();
			} ),
			new Cast( CrystalSpire::new, CrystalSpireSprite.class,
					m -> ((CrystalSpireSprite) m.sprite).updateIdle() ),
			new Cast( GnollGeomancer::new, GnollGeomancerSprite.class, m -> {
				((GnollGeomancerSprite) m.sprite).setupArmor();
				((GnollGeomancerSprite) m.sprite).loseArmor();
			} ),
			new Cast( GnollGuard::new, GnollGuardSprite.class, m -> {
				((GnollGuardSprite) m.sprite).setupArmor();
				((GnollGuardSprite) m.sprite).loseArmor();
			} ),
			new Cast( Golem::new, GolemSprite.class, m -> {
				((GolemSprite) m.sprite).teleParticles( true );
				((GolemSprite) m.sprite).teleParticles( false );
			} ),
			new Cast( Necromancer::new, NecromancerSprite.class, m -> {
				((NecromancerSprite) m.sprite).finishSummoning();
				((NecromancerSprite) m.sprite).cancelSummoning();
			} ),
			new Cast( SpectralNecromancer::new, SpectralNecromancerSprite.class, m -> {
				((SpectralNecromancerSprite) m.sprite).finishSummoning();
				((SpectralNecromancerSprite) m.sprite).cancelSummoning();
			} ),
			new Cast( PrismaticImage::new, PrismaticSprite.class,
					m -> ((PrismaticSprite) m.sprite).updateArmor( 2 ) ),
			new Cast( RatKing::new, RatKingSprite.class, m -> ((RatKingSprite) m.sprite).resetAnims() ),
			new Cast( Pylon::new, PylonSprite.class, m -> ((PylonSprite) m.sprite).activate() ),
			new Cast( VaultBossElemental::new, VaultBossElementalSprite.class,
					m -> ((VaultBossElementalSprite) m.sprite).updateForm() ),
			new Cast( RipperDemon::new, RipperSprite.class,
					m -> ((RipperSprite) m.sprite).leapPrep( Dungeon.hero.pos ) ),
			new Cast( WandOfWarding.Ward::new, WardSprite.class, m -> ((WardSprite) m.sprite).updateTier( 2 ) )
		};
	}

	private static void checkEveryCastSpriteMethodRuns(){
		MobSpriteCheck.freshRun( "SPRITECAST-A" );
		StringBuilder broken = new StringBuilder();
		Cast[] casts = casts();
		for (Cast cast : casts){
			Mob mob = null;
			try {
				mob = cast.mob.get();
				spawn( mob, freeNeighbour( Dungeon.hero.pos ) );
				if (!cast.sprite.isInstance( mob.sprite )){
					broken.append( "\n          " ).append( mob.getClass().getSimpleName() ).append( " got a " )
							.append( name( mob.sprite ) ).append( ", cast to " ).append( cast.sprite.getSimpleName() );
					continue;
				}
				cast.exercise.accept( mob );
			} catch (Throwable t){
				broken.append( "\n          " )
						.append( mob == null ? cast.sprite.getSimpleName() : mob.getClass().getSimpleName() )
						.append( " threw " ).append( t ).append( " at " ).append( topFrame( t ) );
			} finally {
				if (mob != null) remove( mob );
			}
		}
		if (broken.length() > 0){
			fail( "mobs whose code casts their sprite to a concrete sprite class must get that class headlessly,"
					+ " or the first cast is a ClassCastException that ends the episode:" + broken );
			return;
		}
		System.out.println( "  " + casts.length + " mobs whose code casts their sprite get the class they cast to,"
				+ " and every cast method runs headlessly" );
	}

	private static void checkAnEyeChargesAndFires(){
		SPDEnv env = MobSpriteCheck.freshRun( "SPRITECAST-B" );
		int cell = lineCell( 2, 4 );
		if (cell < 0){
			fail( "no cell 2 to 4 from the hero has a clear line to him, so the Eye's beam was never reached." );
			return;
		}
		Eye eye = new Eye();
		hunt( eye, cell );

		boolean charged = false, fired = false;
		try {
			for (int i = 0; i < 30 && env.running() && !fired; i++){
				env.step( Action.WAIT, 0 );
				if (eye.beamCharged) charged = true;
				else if (charged && intField( eye, "beamCooldown" ) > 0) fired = true;
			}
		} catch (Throwable t){
			fail( "an Eye hunting the hero threw " + t + " at " + topFrame( t ) + ". Eye.doAttack casts its sprite"
					+ " to EyeSprite before it charges the beam." );
			return;
		}
		if (!fired || !env.running()){
			fail( "an Eye hunting the hero for 30 turns charged " + charged + ", fired " + fired + ", running "
					+ env.running() + ", stall cause " + env.stallCause() + "." );
			return;
		}
		System.out.println( "  an Eye charges its beam and fires it, and its turn completes headlessly" );
	}

	private static void checkGooPumpsAndAttacks(){
		SPDEnv env = MobSpriteCheck.freshRun( "SPRITECAST-C" );
		Goo goo = new Goo();
		hunt( goo, freeNeighbour( Dungeon.hero.pos ) );

		boolean pumped = false, released = false;
		try {
			setIntField( goo, "pumpedUp", 1 );
			for (int i = 0; i < 20 && env.running() && !released; i++){
				env.step( Action.WAIT, 0 );
				int pump = intField( goo, "pumpedUp" );
				if (pump >= 2) pumped = true;
				else if (pumped && pump == 0) released = true;
			}
		} catch (Throwable t){
			fail( "a pumping Goo threw " + t + " at " + topFrame( t ) + ". Goo.doAttack casts its sprite to"
					+ " GooSprite, and the pumped attack completes from GooSprite.onComplete." );
			return;
		}
		if (!released || !env.running()){
			fail( "a Goo adjacent to the hero pumped " + pumped + " and released " + released + " in 20 turns,"
					+ " running " + env.running() + ", stall cause " + env.stallCause() + ". The pumped attack"
					+ " finishes only when its animation completes." );
			return;
		}
		System.out.println( "  a Goo pumps up and releases its pumped attack, and its turn completes headlessly" );
	}

	private static void checkDM300Slams(){
		SPDEnv env = MobSpriteCheck.freshRun( "SPRITECAST-D" );
		DM300 dm = new DM300();
		hunt( dm, freeNeighbour( Dungeon.hero.pos ) );

		boolean slammed = false;
		try {
			for (int i = 0; i < 12 && env.running() && !slammed; i++){
				setIntField( dm, "turnsSinceLastAbility", 100 );
				setIntField( dm, "abilityCooldown", 0 );
				dm.sprite.visible = true;
				env.step( Action.WAIT, 0 );
				slammed = intField( dm, "lastAbility" ) == 2 && intField( dm, "turnsSinceLastAbility" ) <= 1;
			}
		} catch (Throwable t){
			fail( "DM-300 using its abilities threw " + t + " at " + topFrame( t ) + ". DM300.act casts its"
					+ " sprite to DM300Sprite to slam, and the rocks fall from DM300Sprite.onComplete." );
			return;
		}
		if (!slammed || !env.running()){
			fail( "DM-300 with its ability always ready did not slam in 12 turns: slammed " + slammed
					+ ", running " + env.running() + ", stall cause " + env.stallCause() + "." );
			return;
		}
		System.out.println( "  DM-300 slams and its turn completes headlessly" );
	}

	private static void checkAGhoulCrumples(){
		SPDEnv env = MobSpriteCheck.freshRun( "SPRITECAST-E" );
		Ghoul downed = new Ghoul();
		Ghoul host = new Ghoul();
		hunt( downed, freeNeighbour( Dungeon.hero.pos ) );
		hunt( host, freeNeighbour( downed.pos ) );

		try {
			downed.die( Dungeon.hero );
			if (Dungeon.level.mobs.contains( downed ) || !downed.isAlive()){
				fail( "a Ghoul killed beside another was not life-linked, so its crumple was never reached." );
				return;
			}
			for (int i = 0; i < 12 && env.running(); i++) env.step( Action.WAIT, 0 );
		} catch (Throwable t){
			fail( "a Ghoul going down beside another threw " + t + " at " + topFrame( t ) + ". Ghoul.die casts"
					+ " its sprite to GhoulSprite to crumple it." );
			return;
		}
		if (!env.running()){
			fail( "the episode ended after a Ghoul crumpled: " + env.endReason() + "." );
			return;
		}
		System.out.println( "  a Ghoul crumples beside its host and the episode runs on headlessly" );
	}

	private static void hunt( Mob mob, int cell ){
		spawn( mob, cell );
		mob.aggro( Dungeon.hero );
		mob.state = mob.HUNTING;
		Dungeon.hero.HP = Dungeon.hero.HT = 100000;
	}

	private static void spawn( Mob mob, int cell ){
		mob.pos = cell;
		GameScene.add( mob );
	}

	private static void remove( Mob mob ){
		Actor.remove( mob );
		Dungeon.level.mobs.remove( mob );
	}

	private static int freeNeighbour( int around ){
		for (int d : PathFinder.NEIGHBOURS8){
			int c = around + d;
			if (Dungeon.level.passable[ c ] && !Dungeon.level.pit[ c ] && Actor.findChar( c ) == null) return c;
		}
		throw new IllegalStateException( "no free cell beside " + around );
	}

	private static int lineCell( int min, int max ){
		int hero = Dungeon.hero.pos;
		for (int c = 0; c < Dungeon.level.length(); c++){
			int d = Dungeon.level.distance( c, hero );
			if (d >= min && d <= max && Dungeon.level.passable[ c ] && Dungeon.level.heroFOV[ c ]
					&& Actor.findChar( c ) == null
					&& new Ballistica( c, hero, Ballistica.STOP_SOLID ).subPath( 1, d ).contains( hero )) return c;
		}
		return -1;
	}

	private static int intField( Object target, String name ) throws ReflectiveOperationException {
		return field( target, name ).getInt( target );
	}

	private static void setIntField( Object target, String name, int value ) throws ReflectiveOperationException {
		field( target, name ).setInt( target, value );
	}

	private static Field field( Object target, String name ) throws NoSuchFieldException {
		for (Class< ? > c = target.getClass(); c != null; c = c.getSuperclass()){
			try {
				Field f = c.getDeclaredField( name );
				f.setAccessible( true );
				return f;
			} catch (NoSuchFieldException ignored){
			}
		}
		throw new NoSuchFieldException( name );
	}

	private static String name( Object o ){
		return o == null ? "null sprite" : o.getClass().getSimpleName();
	}

	private static String topFrame( Throwable t ){
		return MobSpriteCheck.topFrame( t );
	}
}
