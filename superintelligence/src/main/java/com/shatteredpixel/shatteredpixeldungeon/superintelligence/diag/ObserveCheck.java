/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */

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

/**
 * Asserts that <em>reading</em> the world draws no randomness.
 *
 * <p>One violation of this already cost a full parity investigation. {@code HeroEncoder} built its
 * defence feature with {@code hero.drRoll()}, which is not a property of the hero but a fresh
 * {@code Random.NormalIntRange} per call, so encoding an observation advanced the gameplay RNG twice per
 * agent step. The stream was offset from the first floor and stayed offset, and the visible symptom -
 * rolls replaying differently - was blamed in turn on the observation encoder, the particle system,
 * the emote icons and the music, and finally the sound pitch. Every one of those was a separate real
 * leak; none of them was the first.
 *
 * <p>Nothing caught it, because every gate re-executes through {@code HeadlessGame}: a gate that shares
 * its implementation with its subject cannot see a fault in it. This one does not re-execute anything.
 * It measures the encoders directly, which is the only way to test them.
 *
 * <p>The first check is a positive control - it draws on purpose and requires the counter to move. Every
 * other check asserts a count of zero, and a counter that is silently broken would satisfy all of them
 * while looking perfectly healthy.
 */
public class ObserveCheck {

	private static final int CHECKS = 4;

	private static final List< String > failures = new ArrayList<>();

	/**
	 * Runs one check and counts it failed if it produced any assertion.
	 *
	 * <p>Counting {@code failures.size()} against {@code CHECKS} looks equivalent and is not: one check
	 * that makes three assertions then reports itself as three failed checks.
	 */
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

		//walk the hero far enough in to be carrying something, so the inventory encoder has real work
		//and the check cannot pass by measuring an empty world
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

		if (failures.isEmpty()){
			System.out.println( "[OK]     observation purity: " + CHECKS + " checks passed" );
		} else {
			System.out.println( "[ERROR]  observation purity: " + checksFailed
					+ " of " + CHECKS + " checks failed, " + failures.size() + " assertions" );
			for (String f : failures) System.out.println( "        " + f );
			System.exit( 1 );
		}
	}

	/**
	 * The counter has to be able to move before any "no draws" result means anything.
	 *
	 * <p>Without this, deleting the {@code RandomTrace.record} calls would turn every check below into a
	 * pass and the gate would report a clean bill of health for a property it had stopped measuring.
	 */
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

	/** The invariant itself: encoding an observation must not advance the simulation's randomness. */
	private static void checkEncodingDrawsNothing( ObservationEncoder encoder ){
		RandomTrace.enable();
		RandomTrace.reset();
		encoder.encode();
		//captured before the reset, so a failure reports the numbers that were actually measured
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

	/** Purity implies determinism, so this catches an encoder that reads live state twice and disagrees. */
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

	/**
	 * The quickslot snapshot goes into every recorded SLOT and INVENTORY step.
	 *
	 * <p>Checked separately because it runs on the recording path rather than the agent's, and a replay
	 * that captured bindings from a shifted stream would resolve a recorded slot index against the wrong
	 * item.
	 */
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
}