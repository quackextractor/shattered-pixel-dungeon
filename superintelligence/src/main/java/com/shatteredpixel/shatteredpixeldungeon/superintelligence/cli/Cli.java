package com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.Ansi;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GradientCheck;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfigBinder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;

import java.io.File;
import java.io.IOException;

public final class Cli {

	private Cli(){}

	public static void boot( CliOptions options ){
		if (options.noColor) Ansi.enable( false );

		File workDir = options.outDir != null
				? options.outDir
				: com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer.outDirDefault();

		HeadlessServices.install( workDir );
		HeadlessServices.disableSaving( !options.persistSaves );
	}


	public static EnvConfig configFor( CliOptions options ){
		try {
			EnvConfigBinder.Loaded loaded = EnvConfigBinder.load( options.configFile );
			System.out.println( EnvConfigBinder.describe( options.configFile, loaded.keysApplied ));
			return loaded.config;
		} catch (IOException e){
			System.err.println( "[ERROR] cannot read the configuration at " + options.configFile
					+ " (" + e.getClass().getSimpleName() + "): " + e.getMessage() );
			System.exit( 1 );
			return new EnvConfig();
		} catch (IllegalArgumentException e){
			System.err.println( "[ERROR] " + e.getMessage() );
			System.exit( 1 );
			return new EnvConfig();
		}
	}


	public static String[] tail( String[] args ){
		String[] out = new String[ Math.max( 0, args.length - 1 ) ];
		System.arraycopy( args, 1, out, 0, out.length );
		return out;
	}


	public static int gradcheck( String[] args ){
		boolean verbose = false;
		for (String a : args){
			if (a.equals( "--verbose" )) verbose = true;
		}

		GradientCheck check = new GradientCheck( new EnvConfig(), 7 );
		return check.run( verbose ) > 0 ? 1 : 0;
	}
}