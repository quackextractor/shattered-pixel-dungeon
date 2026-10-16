package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class TalentPicker {

	private TalentPicker() {}

	public static List<Talent> available( Hero hero ){
		List<Talent> out = new ArrayList<>();

		if (hero == null || !hero.isAlive()) return out;

		int tiers = Math.min( hero.talents.size(), Talent.MAX_TALENT_TIERS );

		for (int i = 0; i < tiers; i++){
			int tier = i + 1;
			if (availablePoints( hero, tier ) <= 0) continue;

			LinkedHashMap<Talent, Integer> tierTalents = hero.talents.get( i );
			if (tierTalents == null) continue;

			for (Talent talent : tierTalents.keySet()){
				Integer points = tierTalents.get( talent );
				if (points == null || points >= talent.maxPoints()) continue;
				out.add( talent );
			}
		}

		return out;
	}

	public static boolean spend( Hero hero, int index ){
		List<Talent> options = available( hero );
		if (index < 0 || index >= options.size()) return false;

		hero.upgradeTalent( options.get( index ) );
		return true;
	}

	public static int spendableCount( Hero hero ){
		if (hero == null) return 0;

		int total = 0;
		int tiers = Math.min( hero.talents.size(), Talent.MAX_TALENT_TIERS );
		for (int tier = 1; tier <= tiers; tier++){
			total += Math.max( 0, availablePoints( hero, tier ) );
		}
		return total;
	}

	private static int availablePoints( Hero hero, int tier ){
		try {
			return hero.talentPointsAvailable( tier );
		} catch (IndexOutOfBoundsException e){
			return 0;
		}
	}
}