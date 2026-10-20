package com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.DM100;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Eye;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Spinner;
import com.shatteredpixel.shatteredpixeldungeon.sprites.CharSprite;
import com.watabou.noosa.MovieClip;
import com.watabou.utils.Callback;

public class HeadlessSprite extends CharSprite implements HeadlessStandIn {

	public HeadlessSprite(){
		super();

	}

	@Override
	public void link( Char ch ){
		this.ch = ch;
		ch.sprite = this;
		renderShadow = false;
		parent = headlessParent();
	}

	@Override
	public void linkVisuals( Char ch ){

	}

	private com.watabou.noosa.Group headlessParentStore = null;

	private com.watabou.noosa.Group headlessParent(){
		if (headlessParentStore == null){
			headlessParentStore = new com.watabou.noosa.Group();
		}
		return headlessParentStore;
	}

	@Override
	public void attack( int cell ){
		attack( cell, null );
	}

	@Override
	public synchronized void attack( int cell, Callback callback ){
		if (callback != null){
			callback.call();
		} else if (ch != null){

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
		if (ch instanceof Spinner) ((Spinner) ch).shootWeb();
		else if (ch instanceof Eye){ ((Eye) ch).deathGaze(); ch.next(); }
		else if (ch instanceof DM100) ch.next();
		else if (ch != null) completeZap( ch );
	}

	private static void completeZap( Char ch ){
		try {
			java.lang.reflect.Method complete = ch.getClass().getMethod( "onZapComplete" );
			complete.setAccessible( true );
			complete.invoke( ch );
		} catch (NoSuchMethodException e){
			return;
		} catch (ReflectiveOperationException e){
			throw new IllegalStateException( "onZapComplete failed on " + ch.getClass().getName(), e );
		}
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

	}

	@Override
	public void move( int from, int to ){

	}

	@Override
	public void play( MovieClip.Animation anim ){
		if (anim != null && !anim.looped && ch != null) onComplete( anim );
	}

	@Override
	public void idle(){

	}

	@Override
	public void turnTo( int from, int to ){

	}

	@Override
	public boolean looping(){
		return false;
	}

	@Override
	public void update(){

	}

	@Override
	public void destroy(){

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

	@Override
	public void kill(){
		exists = false;
		alive = false;
		visible = false;
	}

	private static final class Inert extends com.watabou.noosa.particles.Emitter {

		@Override public void start( com.watabou.noosa.particles.Emitter.Factory factory,
				float interval, int quantity ){
			com.watabou.utils.PRandom.Float( interval );
		}

		@Override public void burst( com.watabou.noosa.particles.Emitter.Factory factory, int quantity ){
			start( factory, 0, quantity );
		}

		@Override public void pour( com.watabou.noosa.particles.Emitter.Factory factory, float interval ){
			start( factory, interval, 0 );
		}

		@Override public void startDelayed( com.watabou.noosa.particles.Emitter.Factory factory,
				float interval, int quantity, float delay ){}
	}

	private static final Inert emitter = new Inert();

	@Override public com.watabou.noosa.particles.Emitter emitter(){ return emitter; }
	@Override public com.watabou.noosa.particles.Emitter centerEmitter(){ return emitter; }
	@Override public com.watabou.noosa.particles.Emitter bottomEmitter(){ return emitter; }
}
