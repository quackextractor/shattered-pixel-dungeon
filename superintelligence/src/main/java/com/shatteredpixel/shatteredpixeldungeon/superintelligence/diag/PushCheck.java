package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.TrainerWorkers;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag.GateReport.fail;

public class PushCheck {

	private static final int CHECKS = 8;

	public static void main( String[] args ){		int chunk = TrainerWorkers.pushChunkBytes;
		byte[] payload = payload( 3, chunk );

		checkNoWriteExceedsTheChunk( chunk, payload );
		checkTheBytesArriveIntact( chunk, payload );
		checkAPayloadOfOneChunkIsOneWrite( chunk );
		checkAnEmptyPayloadWritesNothing( chunk );
		checkAFailureIsNotRetried( chunk );
		checkAFailureIsRefusedByName( chunk );
		checkTheWatchdogWaitsOutTheTrainersOwnUpdate();
		checkTheWeightsArriveBitForBit();

		System.exit( GateReport.report( "policy push", CHECKS ));
	}

	private static void checkTheWatchdogWaitsOutTheTrainersOwnUpdate(){
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.WorkerPool pool =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.WorkerPool();
		pool.stallTimeoutMs( 5 );
		pause( 30 );
		boolean idleStalls = pool.stalled();
		pool.trainerBusy( true );
		pause( 30 );
		boolean busyStalls = pool.stalled();
		pool.trainerBusy( false );
		boolean resumedStalls = pool.stalled();

		if (!idleStalls || busyStalls || resumedStalls){
			fail( "the stall watchdog with workers silent past its timeout reported " + idleStalls + ", during the"
					+ " trainer's own update " + busyStalls + ", the moment the update ended " + resumedStalls
					+ "; wanted true, false, false. A PPO update over the timeout killed teach-demo-51 as a"
					+ " worker stall with every worker idle on stdin." );
			return;
		}
		System.out.println( "  the stall watchdog fires on silent workers, not during the trainer's own update" );
	}

