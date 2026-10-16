package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

public final class Protocol {

	public static final int VERSION = 5;

	public static final int MSG_HELLO = 1;
	public static final int MSG_PARAMS = 2;
	public static final int MSG_EPISODE = 3;
	public static final int MSG_DONE = 4;
	public static final int MSG_BYE = 5;

	public static final int MSG_REPLAY = 6;
	public static final int MSG_TRANSITIONS = 7;

	private Protocol() {}
}