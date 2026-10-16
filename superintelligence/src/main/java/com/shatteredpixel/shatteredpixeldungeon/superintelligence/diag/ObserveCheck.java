package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.ObservationEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.policy.ScriptedPolicy;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay.Quickslots;
import com.watabou.utils.Random;
import com.watabou.utils.RandomTrace;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
public class ObserveCheck {
	private static final int CHECKS = 7;
	private static final List< String > failures = new ArrayList<>();
	private static int checksFailed = 0;
	private static void check( Runnable body ){
		int before = failures.size();
		body.run();
		if (failures.size() > before) checksFailed++;
	}
	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-observecheck" ));
		HeadlessServices.disableSaving( true );
		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 200;
		SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
		env.reset( "OBSERVECHECK-A", HeroClass.WARRIOR );
		ScriptedPolicy policy = new ScriptedPolicy( env.mapper(), 11 );
		int[] slot = new int[ 1 ];
		for (int i = 0; i < 14 && env.running(); i++){
			Action action = policy.choose( env, slot );
			env.step( action, slot[ 0 ] );
		}
		ObservationEncoder encoder = new ObservationEncoder( config, env.mapper() );
		check( ObserveCheck::checkTheInstrumentIsLive );
		check( () -> checkEncodingDrawsNothing( encoder ) );
		check( () -> checkEncodingTwiceIsIdentical( encoder ) );
		check( ObserveCheck::checkCapturingQuickslotsDrawsNothing );
		check( ObserveCheck::checkEveryDrawPathIsCounted );
		check( ObserveCheck::checkShufflingIsCounted );
		check( ObserveCheck::checkNoUnauditedPublicRandomMethods );
		if (failures.isEmpty()){
			System.out.println( "[OK]     observation purity: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  observation purity: " + checksFailed
					+ " of " + CHECKS + " checks failed, " + failures.size() + " assertions" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}
	private static void checkTheInstrumentIsLive(){
		RandomTrace.enable();
		RandomTrace.reset();
		Random.Float();
		Random.Int();
		Random.Long();
		if ( RandomTrace.draws() != 3 ){
			fail( "the draw counter did not move after three deliberate draws ("
					+ RandomTrace.summary() + "). Every check below counts draws, so all of them would "
					+ "pass for the wrong reason." );
		}
		RandomTrace.reset();
		RandomTrace.disable();
	}
	private static void checkEncodingDrawsNothing( ObservationEncoder encoder ){
		RandomTrace.enable();
		RandomTrace.reset();
		encoder.encode();
		String measured = RandomTrace.summary();
		long drawn = RandomTrace.draws();
		RandomTrace.reset();
		RandomTrace.disable();
		if ( drawn != 0 ){
			fail( "ObservationEncoder.encode drew " + drawn + " value(s) from the gameplay RNG ("
					+ measured + "). Reading the world must not change it: a draw here "
					+ "offsets the stream from this turn onwards, and no replay made afterwards will match." );
		}
	}
	private static void checkEncodingTwiceIsIdentical( ObservationEncoder encoder ){
		encoder.encode();
		float[] grid = Arrays.copyOf( encoder.grid(), encoder.grid().length );
		float[] inventory = Arrays.copyOf( encoder.inventory(), encoder.inventory().length );
		float[] hero = Arrays.copyOf( encoder.hero(), encoder.hero().length );
		encoder.encode();
		if ( !Arrays.equals( grid, encoder.grid() )){
			fail( "a second encode of an unchanged world produced a different grid" );
		}
		if ( !Arrays.equals( inventory, encoder.inventory() )){
			fail( "a second encode of an unchanged world produced a different inventory vector" );
		}
		if ( !Arrays.equals( hero, encoder.hero() )){
			fail( "a second encode of an unchanged world produced a different hero vector" );
		}
	}
	private static void checkCapturingQuickslotsDrawsNothing(){
		RandomTrace.enable();
		RandomTrace.reset();
		String captured = Quickslots.capture();
		String measured = RandomTrace.summary();
		long drawn = RandomTrace.draws();
		RandomTrace.reset();
		RandomTrace.disable();
		if ( drawn != 0 ){
			fail( "Quickslots.capture drew " + drawn + " value(s) from the gameplay RNG ("
					+ measured + "). Captured bindings are written into the recording, so a "
					+ "draw here both offsets the stream and corrupts what is stored." );
		}
		if ( captured == null ){
			fail( "Quickslots.capture returned null" );
		}
	}
	private static void fail( String message ){
		failures.add( message );
	}
	private static void checkEveryDrawPathIsCounted(){
		Object[][] paths = {
			{ "Float()",                    (Runnable) Random::Float },
			{ "Float(false)",               (Runnable) () -> Random.Float( false ) },
			{ "Float(10)",                  (Runnable) () -> Random.Float( 10 ) },
			{ "Float(1, 2)",                (Runnable) () -> Random.Float( 1, 2 ) },
			{ "Float(1f)",                  (Runnable) () -> Random.Float( 1f ) },
			{ "NormalFloat(1, 2)",          (Runnable) () -> Random.NormalFloat( 1, 2 ) },
			{ "Int()",                      (Runnable) Random::Int },
			{ "Int(false)",                 (Runnable) () -> Random.Int( false ) },
			{ "Int(10)",                    (Runnable) () -> Random.Int( 10 ) },
			{ "Int(10, false)",             (Runnable) () -> Random.Int( 10, false ) },
			{ "Int(1, 2)",                  (Runnable) () -> Random.Int( 1, 2 ) },
			{ "IntRange(1, 2)",              (Runnable) () -> Random.IntRange( 1, 2 ) },
			{ "NormalIntRange(1, 2)",        (Runnable) () -> Random.NormalIntRange( 1, 2 ) },
			{ "InvNormalIntRange(1, 2)",    (Runnable) () -> Random.InvNormalIntRange( 1, 2 ) },
{ "Long()",                     (Runnable) Random::Long },
			{ "Long(false)",                (Runnable) () -> Random.Long( false ) },
			{ "Long(10)",                   (Runnable) () -> Random.Long( 10 ) },
			{ "Long(7L)",                   (Runnable) () -> Random.Long( 7L ) },
			{ "oneOf(Object[])",            (Runnable) () -> Random.oneOf( new Object[]{ 1, 2 } ) },
			{ "chances(float[])",           (Runnable) () -> Random.chances( new float[]{ 1, 0 } ) },
			{ "element(list)",              (Runnable) () -> Random.element( Arrays.asList( 1, 2, 3 ) ) },
	};
		List< String > silent = new ArrayList<>();
		for (Object[] path : paths){
			RandomTrace.enable();
			RandomTrace.reset();
			((Runnable) path[ 1 ]).run();
			long drawn = RandomTrace.draws();
			RandomTrace.reset();
			RandomTrace.disable();
			if ( drawn == 0 ) silent.add( (String) path[ 0 ] );
		}
		if (!silent.isEmpty()){
			fail( "these Random draw paths advanced the generator without being counted: "
					+ String.join( ", ", silent ) + ". Anything inferred from the RNG trace about stream "
					+ "parity is unsound while a draw path is invisible to it - that is how a headless run "
					+ "and a rendered run were reported as agreeing at every step while sixteen values apart." );
		}
	}
	private static void checkShufflingIsCounted(){
		List< Integer > list = new ArrayList<>();
		for (int i = 0; i < 8; i++) list.add( i );
		RandomTrace.enable();
		RandomTrace.reset();
		Random.shuffle( list );
		long drawn = RandomTrace.draws();
		RandomTrace.reset();
		RandomTrace.disable();
		if ( drawn == 0 ){
			fail( "Random.shuffle(List) advanced the generator without being counted. Level generation "
					+ "shuffles with it, so this is how a gameplay stream gets offset before the first "
					+ "recorded step, with nothing in the trace to show it." );
		}
		List< Integer > same = new ArrayList<>( list );
		RandomTrace.enable();
		RandomTrace.reset();
		Random.shuffle( same );
		RandomTrace.reset();
		RandomTrace.disable();
		if ( !new java.util.HashSet<>( list ).equals( new java.util.HashSet<>( same ) )){
			fail( "Random.shuffle(List) did not permute the list" );
		}
		int[] array = { 0, 1, 2, 3, 4, 5, 6, 7 };
		RandomTrace.enable();
		RandomTrace.reset();
		Random.shuffle( array );
		long arrayDrawn = RandomTrace.draws();
		RandomTrace.reset();
		RandomTrace.disable();
		if ( arrayDrawn == 0 ){
			fail( "Random.shuffle(int[]) advanced the generator without being counted" );
		}
	}
	private static final java.util.Set< String > AUDITED = new java.util.LinkedHashSet<>( Arrays.asList(
			"Float()", "Float(boolean)", "Float(int)", "Float(float)", "Float(float,float)",
			"NormalFloat(float,float)",
			"Int()", "Int(boolean)", "Int(int)", "Int(int,boolean)", "Int(int,int)",
			"IntRange(int,int)", "NormalIntRange(int,int)", "InvNormalIntRange(int,int)",
			"Long()", "Long(boolean)", "Long(int)", "Long(long)",
			"chances([F)", "chances(java.util.HashMap)",
			"element(java.util.List)", "element(java.util.Collection)",
			"element([Ljava.lang.Object;)", "element([Ljava.lang.Object;,int)",
			"index(java.util.Collection)", "oneOf([Ljava.lang.Object;)",
			"shuffle([I)", "shuffle(java.util.List)", "shuffle([Ljava.lang.Object;)", "shuffle([Ljava.lang.Object;,[Ljava.lang.Object;)",
			"seed()", "reseedBase(long)", "resetGenerators()",
			"pushGenerator()", "pushGenerator(long)", "popGenerator()"
	) );
	private static void checkNoUnauditedPublicRandomMethods(){
		List< String > declared = new ArrayList<>();
		for (java.lang.reflect.Method method : Random.class.getDeclaredMethods()){
			if ( !java.lang.reflect.Modifier.isPublic( method.getModifiers() )) continue;
			if ( !java.lang.reflect.Modifier.isStatic( method.getModifiers() )) continue;
			if ( method.isSynthetic() ) continue;

			StringBuilder signature = new StringBuilder( method.getName() ).append( '(' );
			Class< ? >[] parameters = method.getParameterTypes();
			for (int i = 0; i < parameters.length; i++){
				if (i > 0 ) signature.append( ',' );
				signature.append( parameters[ i ].getName() );
			}
			declared.add( signature.append( ')' ).toString() );
		}

		List< String > undeclared = new ArrayList<>();
		for (String signature : declared) if ( !AUDITED.contains( signature )) undeclared.add( signature );

		if (undeclared.isEmpty()){
			System.out.println( "  all " + declared.size() + " public static Random methods are audited: "
					+ AUDITED.size() + " in the set, no 18th draw path can appear unnoticed" );
			return;
		}

		fail( "these public static Random methods are not in the audited set: "
				+ String.join( ", ", undeclared ) + ". The draw-path check above enumerates what it knows, "
				+ "so a path added later is invisible to it - which is precisely how four presentation draws "
				+ "spent the gameplay stream while every count agreed. Add the method to the audited set and "
				+ "to the draw-path table, or the next one repeats it." );
	}
}
