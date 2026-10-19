package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.ConfigDefaults.fail;

public final class ConfigDotEnvCheck {

	private ConfigDotEnvCheck(){}

	public static void checkDotEnvIsRead(){
		File dir = new File( ConfigDefaults.scratch, "dotenv" );
		dir.mkdirs();

		Path file = new File( dir, "run.properties" ).toPath();
		try {
			Files.writeString( file, "env.turn_limit_per_floor=1500\n" );

			Files.writeString( new File( dir, ".env" ).toPath(),
					"# a comment\n"
					+ "SPD_ENV_TURN_LIMIT_PER_FLOOR=321\n"
					+ "SPD_RL_GAMMA=\"0.5\"\n"
					+ "NOT_OURS=1\n"
					+ "malformed line\n" );
		} catch (IOException e){
			fail( "could not write the .env fixture: " + e.getMessage() );
			return;
		}

		EnvConfigBinder.Loaded loaded = load( file );

		if (loaded == null ) return;

		if (loaded.config.turnLimitPerFloor != 321 ){
			fail( ".env beside the properties file was not read: turn_limit_per_floor is "
					+ loaded.config.turnLimitPerFloor + ", not the 321 it names." );
			return;
		}

		if (Math.abs( loaded.hyper.gamma - 0.5f ) > 1e-6){
			fail( "a quoted .env value was not unquoted: gamma is " + loaded.hyper.gamma + ", not 0.5." );
			return;
		}

		boolean sawForeign = false;
		boolean sawMalformed = false;
		for (String warning : loaded.warnings){
			if (warning.contains( "NOT_OURS" )) sawForeign = true;
			if (warning.contains( "malformed line" )) sawMalformed = true;
		}

		if (!sawForeign || !sawMalformed){
			fail( "a .env entry that is not SPD_ prefixed, and a line with no '=', should both warn."
					+ " sawForeign=" + sawForeign + " sawMalformed=" + sawMalformed );
			return;
		}

		System.out.println( "  .env is read beside the properties file: quoted values unquoted, foreign"
				+ " and malformed entries warned about" );
	}


	public static void checkDotEnvBeatsThePropertiesFile(){
		File dir = new File( ConfigDefaults.scratch, "dotenv-order" );
		dir.mkdirs();

		Path file = new File( dir, "run.properties" ).toPath();
		try {
			Files.writeString( file, "env.stall_limit=111\n" );
			Files.writeString( new File( dir, ".env" ).toPath(), "SPD_ENV_STALL_LIMIT=222\n" );
		} catch (IOException e){
			fail( "could not write the ordering fixture: " + e.getMessage() );
			return;
		}

		EnvConfigBinder.Loaded loaded = load( file );
		if (loaded == null ) return;

		if (loaded.config.stallLimit != 222 ){
			fail( ".env names stall_limit 222 and the properties file names 111, but the run used "
					+ loaded.config.stallLimit + ". The documented order is defaults, file, .env,"
					+ " process environment." );
			return;
		}

		System.out.println( "  .env outranks the properties file" );
	}


	public static void checkABadDotEnvValueNamesTheDotEnv(){
		File dir = new File( ConfigDefaults.scratch, "dotenv-origin" );
		dir.mkdirs();

		Path file = new File( dir, "run.properties" ).toPath();
		try {
			Files.writeString( file, "" );
			Files.writeString( new File( dir, ".env" ).toPath(), "SPD_ENV_MAX_SLOTS=wide\n" );
		} catch (IOException e){
			fail( "could not write the origin fixture: " + e.getMessage() );
			return;
		}

		try {
			EnvConfigBinder.load( file );
			fail( "SPD_ENV_MAX_SLOTS=wide in .env was accepted" );
		} catch (IOException e){
			fail( "the origin fixture could not be read: " + e.getMessage() );
		} catch (IllegalArgumentException e){
			if (!e.getMessage().contains( "SPD_ENV_MAX_SLOTS in " + new File( dir, ".env" ).toPath() )){
				fail( "a bad .env value was blamed on the wrong source: " + e.getMessage() );
				return;
			}
			System.out.println( "  a bad .env value names the .env entry it came from" );
		}
	}


	public static void checkTheExampleFileNamesEveryDocumentedKey(){
		Path example = exampleFile();
		if (example == null ) return;

		java.util.Map< String, String > named = new java.util.LinkedHashMap<>();
		try {
			for (String line : Files.readAllLines( example )){
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith( "#" )) continue;
				int eq = trimmed.indexOf( '=' );
				if (eq <= 0 ) continue;
				named.put( trimmed.substring( 0, eq ).trim(), trimmed.substring( eq + 1 ).trim() );
			}
		} catch (IOException e){
			fail( "could not read " + example + ": " + e.getMessage() );
			return;
		}

		List< String > missing = new ArrayList<>();
		List< String > mismatched = new ArrayList<>();
		List< String > unnamed = new ArrayList<>();

		EnvConfigBinder.Loaded defaults;
		try {
			defaults = EnvConfigBinder.load( null );
		} catch (IOException e){
			fail( "could not load the compiled defaults: " + e.getMessage() );
			return;
		}

		for (String key : EnvConfigBinder.KEYS){
			String name = EnvConfigBinder.environmentName( key );
			String written = named.get( name );
			if (written == null ){
				missing.add( name );
				continue;
			}
			String actual = ConfigDefaults.compiledValue( key, defaults.config, defaults.hyper );
			if (actual == null ){
				unnamed.add( key + " (no compiled value to compare it against)" );
				continue;
			}
			if (!ConfigDefaults.sameValue( actual, written )){
				mismatched.add( name + ": example says " + written + ", code uses " + actual );
			}
		}

		if (!missing.isEmpty()){
			fail( example + " names " + named.size() + " SPD_ variables and omits "
					+ missing.size() + " the binder accepts: " + String.join( ", ", missing )
					+ ". A key with no entry in the example file is a key an operator reading the"
					+ " example cannot find, which is what the example file is for." );
			return;
		}

		if (!unnamed.isEmpty()){
			fail( "the example file carries " + unnamed.size() + " key(s) this check cannot compare"
					+ " against the compiled defaults: " + String.join( "; ", unnamed )
					+ ". Skipping a key is not the same as agreeing with it" );
			return;
		}

		if (!mismatched.isEmpty()){
			fail( example + " disagrees with the compiled defaults in " + mismatched.size()
					+ " place(s): " + String.join( "; ", mismatched ) + ". Copying this file configures"
					+ " a run the module does not run, which is how depth_reward 25 against a compiled"
					+ " 1800 survived in it once." );
			return;
		}

		System.out.println( "  " + example.getFileName() + " names all " + EnvConfigBinder.KEYS.size()
				+ " documented keys as SPD_ variables, and every value matches the compiled default" );
	}


	private static Path exampleFile(){
		for (String candidate : new String[]{ "superintelligence/.env.example", ".env.example" }){
			Path path = new File( candidate ).toPath();
			if (Files.isReadable( path )) return path;
		}
		fail( "could not find .env.example from " + new File( "." ).getAbsolutePath()
				+ ". It is a committed file beside superintelligence.properties; this check cannot pass"
				+ " without it, and failing is better than skipping." );
		return null;
	}


	private static EnvConfigBinder.Loaded load( Path file ){
		try {
			return EnvConfigBinder.load( file );
		} catch (IOException | IllegalArgumentException e){
			fail( "the fixture at " + file + " could not be loaded: " + e.getMessage() );
			return null;
		}
	}
}