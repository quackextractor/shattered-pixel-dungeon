package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScenarioCatalog {

	private ScenarioCatalog(){}

	private static final List< Scenario > ALL = new ArrayList<>();

	private static final List< Scenario > SELF_TESTS = new ArrayList<>();

	static {
		ALL.addAll( CombatScenarios.all() );
		ALL.addAll( ModeScenarios.all() );
		ALL.addAll( GapScenarios.all() );
		ALL.addAll( RangedScenarios.all() );
		ALL.addAll( CraftScenarios.all() );
		ALL.addAll( WorldScenarios.all() );
		ALL.addAll( FeatureScenarios.all() );
		ALL.addAll( TrapScenarios.all() );
		ALL.addAll( MobScenarios.all() );
		ALL.addAll( StateScenarios.all() );
		ALL.addAll( EffectScenarios.all() );
		ALL.addAll( LeakScenarios.all() );
		ALL.addAll( DepthScenarios.all() );
		ALL.addAll( CreatureScenarios.all() );
		ALL.addAll( NpcScenarios.all() );
		ALL.addAll( ConditionScenarios.all() );
		ALL.addAll( SewerScenarios.all() );
		ALL.addAll( PlantScenarios.all() );
		ALL.addAll( PotionScenarios.all() );
		ALL.addAll( ScrollScenarios.all() );
		ALL.addAll( StoneScenarios.all() );
		ALL.addAll( BombScenarios.all() );
		ALL.addAll( MissileScenarios.all() );
		ALL.addAll( FoodScenarios.all() );
		ALL.addAll( ArmourScenarios.all() );
		ALL.addAll( RingScenarios.all() );
		ALL.addAll( MeleeScenarios.all() );
		ALL.addAll( WeaponAbilityScenarios.all() );
		ALL.addAll( EnchantScenarios.all() );
	}

	public static List< Scenario > all(){
		return Collections.unmodifiableList( ALL );
	}

	public static boolean selected( String filter, String id ){
		for (String token : filter.split( "," )){
			String t = token.strip();
			if (t.endsWith( "*" ) ? id.startsWith( t.substring( 0, t.length() - 1 )) : id.equals( t )) return true;
		}
		return false;
	}

	public static void registerSelfTest( Scenario scenario ){
		SELF_TESTS.add( scenario );
	}

	public static Scenario find( String id ){
		for (Scenario scenario : ALL) if (scenario.id.equals( id )) return scenario;
		for (Scenario scenario : SELF_TESTS) if (scenario.id.equals( id )) return scenario;
		return WorldSweep.find( id );
	}
}
