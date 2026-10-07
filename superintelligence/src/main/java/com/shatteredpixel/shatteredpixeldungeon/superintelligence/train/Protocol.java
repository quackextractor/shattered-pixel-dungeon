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
 * TRANSITIONS is live. REPLAY is still reserved: the replay rides the episode frame, which is a small
 * share of a generation's traffic, and splitting it is worth doing only once a measurement says so.
 * MSG_DONE is reserved and never sent. All three are numbered so adding one is not an edit to a live
 * frame's meaning.
 */
public final class Protocol {

	/**
	 * Checked on connect, so a mismatched pair fails loudly rather than misreading each other.
	 *
	 * <p><b>2, not 1.</b> Two frame layouts changed incompatibly after version 1 shipped: the params
	 * frame gained the sampling knobs and gamma/lambda, and the transition frame gained the recurrent
	 * state. A version-1 worker reading a version-2 params frame would consume the wrong number of
	 * fields and then desynchronise — and a desync here does not crash. Both processes block on pipes
	 * neither will write to, and the run simply looks like it is still working, which is what
	 * {@link WorkerPool}'s stall watchdog exists to catch.
	 *
	 * <p>So the constant is the thing that catches it, and it had stopped distinguishing the two
	 * layouts: it was 1 while both frames beneath it had moved.
	 */
	public static final int VERSION = 2;

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
	/** Sampled transitions, with their advantages and recurrent states already computed. */
	public static final int MSG_TRANSITIONS = 7;

	private Protocol() {}
}