package com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public enum RewardProfile {

	DEFAULT,
	DEPTH_ONLY;

	public String key(){
		return name().toLowerCase( Locale.ROOT );
	}

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

	public static List< RewardTerm > paying( RewardProfile profile ){
		List< RewardTerm > terms = new ArrayList<>();
		for (int i = 0; i < RewardTerm.count(); i++){
			RewardTerm term = RewardTerm.at( i );
			if (profile.allows( term )) terms.add( term );
		}
		return terms;
	}
}