	private static void checkTheWeightsArriveBitForBit(){
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig config =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.env.EnvConfig();
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network sent =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network( config, new java.util.Random( 1L ));
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network received =
				new com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network( config, new java.util.Random( 2L ));
		sent.layers()[ 0 ].weights[ 0 ] = Float.intBitsToFloat( 0x7fc00123 );
		sent.layers()[ 0 ].bias[ 0 ] = -0f;
		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Worker.writeWeights( new DataOutputStream( buffer ), sent );
			com.shatteredpixel.shatteredpixeldungeon.superintelligence.train.Worker.readWeights(
					new java.io.DataInputStream( new java.io.ByteArrayInputStream( buffer.toByteArray() )), received );
		} catch (IOException e){
			fail( "a policy push did not round-trip: " + e.getMessage() );
			return;
		}
		com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl.Network.Layer[] a = sent.layers(), b = received.layers();
		for (int i = 0; i < a.length; i++){
			boolean same = Arrays.equals( bits( a[ i ].weights ), bits( b[ i ].weights ))
					&& Arrays.equals( bits( a[ i ].bias ), bits( b[ i ].bias ));
			if (!same){
				fail( "layer " + a[ i ].name + " arrived with different bits after a policy push" );
				return;
			}
		}
		System.out.println( "  a policy push delivers every layer bit for bit, NaN payload and -0 included" );
	}

	private static int[] bits( float[] values ){
		int[] out = new int[ values.length ];
		for (int i = 0; i < values.length; i++) out[ i ] = Float.floatToRawIntBits( values[ i ] );
		return out;
	}

	private static void pause( long ms ){
		try {
			Thread.sleep( ms );
		} catch (InterruptedException e){
			Thread.currentThread().interrupt();
		}
	}

	private static void checkNoWriteExceedsTheChunk( int chunk, byte[] payload ){
		Recording recording = new Recording( payload.length + 1 );

		try {
			TrainerWorkers.writeFully( new DataOutputStream( recording ), "policy", payload );
		} catch (IOException e){
			fail( "a " + payload.length + " byte payload was refused: " + e.getMessage() );
			return;
		}

		if (recording.sizes.isEmpty()){
			fail( "a " + payload.length + " byte payload wrote nothing at all" );
			return;
		}

		for (int size : recording.sizes){
			if (size <= chunk) continue;
			fail( "one write carried " + size + " bytes against a chunk size of " + chunk
					+ ", so the push was not chunked. A Windows anonymous pipe has a 4KB system"
					+ " buffer, and one unchunked write of a whole policy blob is what killed runs"
					+ " at generation ~22 with 'Insufficient system resources exist to complete the"
					+ " requested service'. (issues.md 28)" );
			return;
		}

		if (recording.sizes.size() < 2){
			fail( "a payload of " + recording.sizes.get( 0 ) + " bytes against a chunk size of "
					+ chunk + " was written whole, so the chunk size is not reaching the push" );
		}
	}

	private static void checkTheBytesArriveIntact( int chunk, byte[] payload ){
		ByteArrayOutputStream sink = new ByteArrayOutputStream();

		try {
			TrainerWorkers.writeFully( new DataOutputStream( sink ), "policy", payload );
		} catch (IOException e){
			fail( "a " + payload.length + " byte payload was refused: " + e.getMessage() );
			return;
		}

		if (!Arrays.equals( payload, sink.toByteArray() )){
			fail( "the bytes a worker would read differ from the ones the trainer sent: "
					+ sink.size() + " arrived against " + payload.length + " sent" );
		}
	}

	private static void checkAPayloadOfOneChunkIsOneWrite( int chunk ){
		Recording recording = new Recording( 2 );

		try {
			TrainerWorkers.writeFully( new DataOutputStream( recording ), "policy", payload( 1, chunk ));
		} catch (IOException e){
			fail( "a payload of exactly one chunk was refused: " + e.getMessage() );
			return;
		}

		if (recording.sizes.size() != 1 || recording.sizes.get( 0 ) != chunk){
			fail( "a payload of exactly one chunk took " + recording.sizes.size()
					+ " writes of " + recording.sizes + ", where one write of " + chunk
					+ " is what the loop is written to do" );
		}
	}

	private static void checkAnEmptyPayloadWritesNothing( int chunk ){
		Recording recording = new Recording( 1 );

		try {
			TrainerWorkers.writeFully( new DataOutputStream( recording ), "policy", new byte[ 0 ] );
		} catch (IOException e){
			fail( "an empty payload was refused: " + e.getMessage() );
			return;
		}

		if (!recording.sizes.isEmpty()){
			fail( "an empty payload wrote " + recording.sizes.size() + " times" );
		}
	}

	private static void checkAFailureIsNotRetried( int chunk ){
		byte[] payload = payload( 2, chunk );
		Recording recording = new Recording( payload.length + 1 );
		recording.failFirst = 1;

		try {
			TrainerWorkers.writeFully( new DataOutputStream( recording ), "policy", payload );
			fail( "a push whose first write failed still reported success after "
					+ recording.attempts + " attempts. A buffered stream may already hold part of the"
					+ " failed write, so a retry duplicates bytes and desynchronises the protocol." );
		} catch (IOException e){
			if (recording.attempts != 1){
				fail( "a failed write was followed by " + (recording.attempts - 1)
						+ " more, so the push retried instead of failing fast" );
			}
		}
	}

	private static void checkAFailureIsRefusedByName( int chunk ){
		byte[] payload = payload( 1, chunk );
		Recording recording = new Recording( 8 );
		recording.failFirst = Integer.MAX_VALUE;

		try {
			TrainerWorkers.writeFully( new DataOutputStream( recording ), "weights-for-worker-3", payload );
			fail( "a stream that never accepted a byte still reported a successful push" );
		} catch (IOException e){
			String message = e.getMessage() == null ? "" : e.getMessage();
			if (!message.contains( "weights-for-worker-3" )){
				fail( "a failed push was refused without naming what failed:"
						+ " \"" + message + "\"" );
			} else if (!message.contains( "bytes" )){
				fail( "a failed push was refused without saying how much it"
						+ " refused: \"" + message + "\"" );
			}
		}
	}

	private static byte[] payload( int chunks, int chunk ){
		byte[] payload = new byte[ chunks * chunk ];
		for (int i = 0; i < payload.length; i++) payload[ i ] = (byte) ( i * 31 + 7 );
		return payload;
	}

	private static class Recording extends OutputStream {

		final ByteArrayOutputStream sink = new ByteArrayOutputStream();
		final List<Integer> sizes = new ArrayList<>();
		int limit;
		int failFirst;
		int attempts;

		Recording( int limit ){
			this.limit = limit;
		}

		byte[] delivered(){
			return sink.toByteArray();
		}

		@Override public void write( int b ){
			sink.write( b );
		}

		@Override public void write( byte[] b, int off, int len ) throws IOException{
			if (++attempts <= failFirst) throw new IOException( "simulated pipe exhaustion" );
			sizes.add( len );
			if (sizes.size() > limit) throw new IOException( "wrote more chunks than the test allows" );
			sink.write( b, off, len );
		}
	}
}