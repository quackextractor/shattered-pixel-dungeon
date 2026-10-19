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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
public class ObserveCheck {
	private static final List< String > failures = new ArrayList<>();
	private static int checksFailed = 0;
	private static int checksRun = 0;
	private static void check( Runnable body ){
		checksRun++;
		int before = failures.size();
		body.run();
		if (failures.size() > before) checksFailed++;
	}
	public static void main( String[] args ){
		HeadlessServices.install( Scratch.dir( "observecheck" ));
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
		check( () -> checkTheOrderedLogNamesEveryDraw( env ) );
		check( ObserveCheck::checkTheGeneratorLogIsInertWhenOff );
		check( ObserveCheck::checkAStandInEmitterSpendsNoGameplayDraw );
		check( ObserveLeakCheck::checkInventoryShowsOnlyWhatThePlayerSees );
		check( ObserveLeakCheck::checkHeroFeaturesHideAnUnknownLevel );
		check( () -> ObserveLeakCheck.checkAnUnseenFloorEncodesToNothing( encoder ) );
		check( () -> ObserveLeakCheck.checkTheWindowIsCentredOnTheHero( encoder ) );
		if (failures.isEmpty()){
			System.out.println( "[OK]     observation purity: " + checksRun + " checks passed" );
		} else {
			System.out.println( "[ERROR]  observation purity: " + checksFailed
					+ " of " + checksRun + " checks failed, " + failures.size() + " assertions" );
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
	static void fail( String message ){
		failures.add( message );
	}

	private static void checkAStandInEmitterSpendsNoGameplayDraw(){
		com.watabou.noosa.particles.Emitter emitter =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessSprite()
						.emitter();

		RandomTrace.enable();
		RandomTrace.reset();
		long before = RandomTrace.draws();
		emitter.start( null, 1f, 1 );
		long afterStart = RandomTrace.draws();
		emitter.burst( null, 1 );
		long afterBurst = RandomTrace.draws();
		RandomTrace.reset();
		RandomTrace.disable();

		if (afterStart != before){
			fail( "starting the headless stand-in emitter spent " + (afterStart - before)
					+ " value(s) from the gameplay RNG. The real Emitter starts by drawing its delay"
					+ " from PRandom, so a stand-in that compensated on the gameplay stream spends a"
					+ " value the game never spends and puts the trainer one value ahead of the"
					+ " rendered game from the first particle effect onwards." );
		}
		if (afterBurst != afterStart){
			fail( "bursting the headless stand-in emitter spent " + (afterBurst - afterStart)
					+ " value(s) from the gameplay RNG, where the real one spends none" );
		}

		com.watabou.utils.PRandom.reseed( 987654321L );
		float[] expected = new float[ 4 ];
		for (int i = 0; i < expected.length; i++) expected[ i ] = com.watabou.utils.PRandom.Float( 1f );

		com.watabou.utils.PRandom.reseed( 987654321L );
		emitter.start( null, 1f, 1 );
		float first = com.watabou.utils.PRandom.Float( 1f );
		float second = com.watabou.utils.PRandom.Float( 1f );

		if (first != expected[ 1 ] || second != expected[ 2 ]){
			fail( "the headless stand-in emitter did not consume exactly one presentation value where"
					+ " the real Emitter consumes one. The two environments have to spend the same"
					+ " number of values on the same stream or nothing downstream of them agrees." );
		}
	}

	private static void checkTheOrderedLogNamesEveryDraw( SPDEnv env ){
		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 200;
		SPDEnv probe = new SPDEnv( config, HeadlessGame.install() );

		RandomTrace.enable();
		RandomTrace.reset();
		RandomTrace.attribute();

		probe.reset( "OBSERVECHECK-ORDERED", HeroClass.WARRIOR );
		ScriptedPolicy policy = new ScriptedPolicy( probe.mapper(), 29 );
		int[] slot = new int[ 1 ];
		for (int i = 0; i < 40 && probe.running(); i++){
			Action action = policy.choose( probe, slot );
			probe.step( action, slot[ 0 ] );
		}

		long drawn = RandomTrace.draws();
		List< String > order = new ArrayList<>( RandomTrace.siteLog() );
		List< String > pushes = new ArrayList<>( RandomTrace.generatorLog() );

		RandomTrace.attribute( false );
		RandomTrace.disable();

		if (order.size() != drawn){
			fail( "the ordered site log holds " + order.size() + " entries for " + drawn
					+ " draws. The per-site tally and the ordered log are built from the same walk, so a "
					+ "difference here means the log can miss a draw - and a log that misses draws cannot "
					+ "name the draw two runs parted at." );
		}

		int unnamed = 0;
		for (String site : order) if (site.startsWith( "(no caller" )) unnamed++;
		if (unnamed > 0){
			fail( unnamed + " of " + order.size() + " logged draws name no caller outside the RNG, so "
					+ "they would all read as the same entry in a diff." );
		}

		if (pushes.isEmpty()){
			fail( "resetting a run and playing forty steps produced no generator stack events. Level "
					+ "generation pushes a generator per floor, so an empty log means the push/pop hook "
					+ "is not wired and no floor boundary can be located." );
		}

		boolean floorPush = false;
		for (String push : pushes) if (push.startsWith( "push " )) floorPush = true;
		if (!floorPush){
			fail( "none of the " + pushes.size() + " generator events is a push" );
		}
	}

	private static void checkTheGeneratorLogIsInertWhenOff(){
		RandomTrace.enable();
		RandomTrace.reset();
		Random.Float();
		Random.Float();
		Random.Float();
		long at = RandomTrace.baseDraws();
		Random.pushGenerator( 7L );
		Random.popGenerator();
		List< String > pushes = new ArrayList<>( RandomTrace.generatorLog() );
		RandomTrace.reset();
		RandomTrace.disable();

		Random.pushGenerator( 7L );
		Random.Float();
		Random.popGenerator();
		List< String > silent = new ArrayList<>( RandomTrace.generatorLog() );

		if (!silent.isEmpty()){
			fail( "the generator log grew while counting was off: " + silent.size()
					+ " entries. Every push and pop in the game would then walk the stack for an "
					+ "instrument nobody asked for, which is the cost this trace exists to avoid." );
		}

		if (pushes.size() != 2){
			fail( "one push and one pop produced " + pushes.size() + " generator events rather than 2" );
			return;
		}

		String marker = " baseDraws=" + at + " ";
		if (!pushes.get( 0 ).contains( marker ) || !pushes.get( 1 ).contains( marker )){
			fail( "a generator event did not carry the base stream's position. The push recorded \""
					+ pushes.get( 0 ) + "\" and the base had made " + at + " draws at that moment. The "
					+ "position at the push is the whole measurement: a floor's generator is its own "
					+ "stream, so nothing outside it can change its layout except what the base was "
					+ "left holding when it was pushed." );
		}

		if (!pushes.get( 0 ).contains( "from=" ) || pushes.get( 0 ).contains( "from=(no caller" )){
			fail( "a generator event did not name the code that pushed: \"" + pushes.get( 0 ) + "\"" );
		}

		if (!pushes.get( 0 ).contains( "ObserveCheck" )){
			fail( "a generator event named " + pushes.get( 0 ).split( "from=" )[ 1 ]
					+ " rather than the code that pushed it. Naming the plumbing instead of the caller is "
					+ "how an instrument points at Random rather than at the fault." );
		}
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
