package com.shatteredpixel.shatteredpixeldungeon.superintelligence.train;

import java.io.IOException;
import java.util.List;

public interface JobRunner {

	int size();

	Episode run( int worker, TrainerWorkers.Job job ) throws IOException;

	void push( int worker, byte[] payload ) throws IOException;

	List<byte[]> publish() throws IOException;
}
