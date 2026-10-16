package com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GL31;
import com.badlogic.gdx.graphics.GL32;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.GLVersion;

public class HeadlessGraphics implements Graphics {

	private static final int WIDTH = 480;
	private static final int HEIGHT = 320;
	private static final float DPI = 160f;

	public static HeadlessGraphics install(){
		HeadlessGraphics graphics = new HeadlessGraphics();
		Gdx.graphics = graphics;
		return graphics;
	}

	@Override public boolean isGL30Available(){ return false; }
	@Override public boolean isGL31Available(){ return false; }
	@Override public boolean isGL32Available(){ return false; }

	@Override public GL20 getGL20(){ return Gdx.gl; }
	@Override public GL30 getGL30(){ return null; }
	@Override public GL31 getGL31(){ return null; }
	@Override public GL32 getGL32(){ return null; }

	@Override public void setGL20( GL20 gl20 ){ Gdx.gl = gl20; }
	@Override public void setGL30( GL30 gl30 ){ }
	@Override public void setGL31( GL31 gl31 ){ }
	@Override public void setGL32( GL32 gl32 ){ }

	@Override public int getWidth(){ return WIDTH; }
	@Override public int getHeight(){ return HEIGHT; }
	@Override public int getBackBufferWidth(){ return WIDTH; }
	@Override public int getBackBufferHeight(){ return HEIGHT; }
	@Override public float getBackBufferScale(){ return 1f; }

	@Override public int getSafeInsetLeft(){ return 0; }
	@Override public int getSafeInsetTop(){ return 0; }
	@Override public int getSafeInsetBottom(){ return 0; }
	@Override public int getSafeInsetRight(){ return 0; }

	@Override public float getPpiX(){ return DPI; }
	@Override public float getPpiY(){ return DPI; }
	@Override public float getPpcX(){ return 160f; }
	@Override public float getPpcY(){ return 160f; }
	@Override public float getDensity(){ return 1f; }

	@Override public void setTitle( String title ){ }
	@Override public void setUndecorated( boolean undecorated ){ }
	@Override public void setResizable( boolean resizable ){ }
	@Override public void setVSync( boolean vsync ){ }
	@Override public void setForegroundFPS( int foregroundFPS ){ }
	@Override public void setContinuousRendering( boolean continuousRendering ){ }
	@Override public boolean isContinuousRendering(){ return false; }
	@Override public void requestRendering(){ }
	@Override public Cursor newCursor( Pixmap pixmap, int hotspotX, int hotspotY ){ return null; }
	@Override public void setCursor( Cursor cursor ){ }
	@Override public void setSystemCursor( Cursor.SystemCursor cursor ){ }

	@Override public long getFrameId(){ return 0L; }
	@Override public float getDeltaTime(){ return 1f / 60f; }
	@Override public float getRawDeltaTime(){ return 1f / 60f; }
	@Override public int getFramesPerSecond(){ return 60; }

	@Override public GraphicsType getType(){ return GraphicsType.Mock; }
	@Override public GLVersion getGLVersion(){ return null; }

	@Override public boolean supportsDisplayModeChange(){ return false; }
	@Override public Monitor getPrimaryMonitor(){ return null; }
	@Override public Monitor getMonitor(){ return null; }
	@Override public Monitor[] getMonitors(){ return new Monitor[0]; }
	@Override public DisplayMode[] getDisplayModes(){ return new DisplayMode[0]; }
	@Override public DisplayMode[] getDisplayModes( Monitor monitor ){ return new DisplayMode[0]; }
	@Override public DisplayMode getDisplayMode(){ return null; }
	@Override public DisplayMode getDisplayMode( Monitor monitor ){ return null; }
	@Override public boolean setFullscreenMode( DisplayMode displayMode ){ return false; }
	@Override public boolean setWindowedMode( int width, int height ){ return false; }
	@Override public boolean isFullscreen(){ return false; }

	@Override public BufferFormat getBufferFormat(){ return null; }
	@Override public boolean supportsExtension( String extension ){ return false; }
}