package com.shatteredpixel.shatteredpixeldungeon.superintelligence.rl;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CLBufferRegion;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opencl.CL11.CL_BUFFER_CREATE_TYPE_REGION;
import static org.lwjgl.opencl.CL11.clCreateSubBuffer;

final class ClDevice implements AutoCloseable {

	final String name;

	private final long device, context, queue, program;

	private final IntBuffer err = BufferUtils.createIntBuffer( 1 );
	private final PointerBuffer size = BufferUtils.createPointerBuffer( 1 );

	private final PointerBuffer local = BufferUtils.createPointerBuffer( 1 );

	private final List< Long > kernels = new ArrayList<>();
	private long gemm;
	private final java.util.HashMap< Long, String > names = new java.util.HashMap<>();
	private final PointerBuffer event = BufferUtils.createPointerBuffer( 1 );
	private final java.nio.LongBuffer tick = BufferUtils.createLongBuffer( 1 );

	private void profile( long kernel ){
		if (!KernelProfile.ON) return;
		clWaitForEvents( event );
		clGetEventProfilingInfo( event.get( 0 ), CL_PROFILING_COMMAND_START, tick, null );
		long start = tick.get( 0 );
		clGetEventProfilingInfo( event.get( 0 ), CL_PROFILING_COMMAND_END, tick, null );
		KernelProfile.add( names.get( kernel ) + "@" + size.get( 0 ), tick.get( 0 ) - start );
		clReleaseEvent( event.get( 0 ));
	}

	ClDevice( String... sources ){
		device = firstGpu();
		if (device == 0L) throw new IllegalStateException( "no OpenCL GPU device was found" );
		name = deviceString( CL_DEVICE_NAME ).trim();

		PointerBuffer platform = BufferUtils.createPointerBuffer( 1 );
		check( clGetDeviceInfo( device, CL_DEVICE_PLATFORM, platform, null ), "clGetDeviceInfo platform" );
		PointerBuffer properties = BufferUtils.createPointerBuffer( 3 )
				.put( 0, CL_CONTEXT_PLATFORM ).put( 1, platform.get( 0 )).put( 2, 0L );
		PointerBuffer devices = BufferUtils.createPointerBuffer( 1 ).put( 0, device );
		context = clCreateContext( properties, devices, null, 0L, err );
		check( "clCreateContext" );
		queue = clCreateCommandQueue( context, device, KernelProfile.ON ? CL_QUEUE_PROFILING_ENABLE : 0L, err );
		check( "clCreateCommandQueue" );

		StringBuilder text = new StringBuilder();
		for (String s : sources) text.append( source( s )).append( '\n' );
		program = clCreateProgramWithSource( context, text, err );
		check( "clCreateProgramWithSource" );
		if (clBuildProgram( program, device, "", null, 0L ) != CL_SUCCESS){
			throw new IllegalStateException( "OpenCL build failed on " + name + ":\n" + buildLog() );
		}
	}

	private static long firstGpu(){
		IntBuffer count = BufferUtils.createIntBuffer( 1 );
		if (clGetPlatformIDs( null, count ) != CL_SUCCESS || count.get( 0 ) == 0) return 0L;
		PointerBuffer platforms = BufferUtils.createPointerBuffer( count.get( 0 ));
		clGetPlatformIDs( platforms, (IntBuffer) null );
		for (int p = 0; p < platforms.capacity(); p++){
			IntBuffer n = BufferUtils.createIntBuffer( 1 );
			if (clGetDeviceIDs( platforms.get( p ), CL_DEVICE_TYPE_GPU, null, n ) != CL_SUCCESS || n.get( 0 ) == 0) continue;
			PointerBuffer devices = BufferUtils.createPointerBuffer( n.get( 0 ));
			clGetDeviceIDs( platforms.get( p ), CL_DEVICE_TYPE_GPU, devices, (IntBuffer) null );
			return devices.get( 0 );
		}
		return 0L;
	}

	long kernel( String name ){
		long k = clCreateKernel( program, name, err );
		check( "clCreateKernel " + name );
		kernels.add( k );
		names.put( k, name );
		return k;
	}

