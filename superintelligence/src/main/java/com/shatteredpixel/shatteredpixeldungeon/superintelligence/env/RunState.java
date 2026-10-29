package com.shatteredpixel.shatteredpixeldungeon.superintelligence.env;

import com.shatteredpixel.shatteredpixeldungeon.Bones;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.superintelligence.headless.HeadlessServices;

public class RunState {

	private RunState() {}

	public static void clearRunStatics(){

		GameScene.clearPendingCellListener();

		GameScene.clearHeadlessWindow();

		GameScene.clearSelector();

		SlotAction.clearPendingUseItem();

		com.shatteredpixel.shatteredpixeldungeon.levels.features.Chasm.jumpConfirmed = false;

		com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.fallIntoPit = false;

		com.shatteredpixel.shatteredpixeldungeon.journal.Document.resetPages();

		com.shatteredpixel.shatteredpixeldungeon.utils.Holiday.pin( com.shatteredpixel.shatteredpixeldungeon.utils.Holiday.NONE );

		forgetPreviousRemains();
	}

	private static final String BONES_FILE = "bones.dat";

	private static void forgetPreviousRemains(){
		Bones.clear();

		try {
			if (HeadlessServices.installed && HeadlessServices.files() != null){
				HeadlessServices.files().deleteSave( BONES_FILE );
			} else {
				com.badlogic.gdx.files.FileHandle remains =
						com.watabou.utils.FileUtils.getFileHandle( BONES_FILE );
				if (remains != null && remains.exists()) remains.delete();
			}
		} catch (RuntimeException ignored){

		}
	}

	public static void clearRemains(){
		forgetPreviousRemains();
	}
}
