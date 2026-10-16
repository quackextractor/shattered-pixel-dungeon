package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.SPDEnv;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


public class ActionCheck {

	private static final int CHECKS = 3;

	private static final List< String > failures = new ArrayList<>();


	private static final String[] SEEDS = {
		"ACTIONCHECK-A", "ACTIONCHECK-B", "ACTIONCHECK-C", "ACTIONCHECK-D"
	};

	public static void main( String[] args ){
		HeadlessServices.install( new File( System.getProperty( "java.io.tmpdir" ), "spd-actioncheck" ));
		HeadlessServices.disableSaving( true );

		checkEveryDirectionMovesTheWayItIsNamed();
		checkDirectionOffsetsMatchTheGamesConvention();
		checkTheActionSpaceIsStillComplete();

		if (failures.isEmpty()){
			System.out.println( "[OK]     action semantics: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  action semantics: " + failures.size() + " of " + CHECKS
					+ " checks failed" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}


	private static void checkEveryDirectionMovesTheWayItIsNamed(){
		EnvConfig config = new EnvConfig();
		config.turnLimitPerFloor = 400;

		for (Action move : directionalMoves()){
			String expected = direction(move);
			boolean sawAnyMove = false;

			for (String seed : SEEDS){
				SPDEnv env = new SPDEnv( config, HeadlessGame.install() );
				env.reset( seed, HeroClass.WARRIOR );

				int width = Dungeon.level.width();
				int before = env.heroPosition();
				int bx = before % width, by = before / width;

				for (int i = 0; i < 6 && env.mode() == EnvMode.WORLD; i++ ) env.step( move, 0 );

				int after = env.heroPosition();
				int ax = after % width, ay = after / width;

				int dx = Integer.signum( ax - bx );
				int dy = Integer.signum( ay - by );
				if (dx == 0 && dy == 0 ) continue;

				sawAnyMove = true;

				String got = observedDirection( dx, dy );

				if (!got.equals( expected )) {
					failures.add( move + " is named " + expected + " but moved " + got
							+ " on seed " + seed + " (row " + by + " -> " + ay + ", column " + bx
							+ " -> " + ax + "). The game's own convention is"
							+ " Level.pointToCell = x + y*width with CellSelector giving north"
							+ " (0,-1), so north must DECREASE the row index." );
					return;
				}

				break;  
			}

			if (!sawAnyMove ){
				failures.add( move + " never moved on any of the " + SEEDS.length + " seeds, so its"
						+ " direction cannot be checked. Widen the seed list rather than accepting"
						+ " an unverified direction." );
				return;
			}
		}

		System.out.println( "  all eight directions move the way they are named" );
	}


	private static void checkDirectionOffsetsMatchTheGamesConvention(){
		int[] dx = { 0, 1, 1, 1, 0, -1, -1, -1 };
		int[] dy = { -1, -1, 0, 1, 1, 1, 0, -1 };
		String[] names = { "N", "NE", "E", "SE", "S", "SW", "W", "NW" };

		List< Action > moves = directionalMoves();
		if (moves.size() != 8 ){
			failures.add( "expected 8 directional moves, found " + moves.size() );
			return;
		}

		for (int i = 0; i < 8; i++ ){
			Action move = moves.get( i );
			if (!move.name().equals( "MOVE_" + names[ i ] )) continue;
			if (move.dx != dx[ i ] || move.dy != dy[ i ] ){
				failures.add( move + " carries offset (" + move.dx + "," + move.dy + ") where the game"
						+ " gives " + names[ i ] + " (" + dx[ i ] + "," + dy[ i ] + ")." );
				return;
			}
		}

		System.out.println( "  direction offsets match Level.pointToCell and CellSelector" );
	}


	private static void checkTheActionSpaceIsStillComplete(){
		Action[] opposite = {
			Action.MOVE_S, Action.MOVE_SW, Action.MOVE_W, Action.MOVE_NW,
			Action.MOVE_N, Action.MOVE_NE, Action.MOVE_E, Action.MOVE_SE
		};
		Action[] moves = directionalMoves().toArray( new Action[ 0 ] );

		for (int i = 0; i < moves.length; i++ ){
			if (moves[ i ].dx != -opposite[ i ].dx || moves[ i ].dy != -opposite[ i ].dy ){
				failures.add( moves[ i ] + " and " + opposite[ i ] + " are not opposite directions." );
				return;
			}
		}

		for (int i = 0; i < moves.length; i++ ){
			String expected = "MOVE_" + ( new String[]{ "N", "NE", "E", "SE", "S", "SW", "W", "NW" }[ i ] );
			if (!moves[ i ].name().equals( expected )){
				failures.add( "directional moves are declared out of order: position " + i + " is "
						+ moves[ i ] + " where " + expected + " was expected. The checks pair by"
						+ " position, so reordering the enum silently invalidates them." );
				return;
			}
		}

		System.out.println( "  the eight directions are mutually opposite and declared in order" );
	}

	private static List< Action > directionalMoves(){
		List< Action > moves = new ArrayList<>();
		for (Action a : Action.values()){
			if (a.directional()) moves.add( a );
		}
		return moves;
	}


	private static String observedDirection( int dx, int dy ){
		if (dy == 0 ) return dx < 0 ? "W" : "E";
		if (dx == 0 ) return dy < 0 ? "N" : "S";
		if (dy < 0 ) return dx < 0 ? "NW" : "NE";
		return dx < 0 ? "SW" : "SE";
	}


	private static String direction( Action a ){
		switch (a){
			case MOVE_N:  return "N";
			case MOVE_NE: return "NE";
			case MOVE_E:  return "E";
			case MOVE_SE: return "SE";
			case MOVE_S:  return "S";
			case MOVE_SW: return "SW";
			case MOVE_W:  return "W";
			default:      return "NW";
		}
	}
}