	long floats( long count ){
		long b = clCreateBuffer( context, CL_MEM_READ_WRITE, Math.max( 4L, 4L * count ), err );
		check( "clCreateBuffer " + count + " floats" );
		return b;
	}

	long bytes( long count ){
		long b = clCreateBuffer( context, CL_MEM_READ_WRITE, Math.max( 4L, count ), err );
		check( "clCreateBuffer " + count + " bytes" );
		return b;
	}

	void release( long... buffers ){
		for (long b : buffers) if (b != 0L) clReleaseMemObject( b );
	}

	void run( long kernel, long global, Object... args ){
		for (int i = 0; i < args.length; i++){
			Object a = args[ i ];
			int status;
			if (a instanceof Long) status = clSetKernelArg1p( kernel, i, (Long) a );
			else if (a instanceof Float) status = clSetKernelArg1f( kernel, i, (Float) a );
			else status = clSetKernelArg1i( kernel, i, (Integer) a );
			check( status, "kernel argument " + i );
		}
		size.put( 0, global );
		check( clEnqueueNDRangeKernel( queue, kernel, 1, null, size, null, null, KernelProfile.ON ? event : null ), "enqueue kernel" );
		profile( kernel );
	}

	int gemm( long a, long b, long c, int M, int N, int K, int sAi, int sAk, int sBk, int sBj, int sCi, int slices ){
		return gemm( a, 0, b, 0, c, 0, M, N, K, sAi, sAk, sBk, sBj, sCi, slices );
	}

	int gemm( long a, int aOff, long b, int bOff, long c, int cOff, int M, int N, int K, int sAi, int sAk, int sBk, int sBj,
			int sCi, int slices ){
		if (gemm == 0L) gemm = kernel( "gemm" );
		int tilesM = ( M + 63 ) / 64, tilesN = ( N + 63 ) / 64;
		int kSlice = ( ( K + slices - 1 ) / slices + 15 ) / 16 * 16;
		slices = ( K + kSlice - 1 ) / kSlice;
		runSized( gemm, (long) slices * tilesM * tilesN, 256, a, b, c, M, N, K, sAi, sAk, sBk, sBj, sCi, kSlice, tilesM, tilesN,
				M * sCi, aOff, bOff, cOff );
		return slices;
	}

	void copy( long src, int srcOffFloats, long dst, int dstOffFloats, int floats ){
		if (floats <= 0) return;
		check( clEnqueueCopyBuffer( queue, src, dst, 4L * srcOffFloats, 4L * dstOffFloats, 4L * floats, null, null ), "copy" );
	}

	int alignFloats(){
		IntBuffer bits = BufferUtils.createIntBuffer( 1 );
		check( clGetDeviceInfo( device, CL_DEVICE_MEM_BASE_ADDR_ALIGN, bits, null ), "clGetDeviceInfo alignment" );
		return Math.max( 1, bits.get( 0 ) / 32 );
	}

	long view( long buffer, int offsetFloats, int lengthFloats ){
		try (MemoryStack stack = MemoryStack.stackPush()){
			CLBufferRegion region = CLBufferRegion.malloc( stack ).origin( 4L * offsetFloats ).size( 4L * Math.max( 1, lengthFloats ));
			long b = clCreateSubBuffer( buffer, CL_MEM_READ_WRITE, CL_BUFFER_CREATE_TYPE_REGION,
					MemoryUtil.memByteBuffer( region.address(), CLBufferRegion.SIZEOF ), err );
			check( "clCreateSubBuffer" );
			return b;
		}
	}

	void runSized( long kernel, long groups, int groupSize, Object... args ){
		for (int i = 0; i < args.length; i++){
			Object a = args[ i ];
			int status;
			if (a instanceof Long) status = clSetKernelArg1p( kernel, i, (Long) a );
			else if (a instanceof Float) status = clSetKernelArg1f( kernel, i, (Float) a );
			else status = clSetKernelArg1i( kernel, i, (Integer) a );
			check( status, "kernel argument " + i );
		}
		size.put( 0, groups * groupSize );
		local.put( 0, groupSize );
		check( clEnqueueNDRangeKernel( queue, kernel, 1, null, size, local, null, KernelProfile.ON ? event : null ), "enqueue kernel" );
		profile( kernel );
	}

