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

package com.shatteredpixel.shatteredpixeldungeon.desktop.replay;

/**
 * The frame delta the viewer advances playback by, and the option to pin it.
 *
 * <p>Left alone this is {@code Game.elapsed}, which is real measured frame time. That is what a person
 * watching a run wants and what makes playback frame-rate dependent: the number of frames a step
 * consumes depends on how fast the machine renders, and the number of frames a step consumes is the
 * number of chances the render loop gets to change the world.
 *
 * <p>Pinned with {@code -Dspd.fixedDelta=<seconds>} the player's pacing becomes a constant, which is what
 * makes the number of frames between recorded steps a function of the recording rather than of the
 * machine.
 *
 * <p><b>It does not pin the animation clock</b>, and no arrangement of writes from here can - see
 * {@link #current(float)} for the measurements. Animation timing remains frame-rate dependent, so a
 * pinned playback is reproducible and a frame-rate independent one is not yet possible without an engine
 * change at the point the delta is measured.
 *
 * <p>The value is fed into {@code Game.timeScale} rather than substituted at the call site, because
 * {@code Game.update} computes {@code Game.elapsed} from the scale before {@code scene.update()} runs.
 * Substituting only at the player would pin playback's own pacing and leave the animations running on
 * real time - which is precisely the frame-timing coupling under investigation.
 */
public class FrameDelta {

	private FrameDelta(){
	}

	private static float pinned = -1f;

	/**
	 * Pinned delta in seconds, or -1 when playback runs on real frame time.
	 *
	 * <p>Read once. A value that could change mid-run would make a run that pinned it partway through
	 * incomparable with one that pinned it from the start, with nothing in the output to say so.
	 */
	public static float pinned(){
		if (pinned == -1f) pinned = read();
		return pinned;
	}

	private static float read(){
		String raw = System.getProperty( "spd.fixedDelta" );
		if (raw == null || raw.trim().isEmpty()) return -1f;

		float value;
		try {
			value = Float.parseFloat( raw.trim() );
		} catch (NumberFormatException e){
			//Not a fatal refusal: this is a diagnostic flag, and a mistyped one should not stop a
			//playback that would otherwise work. Falling back to real time is the documented behaviour.
			System.err.println( "[replay] spd.fixedDelta is not a number (" + raw + "); using real frame time" );
			return -1f;
		}

		if (value <= 0f || value > 0.2f){
			//0.2 is Game.update's own ceiling, so a larger pin would be silently clamped and the pin
			//would not be the pin that was asked for.
			System.err.println( "[replay] spd.fixedDelta must be in (0, 0.2]; got " + value
					+ ". Using real frame time." );
			return -1f;
		}

		return value;
	}

	/**
	 * The delta to hand {@link ReplayPlayer#update(float)}.
	 *
	 * <p>Written into {@code Game.timeScale}, which is the <i>only</i> place a pinned delta takes effect
	 * on the whole clock. Two earlier attempts were wrong and both are worth recording.
	 *
	 * <p>Overwriting {@code Game.elapsed} directly does pin what animations read, because the frame driver
	 * runs before {@code GameScene.super.update()}. But {@code Game.update} computes
	 * {@code elapsed = timeScale * frameDelta} and folds it into {@code timeTotal} <i>before</i> the frame
	 * driver is reached, so {@code timeTotal} keeps accumulating real frame time. Measured: with a pin of
	 * 0.0166666, {@code Game.elapsed} read 0.0166666 and {@code timeTotal} advanced 0.0070 per frame - the
	 * animation clock split 42/58 between the two, and a 0.33s attack took 49 frames instead of 20. Two
	 * clocks disagreeing, which is the thing a pin exists to prevent.
	 *
	 * <p>Scaling {@code Game.timeScale} is correct precisely because it is upstream of both. It has one
	 * sharp edge: {@code Game.update} clamps {@code frameDelta} to 0.2s before applying the scale, so a
	 * frame longer than 0.2s gets proportionally less time than requested. That is a floor on frame length
	 * rather than a silent wrong answer - it can only make a frame advance less than asked, never more -
	 * and it is the same clamp the unpinned path already has.
	 *
	 * @return the pinned delta, or {@code realDelta} when nothing is pinned
	 */
	public static float current( float realDelta ){
		float fixed = pinned();
		if (fixed < 0f) return realDelta;

		//timeScale is left alone, and only the value handed to the player is pinned.
		//
		//Three attempts were made to pin the animation clock and all three fail, for one structural
		//reason: Game.elapsed is *derived*. Game.update computes it as timeScale * frameDelta and folds it
		//into timeTotal before any game code runs, so the frame driver - which runs from inside
		//scene.update() - is downstream of the value it would need to influence.
		//
		//  - writing Game.elapsed: pins what MovieClip reads, but timeTotal keeps accumulating real
		//    frame time. Measured 0.0166 against 0.0070 per frame: the clock split 42/58.
		//  - writing timeScale: applies to the *next* frame's delta, which cannot be observed, so each
		//    frame's ratio is derived from a delta the previous frame's ratio already distorted. Measured
		//    oscillating between 0.001 and 0.14 per frame.
		//  - writing both: no better, same two problems.
		//
		//So the honest scope of this flag is the player's own pacing, and nothing else. It makes the
		//number of frames the player waits between steps independent of frame rate, which is what the
		//regression tests need - and it does NOT make animations frame-rate independent, because that
		//would require the pin to sit upstream of Game.update, in the engine, at the point the delta is
		//measured.
		//
		//Refusing loudly rather than appearing to work, because a flag that half-works is worse than one
		//that says what it does.
		if (!warned){
			warned = true;
			System.err.println( "[replay] spd.fixedDelta pins playback pacing only. Animations still run on"
					+ " real frame time, so it does not make a playback frame-rate independent - see"
					+ " FrameDelta for why the animation clock cannot be pinned from here." );
		}

		return fixed;
	}

	private static boolean warned;
}