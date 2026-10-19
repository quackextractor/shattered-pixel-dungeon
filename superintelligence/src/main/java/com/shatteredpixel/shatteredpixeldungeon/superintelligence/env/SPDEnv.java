package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessGame;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.obs.ObservationEncoder;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.Curriculum;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardLedger;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardModel;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.reward.RewardTerm;
public class SPDEnv {
	private final EnvConfig config;
	private final HeadlessGame game;
	private final LevelPipeline pipeline;
	private final ActionMapper mapper;
	private final ObservationEncoder encoder;
	private final RewardLedger ledger;
	private final Curriculum curriculum;
	private final RewardModel reward;
	private final float[] actionMask = new float[ Action.size() ];
	private final float[] slotMask;
	private final float[] targetMask = new float[ ActionMapper.TARGET_COUNT ];
	private EnvMode mode = EnvMode.WORLD;
	private int turnsThisFloor;
	private final java.util.HashMap< Integer, Integer > turnsByFloor = new java.util.HashMap<>();
	private int turnsTotal;
	private int lastDepth = 1;
	private int lastBranch = 0;
	private int stallCount;
	private int freeStepsThisFloor;
	private int lastStallPos = -1;
	private float lastStallHp;
	private StallCause stallCause = StallCause.NONE;
	private EnvMode stepEntered = EnvMode.WORLD;
	private float stepClock;
	private boolean running;
	private boolean terminated;
	private boolean truncated;
	private RewardModel.TerminateReason endReason = RewardModel.TerminateReason.OTHER;
	private String seedText = "";
	private HeroClass heroClass = HeroClass.WARRIOR;
	public SPDEnv( EnvConfig config, HeadlessGame game ){
		this.config = config;
		this.game = game;
		this.pipeline = new LevelPipeline( game );
		this.mapper = new ActionMapper( config );
		this.encoder = new ObservationEncoder( config, mapper );
		this.ledger = new RewardLedger();
		this.ledger.profile( config.rewardProfile );
		this.curriculum = new Curriculum( config );
		this.reward = new RewardModel( config, ledger, curriculum );
		this.slotMask = new float[ config.maxSlots ];
	}
	public LevelPipeline pipeline(){
		return pipeline;
	}
	public double reset( String seed, HeroClass heroClass ){
		double previous = ledger.total();
		ledger.reset();
		curriculum.reset();
		ledger.shapingScale( curriculum.shapingScale() );
		reward.resetSnapshot();
		this.seedText = (seed == null) ? "" : seed;
		this.heroClass = heroClass;
		RunState.clearRunStatics();
		pipeline.startRun( seedText, heroClass, 0 );
		if (seedText.isEmpty()){
			seedText = com.shatteredpixel.shatteredpixeldungeon.superintelligence.utils
					.DungeonSeedCodes.encode( LevelPipeline.currentSeed() );
		}
		mode = EnvMode.WORLD;
		turnsThisFloor = 0;
		turnsByFloor.clear();
		turnsTotal = 0;
		lastDepth = 1;
		lastBranch = 0;
		stallCount = 0;
		freeStepsThisFloor = 0;
		lastStallPos = -1;
		lastStallHp = Dungeon.hero.HP;
		terminated = false;
		truncated = false;
		endReason = RewardModel.TerminateReason.OTHER;
		running = true;
		mapper.clearZeroTimeModeExit();
		encoder.resetExplored();
		mapper.refreshSlots();
		settle();
		ledger.beginFloor( Dungeon.depth, Dungeon.branch );
		encoder.encode();
		refreshMasks();
		return previous;
	}
	public double step( Action action, int slot ){
		if (!running) throw new IllegalStateException( "step() after the episode ended" );
		GameScene.clearPendingCellListener();
		if (mode != EnvMode.TARGETING){
			SlotAction.clearPendingUseItem();
		}
		mapper.refreshSlots();
		boolean acted = false;
		stepEntered = mode;
		stepClock = Actor.now();
if (mode == EnvMode.WORLD){
			acted = mapper.apply( action, slot );
			if (action == Action.OPEN_INVENTORY ){
				mode = EnvMode.INVENTORY;
				acted = true;
			} else if (action == Action.TALENT ){
				mode = EnvMode.TALENT;
				acted = true;
			} else if (action == Action.ALCHEMY ){
				mode = EnvMode.ALCHEMY;
				acted = true;
			} else if (action == Action.USE || action == Action.DROP ){
				mode = EnvMode.SLOT;
				if (WindowBridge.open()) mode = EnvMode.MENU;
				acted = true;
			}
		} else if (mode == EnvMode.MENU){
			acted = mapper.applySecondary( mode, action, slot );
			mode = EnvMode.WORLD;
		} else if (mode == EnvMode.TALENT){
			acted = (action == Action.CANCEL) || mapper.applySecondary( mode, action, slot );
			mode = EnvMode.WORLD;
		} else if (mode == EnvMode.ALCHEMY ){
			acted = (action == Action.CANCEL) || mapper.applySecondary( mode, action, slot );
			mode = EnvMode.WORLD;
			if (mapper.craftedMeatPie()){
				mapper.clearCraftedMeatPie();
				reward.note( RewardTerm.CRAFTED_MEAT_PIE, config.craftMeatPieReward );
			}
		} else if (mode == EnvMode.SLOT){
			boolean needsTarget = false;
			if (action == Action.USE){
				com.shatteredpixel.shatteredpixeldungeon.items.Item item = mapper.slot( slot );
				if (item != null){
					needsTarget = SlotAction.use( Dungeon.hero, item, config.allowEquipping );
				}
			} else {
				acted = mapper.applySecondary( mode, action, slot );
			}
			mode = needsTarget ? EnvMode.TARGETING : EnvMode.WORLD;
			acted = true;
} else if (mode == EnvMode.TARGETING){
			if (action == Action.CANCEL){
				acted = SlotAction.cancelPendingUse( Dungeon.hero );
			} else {
				acted = mapper.applySecondary( mode, action, slot );
			}
			mode = EnvMode.WORLD;
		} else if (mode == EnvMode.INVENTORY){
			boolean needsTarget = false;
			if (action == Action.USE){
				com.shatteredpixel.shatteredpixeldungeon.items.Item item = mapper.slot( slot );
				if (item != null){
					needsTarget = SlotAction.use( Dungeon.hero, item, config.allowEquipping );
				}
			} else {
				acted = mapper.applySecondary( mode, action, slot );
			}
			mode = needsTarget ? EnvMode.TARGETING : EnvMode.WORLD;
			acted = true;
		}
		lastStepActed = acted;
		if (!acted){
			reward.noteEvent( RewardTerm.INVALID_ACTION );
		}
		double accumulated = settle();
		return accumulated;
	}
	private void refreshMasksAfterDrain(){
		if (stepEntered != EnvMode.WORLD && mode == EnvMode.WORLD
				&& Actor.now() == stepClock){
			mapper.noteZeroTimeModeExit();
		}
		refreshMasks();
	}
	private double settle(){
		double accumulated = 0;
		int guard = 0;
		while (running){
if (++guard > config.actorStepLimit){
				stallCause = StallCause.ACTOR_STEP_LIMIT;
				terminate( RewardModel.TerminateReason.STALLED );
				break;
			}
			if (WindowBridge.open()){
				WindowBridge.age( config );
				mode = EnvMode.MENU;
				break;
			}
			if (GameScene.pendingCellListener() != null){
				mode = EnvMode.TARGETING;
				break;
			}
			LevelPipeline.Outcome outcome = pipeline.runToHeroReady( config.actorStepLimit );
			if (outcome == LevelPipeline.Outcome.TRANSITION){
				accumulated += onFloorTransition();
				turnsThisFloor++;
				turnsTotal++;
				accumulated += settleLanding();
				if (!running) break;
				accumulated += reward.step( encoder.encode(), true, menuNoop() );
				checkFloorLimits();
				mapper.refreshSlots();
				refreshMasksAfterDrain();
				break;
			}
			if (outcome == LevelPipeline.Outcome.HERO_DEAD){
				accumulated += terminate( RewardModel.TerminateReason.DEATH );
				break;
			}
			if (outcome == LevelPipeline.Outcome.STALLED || outcome == LevelPipeline.Outcome.STEP_LIMIT){
				stallCause = StallCause.PIPELINE;
				accumulated += terminate( RewardModel.TerminateReason.STALLED );
				break;
			}
			turnsThisFloor++;
			turnsTotal++;
			int newlyExplored = encoder.encode();
			accumulated += reward.step( newlyExplored, true, menuNoop() );
			checkFloorLimits();
			if (!running) break;
			mapper.refreshSlots();
			refreshMasksAfterDrain();
			if (WindowBridge.open()){
				WindowBridge.age( config );
				mode = EnvMode.MENU;
			}
			break;
		}
		return accumulated;
	}
	private double settleLanding(){
		LevelPipeline.Outcome landed = pipeline.runToHeroReady( config.actorStepLimit );
		if (landed == LevelPipeline.Outcome.HERO_DEAD){
			return terminate( RewardModel.TerminateReason.DEATH );
		}
		if (landed == LevelPipeline.Outcome.STALLED || landed == LevelPipeline.Outcome.STEP_LIMIT){
			stallCause = StallCause.PIPELINE;
			return terminate( RewardModel.TerminateReason.STALLED );
		}
		return 0;
	}
	private double onFloorTransition(){
		LevelPipeline.Transition transition = pipeline.handleTransition();
		if (transition == LevelPipeline.Transition.FALL){
			pipeline.handleFall();
		}
		int depth = Dungeon.depth;
		int branch = Dungeon.branch;
		boolean newFloor = depth > curriculum.deepestSeen();
		curriculum.observe( depth );
		ledger.shapingScale( curriculum.shapingScale() );
		if (newFloor && lastBranch == 0 && Dungeon.bossLevel( lastDepth )){
			reward.note( RewardTerm.BOSS_SLAIN, config.depthReward );
		}
		if (wonTheGame()){
			settleHeroOnLanding();
			return terminate( RewardModel.TerminateReason.VICTORY );
		}
		if (depth != lastDepth || branch != lastBranch){
			if (depth > lastDepth){
				ledger.markFloorCleared();
			}
			turnsByFloor.put( lastDepth + 1000 * lastBranch, turnsThisFloor );
			turnsThisFloor = turnsByFloor.getOrDefault( depth + 1000 * branch, 0 );
			lastDepth = depth;
			lastBranch = branch;
			encoder.resetExplored();
			mapper.refreshSlots();
			stallCount = 0;
			freeStepsThisFloor = 0;
			lastStallPos = -1;
			ledger.beginFloor( depth, branch );
		}
		refreshMasksAfterDrain();
		return ledger.flushTurn();
	}
	private static boolean wonTheGame(){
		return com.shatteredpixel.shatteredpixeldungeon.Statistics.ascended
				|| Dungeon.hero.belongings.getItem( com.shatteredpixel.shatteredpixeldungeon.items.Amulet.class ) != null;
	}
	private void settleHeroOnLanding(){
		mapper.refreshSlots();
		encoder.encode();
	}
	private void checkFloorLimits(){
		if (turnsThisFloor >= config.turnLimitPerFloor){
			terminate( RewardModel.TerminateReason.TURN_LIMIT );
			return;
		}
		if (turnsTotal >= config.turnLimitTotal){
			terminate( RewardModel.TerminateReason.TURN_LIMIT );
			return;
		}
		Hero hero = Dungeon.hero;
		if (hero.pos == lastStallPos && hero.HP == lastStallHp){
			stallCount++;
			if (stallCount >= config.stallLimit){
				stallCause = StallCause.POSITION;
				terminate( RewardModel.TerminateReason.STALLED );
				return;
			}
		} else {
			stallCount = 0;
			lastStallPos = hero.pos;
			lastStallHp = hero.HP;
		}
if (!reward.lastStepAdvancedClock()){
			freeStepsThisFloor++;
			if (freeStepsThisFloor >= config.freeStepBudget){
				stallCause = StallCause.FREE_STEP;
				terminate( RewardModel.TerminateReason.STALLED );
			}
		}
	}
	private double terminate( RewardModel.TerminateReason reason ){
		if (terminated) return 0;
		endReason = reason;
		terminated = true;
		truncated = SPDEnv.isTruncation( reason );
		running = false;
		return reward.terminate( reason );
	}
	public static boolean isTruncation( RewardModel.TerminateReason reason ){
		return !isNaturalEnding( reason );
	}
	public EnvMode mode(){ return mode; }
	public boolean running(){ return running; }
	public boolean terminated(){ return terminated; }
	public boolean truncated(){ return truncated; }
	public RewardModel.TerminateReason endReason(){ return endReason; }
	public boolean endedNaturally(){
		return isNaturalEnding( endReason );
	}
	public static boolean isNaturalEnding( RewardModel.TerminateReason reason ){
		return reason == RewardModel.TerminateReason.DEATH
				|| reason == RewardModel.TerminateReason.VICTORY;
	}
public float[] actionMask(){ return actionMask; }
	public StallCause stallCause(){ return stallCause; }

