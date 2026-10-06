package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

/**
 * The trainer/worker wire protocol.
 *
 * Message numbers live here rather than as literals in {@link Trainer} and {@link Worker}. They were
 * duplicated as bare numbers - {@code out.writeInt( 1 )}, {@code out.writeInt( 3 )} - on both sides,
 * with a comment naming the constant. That is one edit away from a desync, and a desync here does not
 * crash: both processes block on pipes neither will write to and the run just looks like it is still
 * working. See {@link WorkerPool}'s watchdog.
 *
 * <pre>
 *   trainer -> worker   HELLO(version)  then per generation: EPISODE(seed, hero, wantReplay),
 *                                  PARAMS(config + hyperparameters + weights), BYE
 *   worker -> trainer   HELLO(version), PARAMS ack, then per episode: EPISODE(summary)
 * </pre>
 *
 * REPLAY and TRANSITIONS are reserved and unused until the data-flow work lands. They are numbered
 * now so that adding them is not an edit to a live frame's meaning.
 */
public final class Protocol {

	/** Checked on connect, so a mismatched pair fails loudly rather than misreading each other. */
	public static final int VERSION = 1;

	// trainer -> worker
	public static final int MSG_HELLO = 1;
	/** Config, hyperparameters and the current weights. Acknowledged. */
	public static final int MSG_PARAMS = 2;
	/** One episode request: seed, hero class, whether a replay is wanted. */
	public static final int MSG_EPISODE = 3;
	/** Reserved. Never sent; kept so a future drain signal is not renumbered onto it. */
	public static final int MSG_DONE = 4;
	public static final int MSG_BYE = 5;

	// worker -> trainer
	/** Reserved for a standalone replay frame, split off the episode channel. */
	public static final int MSG_REPLAY = 6;
	/** Reserved for sampled transitions with their advantages already computed. */
	public static final int MSG_TRANSITIONS = 7;

	private Protocol() {}
}