package com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.noosa.MovieClip;
import com.watabou.utils.Callback;

/**
 * A {@link CharSprite} that resolves animations instantly instead of over time.
 *
 * This is the single most important seam in the headless build. In the real game an attack, a
 * door unlock and a chest open all resolve when the sprite animation finishes, via
 * {@code CharSprite.onComplete}. {@code Actor.process()} additionally blocks on
 * {@code CharSprite.isMoving} until movement animation completes. Both halves of that contract
 * are removed here:
 *
 * <ul>
 *   <li>{@code attack}/{@code operate}/{@code zap}/{@code jump} invoke their callback
 *       synchronously, so {@code Char.onAttackComplete}/{@code onOperateComplete} - which apply
 *       damage, consume keys and spend turns - run before the actor yields.</li>
 *   <li>{@code move} and {@code play} do nothing and never set {@code isMoving}, so the
 *       scheduler never has to wait.</li>
 * </ul>
 *
 * Turn semantics are unchanged: {@code Hero.actAttack} still returns false and still spends
 * {@code attackDelay()} from inside {@code Hero.onAttackComplete}, exactly as it would once the
 * animation finished. Only the wall-clock time the animation occupied is removed.
 *
 * No GL object is ever created. See {@link #link} for the second half of the story - linking
 * normally pulls shadow, aura and health-bar visuals out of {@code Assets}.
 */
public class HeadlessSprite extends CharSprite {

	public HeadlessSprite(){
		super();
		//CharSprite's constructor chain is Gizmo -> Visual -> Image, all of which only allocate
		//PointF scratch space, so it is safe with no GL context.
	}

	/**
	 * The real implementation instantiates a shadow, an aura and a health bar out of
	 * {@code Assets}. Headlessly we only need the back-reference, because {@code ch.sprite}
	 * must be non-null: {@code Hero.ready()} calls {@code sprite.looping()}/{@code idle()} and
	 * several Char paths dereference it.
	 */
	@Override
	public void link( Char ch ){
		this.ch = ch;
		ch.sprite = this;
		renderShadow = false;
	}

	@Override
	public void linkVisuals( Char ch ){
		//no shadow, aura or health bar without Assets
	}

	// ---------------------------------------------------------------- animations that gate the turn

	@Override
	public void attack( int cell ){
		attack( cell, null );
	}

	@Override
	public synchronized void attack( int cell, Callback callback ){
		if (callback != null){
			callback.call();
		} else if (ch != null){
			//mirrors CharSprite.onComplete's null-callback branch
			ch.onAttackComplete();
		}
	}

	@Override
	public void operate( int cell ){
		operate( cell, null );
	}

	@Override
	public synchronized void operate( int cell, Callback callback ){
		if (callback != null){
			callback.call();
		} else if (ch != null){
			ch.onOperateComplete();
		}
	}

	@Override
	public void zap( int cell ){
		zap( cell, null );
	}

	@Override
	public synchronized void zap( int cell, Callback callback ){
		if (callback != null){
			callback.call();
		}
	}

	@Override
	public void jump( int from, int to, Callback callback ){
		if (callback != null){
			callback.call();
		}
	}

	@Override
	public void jump( int from, int to, float height, float duration, Callback callback ){
		if (callback != null){
			callback.call();
		}
	}

	@Override
	public void die(){
		//Not `ch.die( ch )`. The real sprite plays a death animation and invokes the callback when it
		//finishes; calling it here recursed forever, because the callback re-enters Hero.die ->
		//Char.die -> sprite.die -> callback -> Hero.die. Hero.die's own guard does not stop it either:
		//it returns early only on a repeated *cause*, and each bounce passes a different Char.
		//Every death therefore blew the stack, which is how this was found: the first episode to reach
		//a real death crashed its worker with StackOverflowError rather than recording the DEATH that
		//GAE treats as a terminal state.
		//
		//Nothing to do. The callback is the animation finishing, and there is no animation here.
		//
		//Verified by mutation: restoring the callback makes rewardcheck die with StackOverflowError
		//rather than reporting a failed case - the recursion takes the whole JVM down before any
		//assertion can run. That is also why the fix has no test-shaped failure; the gate asserts the
		//death is recorded, and the mutant cannot reach it.
	}

	// ---------------------------------------------------------------- animations with no turn effect

	@Override
	public void move( int from, int to ){
		//intentionally does not set isMoving and creates no PosTweener
	}

	@Override
	public void play( MovieClip.Animation anim ){
		//no frame bookkeeping: headless there is no frame rate
	}

	@Override
	public void idle(){
		//no-op
	}

	@Override
	public void turnTo( int from, int to ){
		//flipHorizontal is presentation only
	}

	@Override
	public boolean looping(){
		return false;
	}

	@Override
	public void update(){
		//no tweener progression, no particles
	}

	@Override
	public void destroy(){
		//the superclass nulls out emitters and calls Gizmo.destroy; nothing here needs doing,
		//but it is overridden so no Visual/Group teardown path can touch GL later.
		exists = false;
	}

	@Override
	public void showStatus( int color, String label, Object... args ){ }
	@Override public void showStatusWithIcon( int color, String label, int icon, Object... args ){ }
	@Override public void flash(){ }
	@Override public void aura( int color, int duration ){ }
	@Override public void clearAura(){ }
	@Override public void add( State state ){ }
	@Override public void remove( State state ){ }
	@Override public void resetColor(){ }
	@Override public void interruptMotion(){ }
	@Override public void place( int cell ){ }
	@Override public void burst( int type, int particles ){ }
	@Override public int blood(){ return 0; }
	@Override public void showSleep(){ }
	@Override public void hideSleep(){ }
	@Override public void showAlert(){ }
	@Override public void hideAlert(){ }
	@Override public void showInvestigate(){ }
	@Override public void hideInvestigate(){ }
	@Override public void showLost(){ }
	@Override public void hideLost(){ }
	@Override public void hideEmo(){ }

	/**
	 * {@code CharSprite.kill()} calls {@code super.kill()} and then walks the effect fields.
	 * Headless we only need the actor to leave the scene graph, so the Gizmo flags are set
	 * directly and the effect walk is skipped.
	 */
	@Override
	public void kill(){
		exists = false;
		alive = false;
		visible = false;
	}

	/** Emitter creation is the main remaining route from game logic into the particle system. */
	@Override public com.watabou.noosa.particles.Emitter emitter(){ return null; }
	@Override public com.watabou.noosa.particles.Emitter centerEmitter(){ return null; }
	@Override public com.watabou.noosa.particles.Emitter bottomEmitter(){ return null; }
}