	public boolean lastStepActed(){ return lastStepActed; }

	private boolean lastStepActed = true;
	public boolean lastStepAdvancedClock(){ return reward.lastStepAdvancedClock(); }
	public float[] slotMask(){ return slotMask; }
	public float[] targetMask(){ return targetMask; }
	private boolean menuNoop(){
		return stepEntered != EnvMode.WORLD && mode == stepEntered && Actor.now() <= stepClock;
	}
	private final float[] actionLogits = new float[ Action.size() ];
	private final float[] slotLogits = new float[ 128 ];
	private final float[] targetLogits = new float[ ActionMapper.TARGET_COUNT ];
	public float[] actionLogitsPreview(){ return actionLogits; }
	public float[] slotLogitsPreview(){ return slotLogits; }
	public float[] targetLogitsPreview(){ return targetLogits; }
	public void setPolicyOutputs( float[] actions, float[] slots, float[] targets ){
		System.arraycopy( actions, 0, actionLogits, 0,
				Math.min( actions.length, actionLogits.length ) );
		System.arraycopy( slots, 0, slotLogits, 0, Math.min( slots.length, slotLogits.length ) );
		System.arraycopy( targets, 0, targetLogits, 0, Math.min( targets.length, targetLogits.length ) );
	}
	public float[] grid(){ return encoder.grid(); }
	public float[] inventory(){ return encoder.inventory(); }
	public float[] heroFeatures(){ return encoder.hero(); }
	public int gridWidth(){ return encoder.gridWidth(); }
	public int gridHeight(){ return encoder.gridHeight(); }
	public int spatialChannels(){ return encoder.spatialChannels(); }
	public RewardLedger ledger(){ return ledger; }
	public float shapingGamma(){ return reward.gamma(); }
	public Curriculum curriculum(){ return curriculum; }
	public ActionMapper mapper(){ return mapper; }
	public EnvConfig config(){ return config; }
	public String seedText(){ return seedText; }
	public int turnsTotal(){ return turnsTotal; }
	public int depth(){ return Dungeon.depth; }
	public int deepestDepth(){ return com.shatteredpixel.shatteredpixeldungeon.Statistics.deepestFloor; }
	public int heroPosition(){ return Dungeon.hero.pos; }
	private void refreshMasks(){
		mapper.actionMask( mode, actionMask );
		mapper.slotMask( mode, slotMask );
		for (int i = 0; i < targetMask.length; i++){
			targetMask[ i ] = mapper.targetMask( i ) ? 1f : 0f;
		}
		if (mode == EnvMode.WORLD) mapper.interactMask( targetMask );
	}
	public void abandon(){
		running = false;
		terminated = true;
	}
	public String describeMode(){
		switch (mode) {
			case MENU:      return "menu(" + WindowBridge.describe() + ")";
			case TARGETING: return "targeting";
			case SLOT:      return "slot";
			case INVENTORY: return "inventory";
			case TALENT:    return "talent(" + talentChoices().size() + " choices)";
			default:        return "world";
		}
	}
	public boolean windowOptionSelectable( int index ){
		return WindowBridge.optionSelectable( index );
	}
	public int windowOptionCount(){
		return WindowBridge.optionCount();
	}
	public java.util.List<com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent> talentChoices(){
		return mapper.talentChoices();
	}
}
