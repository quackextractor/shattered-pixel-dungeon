package com.shatteredpixel.shatteredpixeldungeon.superintelligence.cli;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

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
					o.saveTo = new File( args[ ++i ] );
					break;
				case "--out":
					o.outDir = new File( args[ ++i ] );
					break;
				case "--max-turns":
					o.maxTurns = Integer.parseInt( args[ ++i ] );
					break;
				case "--config":
					o.configFile = Paths.get( args[ ++i ] );
					break;
				case "--no-color":
					o.noColor = true;
					break;
				case "--rng-trace-out":
					o.rngTraceOut = Paths.get( args[ ++i ] );
					break;
				case "--rng-trace-sites":
					o.rngTraceSites = Paths.get( args[ ++i ] );
					break;
				case "--rng-trace-golden":
					o.rngTraceGolden = Paths.get( args[ ++i ] );
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
}