	void write( long buffer, int offsetFloats, float[] data ){
		check( clEnqueueWriteBuffer( queue, buffer, true, 4L * offsetFloats, data, null, null ), "write" );
	}

	void read( long buffer, int offsetFloats, float[] into ){
		check( clEnqueueReadBuffer( queue, buffer, true, 4L * offsetFloats, into, null, null ), "read" );
	}

	void write( long buffer, float[] data ){
		check( clEnqueueWriteBuffer( queue, buffer, true, 0, data, null, null ), "write" );
	}

	void write( long buffer, int[] data ){
		check( clEnqueueWriteBuffer( queue, buffer, true, 0, data, null, null ), "write" );
	}

	void write( long buffer, FloatBuffer data ){
		check( clEnqueueWriteBuffer( queue, buffer, true, 0, data, null, null ), "write" );
	}

	void write( long buffer, ByteBuffer data ){
		check( clEnqueueWriteBuffer( queue, buffer, true, 0, data, null, null ), "write" );
	}

	void writeAsync( long buffer, ByteBuffer data ){
		check( clEnqueueWriteBuffer( queue, buffer, false, 0, data, null, null ), "write" );
	}

	void write( long buffer, IntBuffer data ){
		check( clEnqueueWriteBuffer( queue, buffer, true, 0, data, null, null ), "write" );
	}

	void writeAsync( long buffer, int offsetInts, IntBuffer data ){
		check( clEnqueueWriteBuffer( queue, buffer, false, 4L * offsetInts, data, null, null ), "write" );
	}

	void writeAsync( long buffer, IntBuffer data ){
		check( clEnqueueWriteBuffer( queue, buffer, false, 0, data, null, null ), "write" );
	}

	void writeAsync( long buffer, FloatBuffer data ){
		check( clEnqueueWriteBuffer( queue, buffer, false, 0, data, null, null ), "write" );
	}

	void readAsync( long buffer, int offsetFloats, FloatBuffer into ){
		check( clEnqueueReadBuffer( queue, buffer, false, 4L * offsetFloats, into, null, null ), "read" );
	}

	void read( long buffer, float[] into ){
		check( clEnqueueReadBuffer( queue, buffer, true, 0, into, null, null ), "read" );
	}

	void read( long buffer, FloatBuffer into ){
		check( clEnqueueReadBuffer( queue, buffer, true, 0, into, null, null ), "read" );
	}

	@Override
	public void close(){
		clFinish( queue );
		for (long k : kernels) clReleaseKernel( k );
		clReleaseProgram( program );
		clReleaseCommandQueue( queue );
		clReleaseContext( context );
	}

	private void check( String what ){
		check( err.get( 0 ), what );
	}

	static void check( int status, String what ){
		if (status != CL_SUCCESS) throw new IllegalStateException( what + " failed with OpenCL status " + status );
	}

	private String buildLog(){
		clGetProgramBuildInfo( program, device, CL_PROGRAM_BUILD_LOG, (ByteBuffer) null, size );
		ByteBuffer log = BufferUtils.createByteBuffer( (int) size.get( 0 ));
		clGetProgramBuildInfo( program, device, CL_PROGRAM_BUILD_LOG, log, null );
		return StandardCharsets.UTF_8.decode( log ).toString();
	}

	private String deviceString( int param ){
		PointerBuffer n = BufferUtils.createPointerBuffer( 1 );
		clGetDeviceInfo( device, param, (ByteBuffer) null, n );
		ByteBuffer text = BufferUtils.createByteBuffer( (int) n.get( 0 ));
		clGetDeviceInfo( device, param, text, null );
		return StandardCharsets.UTF_8.decode( text ).toString().replace( "\u0000", "" );
	}

	private static String source( String resource ){
		try (InputStream in = ClDevice.class.getResourceAsStream( resource )){
			if (in == null) throw new IllegalStateException( resource + " is missing from the classpath" );
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			in.transferTo( out );
			return out.toString( StandardCharsets.UTF_8 );
		} catch (IOException e){
			throw new IllegalStateException( resource + " could not be read", e );
		}
	}
}
