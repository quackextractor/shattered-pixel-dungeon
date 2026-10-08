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

package com.watabou.utils;

/**
 * Counts and fingerprints every draw made through {@link Random}.
 *
 * <p>Disabled by default, and the check is a single static boolean per draw, so the cost when nobody is
 * measuring is one predictable branch. Enable it only from a gate or a diagnostic.
 *
 * <p>Only {@code Random} is instrumented. Presentation randomness lives on {@link PRandom}, which has
 * its own stream, so what this counts is exactly the draws the simulation is entitled to: every other
 * number on screen is cosmetic and must not perturb this one.
 *
 * <p>Used for two things. {@code observecheck} asserts that <em>reading</em> the world draws nothing -
 * the invariant whose one violation, {@code HeroEncoder} calling {@code drRoll()}, silently offset the
 * RNG stream from the first floor of every run. {@code rngtrace} compares the per-step count and
 * fingerprint between a headless run and the rendered game.
 */
public class RandomTrace {

	private static boolean enabled = false;

	/** Draws made, whichever generator answered. */
	private static long draws = 0;

	/** Draws made with {@code useGeneratorStack == false}, i.e. against the base generator. */
	private static long baseDraws = 0;

	/**
	 * Order-sensitive fold of every value drawn.
	 *
	 * <p>A count alone cannot distinguish "drew the same numbers in a different order" from "drew the
	 * same numbers in the same order", and the first is a divergence too.
	 */
	private static long fingerprint = 0;

	private RandomTrace() {}

	public static void enable(){
		enabled = true;
	}

	public static void disable(){
		enabled = false;
	}

	/** True while counting. */
	public static boolean enabled(){
		return enabled;
	}

	/** Zeroes the counters. Does not disable. */
	public static void reset(){
		draws = 0;
		baseDraws = 0;
		fingerprint = 0;
	}

	/**
	 * Records one draw.
	 *
	 * @param bits the raw bits of the value drawn
	 * @param base  true when it came from the base generator rather than the pushed stack
	 */
	public static void record( long bits, boolean base ){
		if (!enabled) return;
		draws++;
		if (base) baseDraws++;
		//FNV-style: multiplication then xor, so the result depends on order and on every bit.
		fingerprint ^= bits;
		fingerprint *= 1099511628211L;
	}

	public static long draws(){
		return draws;
	}

	public static long baseDraws(){
		return baseDraws;
	}

	public static long fingerprint(){
		return fingerprint;
	}

	/** One-line summary for assertions and diagnostics. */
	public static String summary(){
		return "draws=" + draws + " baseDraws=" + baseDraws + " fingerprint=" + fingerprint;
	}
}