package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which terms of the reward function are allowed to pay at all.
 *
 * <p>DEFAULT is every term, which is what the module has always run.
 *
 * <p>DEPTH_ONLY silences every term except the objective and the prices for stopping. Positive score is
 * then reachable only by reaching a new floor or winning, and the only negatives are the turn cost, the
 * charge for a step the engine resolved for free, running out of turns, dying and stalling. It is a
 * temporary experimental override, selected by {@code reward.profile} in
 * {@code superintelligence.properties} or by {@code SPD_REWARD_PROFILE}.
 *
 * <p>The allow-list is written out rather than derived from {@link RewardTerm#isShaping()} because
 * "everything that is not shaping" would admit {@link RewardTerm#GOLD_LOST}, {@link RewardTerm#DAMAGE_TAKEN}
 * and {@link RewardTerm#CURSE_EQUIPPED}, and the point of the profile is that only the four stated
 * negatives survive. A derived rule and a stated rule disagree the first time a term is added.
 */
public enum RewardProfile {

	DEFAULT,
	DEPTH_ONLY;

	/** What the properties file and the wire carry. Lower case, so it reads like the other values. */
	public String key(){
		return name().toLowerCase( Locale.ROOT );
	}

	/**
	 * The profile named by {@code raw}, or an exception naming the ones that exist.
	 *
	 * <p>Throws rather than defaulting, because the profile is a claim about what a run was rewarding and
	 * a misspelling would quietly train the objective rather than the experiment.
	 */
	public static RewardProfile parse( String raw ){
		String wanted = raw == null ? "" : raw.trim().toLowerCase( Locale.ROOT );
		for (RewardProfile profile : values()){
			if (profile.key().equals( wanted )) return profile;
		}

		StringBuilder known = new StringBuilder();
		for (RewardProfile profile : values()){
			if (known.length() > 0 ) known.append( ", " );
			known.append( profile.key() );
		}

		throw new IllegalArgumentException( "reward.profile needs one of " + known
				+ ", but it was \"" + raw + "\". Nothing was defaulted: a run priced by a reward function"
				+ " nobody asked for is worse than one that refuses to start." );
	}

	/** Whether this profile lets {@code term} carry an amount at all. */
	public boolean allows( RewardTerm term ){
		if (this == DEFAULT) return true;

		switch (term) {
			case DEPTH_ADVANCE:
			case BOSS_SLAIN:
			case VICTORY:
			case TURN_COST:
			case NO_PROGRESS:
			case DEATH:
			case STALLED:
			case TURN_LIMIT:
				return true;
			default:
				return false;
		}
	}

	/** The terms this profile lets pay, in {@link RewardTerm} order. */
	public static List< RewardTerm > paying( RewardProfile profile ){
		List< RewardTerm > terms = new ArrayList<>();
		for (int i = 0; i < RewardTerm.count(); i++){
			RewardTerm term = RewardTerm.at( i );
			if (profile.allows( term )) terms.add( term );
		}
		return terms;
	}
}