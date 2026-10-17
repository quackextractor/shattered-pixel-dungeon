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

import com.watabou.noosa.Game;

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

	/** Re-reads the property. For gates, which set it after the class has been loaded. */
	public static void reset(){
		pinned = -1f;
		warned = false;
	}

	private static float read(){
		String raw = System.getProperty( "spd.fixedDelta" );
		if (raw == null || raw.trim().isEmpty()) return -1f;

		float value;
		try {
			value = Float.parseFloat( raw.trim() );
		} catch (NumberFormatException e){
			System.err.println( "[replay] spd.fixedDelta is not a number (" + raw + "); using real frame time" );
			return -1f;
		}

		if (value <= 0f || value > 0.2f){
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

		if (!warned){
			warned = true;
			System.err.println( "[replay] spd.fixedDelta pins playback pacing here and, through"
					+ " Game.fixedFrameDelta, the animation clock too - a pinned playback is frame-rate"
					+ " deterministic rather than only reproducible." );
		}

		return fixed;
	}

	private static boolean warned;

	/**
	 * Publishes the pin where {@code Game.update} reads it, and nothing else.
	 *
	 * <p>{@code Game.elapsed} and {@code Game.timeTotal} are both derived from the raw delta before any
	 * game code runs, so writing them from a frame driver only splits the clock - measured in this file's
	 * own javadoc as 0.0166666 requested against 0.0070 accumulated. {@code Game.fixedFrameDelta} is
	 * upstream of both, so pinning it pins the animation clock as well as playback's pacing.
	 *
	 * <p>No-op when nothing is pinned, so ordinary gameplay reads {@code Gdx.graphics.getDeltaTime()}
	 * exactly as before.
	 */
	public static void apply(){
		float fixed = pinned();
		Game.fixedFrameDelta = fixed < 0f ? 0f : fixed;
		if (Game.fixedFrameDelta > 0f){
			System.err.println( "[replay] spd.fixedDelta pins Game.fixedFrameDelta at "
					+ Game.fixedFrameDelta + "s, so the animation clock is deterministic for this run" );
		}
	}

	/** Restores measured frame time, so a viewer's pin cannot outlive the playback. */
	public static void release(){
		Game.fixedFrameDelta = 0f;
	}
}