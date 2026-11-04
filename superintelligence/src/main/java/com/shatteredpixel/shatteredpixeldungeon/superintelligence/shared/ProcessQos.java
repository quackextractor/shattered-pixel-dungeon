package com.shatteredpixel.shatteredpixeldungeon.superintelligence.shared;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

public final class ProcessQos {

	private static final int PROCESS_POWER_THROTTLING = 4, STATE_BYTES = 12, EXECUTION_SPEED = 1;

	private ProcessQos(){}

	public static boolean supported(){
		return System.getProperty( "os.name", "" ).startsWith( "Windows" );
	}

	public static boolean exemptFromThrottling(){
		return call( "SetProcessInformation", EXECUTION_SPEED ) != null;
	}

	public static boolean exempt(){
		int[] state = call( "GetProcessInformation", 0 );
		return state != null && ( state[ 0 ] & EXECUTION_SPEED ) != 0 && ( state[ 1 ] & EXECUTION_SPEED ) == 0;
	}

	@SuppressWarnings( "restricted" )
	private static int[] call( String function, int control ){
		if (!supported()) return null;
		try (Arena arena = Arena.ofConfined()){
			Linker linker = Linker.nativeLinker();
			SymbolLookup kernel32 = SymbolLookup.libraryLookup( "kernel32", arena );
			MethodHandle current = linker.downcallHandle( kernel32.find( "GetCurrentProcess" ).orElseThrow(),
					FunctionDescriptor.of( ADDRESS ));
			MethodHandle target = linker.downcallHandle( kernel32.find( function ).orElseThrow(),
					FunctionDescriptor.of( JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT ));
			MemorySegment state = arena.allocate( STATE_BYTES, 4 );
			state.set( JAVA_INT, 0, 1 );
			state.set( JAVA_INT, 4, control );
			int ok = (int) target.invoke( (MemorySegment) current.invoke(), PROCESS_POWER_THROTTLING, state, STATE_BYTES );
			return ok == 0 ? null : new int[]{ state.get( JAVA_INT, 4 ), state.get( JAVA_INT, 8 ) };
		} catch (Throwable e){
			return null;
		}
	}
}
