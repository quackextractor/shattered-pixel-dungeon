package com.shatteredpixel.shatteredpixeldungeon.superintelligence.scenario;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.AscendedForm;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.PowerOfMany;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.cleric.Trinity;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.BodyForm;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.BeamingRay;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.ClericSpell;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.DivineIntervention;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Flash;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Judgement;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.LifeLinkSpell;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.MindForm;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.SpiritForm;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.Stasis;

import java.util.ArrayList;
import java.util.List;

final class TalentTier4Cleric {

	private TalentTier4Cleric(){}

	static List< Scenario > all(){
		List< Scenario > all = new ArrayList<>();
		all.add( spell( Talent.DIVINE_INTERVENTION, new AscendedForm(), DivineIntervention.INSTANCE,
				"divine intervention was not offered" ));
		all.add( spell( Talent.JUDGEMENT, new AscendedForm(), Judgement.INSTANCE, "judgement was not offered" ));
		all.add( spell( Talent.FLASH, new AscendedForm(), Flash.INSTANCE, "flash was not offered" ));
		all.add( form( Talent.BODY_FORM, () -> BodyForm.duration() > 13, "body form gained no duration" ));
		all.add( form( Talent.MIND_FORM, () -> MindForm.itemLevel() > 2, "mind form conjured no stronger item" ));
		all.add( form( Talent.SPIRIT_FORM, () -> SpiritForm.artifactLevel() > 2, "spirit form strengthened no artifact" ));
		all.add( spell( Talent.BEAMING_RAY, new PowerOfMany(), BeamingRay.INSTANCE, "beaming ray was not offered" ));
		all.add( spell( Talent.LIFE_LINK, new PowerOfMany(), LifeLinkSpell.INSTANCE, "life link was not offered" ));
		all.add( spell( Talent.STASIS, new PowerOfMany(), Stasis.INSTANCE, "stasis was not offered" ));
		return all;
	}

	private static Scenario spell( Talent talent
			, com.shatteredpixel.shatteredpixeldungeon.actors.hero.abilities.ArmorAbility ability
			, ClericSpell spell, String failure ){
		return TalentTier4.of( talent, HeroClass.CLERIC, ability, ( world, kit ) -> {}, d -> {
			TalentFamily.unmask( d, com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.USE );
			d.use( com.shatteredpixel.shatteredpixeldungeon.items.armor.ClassArmor.class );
			if (d.env().mode() == com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvMode.TARGETING)
				d.step( com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.Action.USE,
						d.direction( new World().adjacentFree() ));
		}, () -> () -> TalentTier4.spell( TalentTier4.hero(), spell ), failure );
	}

	private static Scenario form( Talent talent, java.util.function.BooleanSupplier reached, String failure ){
		return TalentTier4.of( talent, HeroClass.CLERIC, new Trinity(), ( world, kit ) -> {}, d -> TalentTier4.useAbility( d ),
				() -> reached, failure );
	}
}
