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
 * <p>Pinned with {@code -Dspd.fixedDelta=<seconds>} it becomes a constant, and because every animation in
 * the game accumulates {@code Game.elapsed} rather than counting frames, a constant delta makes the
 * frame count per step deterministic too. That is what turns "this recording fails" into "this
 * recording fails identically every time", which is the only form in which a fix can be demonstrated.
 *
 * <p>Verified doing exactly that: three consecutive corpus runs at a pinned 0.0166666s agree on the
 * failing step for every recording, where unpinned runs move both the step and the membership.
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
	 * The delta to hand {@link ReplayPlayer#update(float)}, and the value {@code Game.elapsed} is set to.
	 *
	 * <p>Also writes {@code Game.elapsed} directly rather than scaling {@code Game.timeScale}. Two
	 * reasons, both learned the hard way:
	 *
	 * <p>{@code Game.update} computes {@code Game.elapsed = timeScale * frameDelta} <i>before</i>
	 * {@code scene.update()} runs, so a timeScale written from the frame driver arrives too late - the
	 * frame's animations would advance on the old scale while the player advanced on the new one, and the
	 * two clocks would disagree by exactly the amount the pin was supposed to remove.
	 *
	 * <p>And {@code frameDelta} is clamped to 0.2s by {@code Game.update}, so any timeScale chosen to hit a
	 * target delta silently misses when a frame takes longer than that. Under parallel load it does, which
	 * is how the clamp was found.
	 *
	 * <p>Overwriting {@code Game.elapsed} works because the frame driver runs first and
	 * {@code GameScene.super.update()} - which advances every animation - runs after it. The overwrite is
	 * safe: {@code Game.update} recomputes the field from scratch on the next frame, so nothing downstream
	 * inherits it.
	 */
	public static float current( float realDelta ){
		float fixed = pinned();
		if (fixed < 0f) return realDelta;

		com.watabou.noosa.Game.elapsed = fixed;
		return fixed;
	}
}