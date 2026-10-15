package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

/**
 * Which guard ended an episode as {@code STALLED}.
 *
 * <p>Four separate guards raise that one reason, and they are not interchangeable: one is the engine
 * refusing to hand back control, one is a pipeline that could not reach a settled hero, one is the hero
 * standing still, and one is the agent spending actions that cost nothing. A run reporting "99% STALLED"
 * names none of them.
 *
 * <p>Its own file rather than nested in {@link SPDEnv}, which is over the module's line limit already and
 * does not need a diagnostic enum added to it.
 */
public enum StallCause {

	/** The episode did not end as STALLED. */
	NONE,

	/**
	 * {@code settle}'s {@code actorStepLimit}: the engine kept taking actor steps without ever yielding
	 * control back to the agent. The backstop for a state the agent cannot influence.
	 */
	ACTOR_STEP_LIMIT,

	/**
	 * {@code runToHeroReady} returned {@code STALLED} or {@code STEP_LIMIT}: the pipeline could not get the
	 * hero back to a settled, ready state within its budget.
	 */
	PIPELINE,

	/**
	 * The {@code stallLimit} guard: {@code stallLimit} consecutive steps with no change to the hero's cell
	 * or health. The hero is standing still - not necessarily refusing to move, since a move into a wall
	 * also leaves the cell unchanged.
	 */
	POSITION,

	/** The {@code freeStepBudget} guard: too many steps on this floor that advanced no engine time. */
	FREE_STEP
}