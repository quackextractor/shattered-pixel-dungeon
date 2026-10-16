package com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Trainer;

import java.io.File;
import java.nio.file.Path;

public class CliOptions {

	public String seed = "";
	public boolean seedProvided = false;

	public Path configFile;
	public HeroClass hero = HeroClass.WARRIOR;
	public File saveTo;
	public File outDir;
	public int maxTurns = 0;
	public boolean noColor = false;
	public boolean persistSaves = false;

	public Path rngTraceOut;
	public Path rngTraceGolden;
	public Path rngTraceSites;

	public static CliOptions parse( String[] args ){
		CliOptions o = new CliOptions();
		for (int i = 0; i < args.length; i++){
			switch (args[ i ]) {
				case "--seed":
					o.seed = args[ ++i ];
					o.seedProvided = true;
					break;
				case "--hero":
					o.hero = HeroClass.valueOf( args[ ++i ].toUpperCase() );
					break;
				case "--save":
					o.saveTo = resolve( new File( args[ ++i ] ));
					break;
				case "--out":
					o.outDir = resolve( new File( args[ ++i ] ));
					break;
				case "--max-turns":
					o.maxTurns = Integer.parseInt( args[ ++i ] );
					break;
				case "--config":
					o.configFile = resolve( new File( args[ ++i ] )).toPath();
					break;
				case "--no-color":
					o.noColor = true;
					break;
				case "--rng-trace-out":
					o.rngTraceOut = resolve( new File( args[ ++i ] )).toPath();
					break;
				case "--rng-trace-sites":
					o.rngTraceSites = resolve( new File( args[ ++i ] )).toPath();
					break;
				case "--rng-trace-golden":
					o.rngTraceGolden = resolve( new File( args[ ++i ] )).toPath();
					break;
				case "--persist-saves":
					o.persistSaves = true;
					break;
				default:
					if (args[ i ].startsWith( "--" )){
						System.err.println( "[WARN] unknown option: " + args[ i ] );
					}
			}
		}
		return o;
	}

	public static File resolve( File path ){
		if (path == null || path.isAbsolute()) return path;

		File module = Trainer.replayDirDefault();
		File root = module.getParentFile();
		return new File( root == null ? module : root, path.getPath() );
	}
}