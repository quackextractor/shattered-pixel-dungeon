package com.shatteredpixel.shatteredpixeldungeon.superintelligence.diag;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayList;
import java.util.List;

public class ResourceStats {

	private static final OperatingSystemMXBean OS = ManagementFactory.getOperatingSystemMXBean();
	private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
	private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

	private static long processCpuNanos(){
		if (!(OS instanceof com.sun.management.OperatingSystemMXBean)) return -1;
		long v = ((com.sun.management.OperatingSystemMXBean) OS).getProcessCpuTime();
		return v < 0 ? -1 : v;
	}

	public static double systemCpuLoad(){
		if (!(OS instanceof com.sun.management.OperatingSystemMXBean)) return -1;
		double v = ((com.sun.management.OperatingSystemMXBean) OS).getCpuLoad();
		return v < 0 ? -1 : v;
	}

	public static double processCpuSecondsTotal(){
		long ns = processCpuNanos();
		return ns < 0 ? -1 : ns / 1e9;
	}

	public static double processCpuLoad(){
		if (!(OS instanceof com.sun.management.OperatingSystemMXBean)) return -1;
		double v = ((com.sun.management.OperatingSystemMXBean) OS).getProcessCpuLoad();
		return v < 0 ? -1 : v;
	}

	public static int availableProcessors(){
		return Runtime.getRuntime().availableProcessors();
	}

	public static class Sample {
		public final long wallNanos;
		public final long cpuNanos;
		public final double cpuLoad;
		public final long heapUsed;
		public final long heapCommitted;
		public final long heapMax;
		public final long gcCount;
		public final long gcMillis;
		public final int threads;

		Sample(){
			wallNanos = System.nanoTime();
			cpuNanos = processCpuNanos();
			cpuLoad = processCpuLoad();
			heapUsed = MEMORY.getHeapMemoryUsage().getUsed();
			heapCommitted = MEMORY.getHeapMemoryUsage().getCommitted();
			heapMax = MEMORY.getHeapMemoryUsage().getMax();
			int gc = 0;
			long ms = 0;
			for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()){
				long c = gcBean.getCollectionCount();
				long t = gcBean.getCollectionTime();
				if (c > 0) gc += (int) c;
				if (t > 0) ms += t;
			}
			gcCount = gc;
			gcMillis = ms;
			threads = THREADS.getThreadCount();
		}

		public double heapUsedMb(){ return heapUsed / (1024.0 * 1024.0); }
		public double heapMaxMb(){ return heapMax / (1024.0 * 1024.0); }
	}

	public static class Interval {
		private final Sample from = new Sample();
		private Sample to;

		public Sample from(){ return from; }

		public Interval stop(){
			if (to == null) to = new Sample();
			return this;
		}

		public Sample to(){ return to == null ? from : to; }

		public double wallMillis(){
			return (to().wallNanos - from.wallNanos) / 1e6;
		}

		public double wallSeconds(){ return (to().wallNanos - from.wallNanos) / 1e9; }

		public double cpuSeconds(){
			if (from.cpuNanos < 0 || to().cpuNanos < 0) return -1;
			return (to().cpuNanos - from.cpuNanos) / 1e9;
		}

		public double coresUsed(){
			double s = wallSeconds();
			double c = cpuSeconds();
			return (s <= 0 || c < 0) ? -1 : c / s;
		}

		public double gcMillis(){
			return to().gcMillis - from.gcMillis;
		}

		public long gcCount(){
			return to().gcCount - from.gcCount;
		}

		public double gcFraction(){
			double s = wallSeconds();
			return s <= 0 ? 0 : gcMillis() / 1000.0 / s;
		}
	}

	public static Interval start(){
		return new Interval();
	}

	public static String render( String label, Interval t, long steps, double seconds ){
		StringBuilder sb = new StringBuilder();
		sb.append( String.format( "%-9s %s", label,
				String.format( "%,d steps", steps ) ) );
		sb.append( String.format( "  %,.0f steps/s", stepsPerSecond( steps, seconds ) ) );
		appendUsage( sb, t );
		return sb.toString();
	}

	public static double stepsPerSecond( long steps, double seconds ){
		return seconds <= 0 ? 0 : steps / seconds;
	}

	public static void appendUsage( StringBuilder sb, Interval t ){
		double cores = t.coresUsed();
		if (cores >= 0) sb.append( String.format( "  cores %.2f", cores ) );

		Sample to = t.to();
		sb.append( String.format( "  heap %.0f/%.0fMB",
				to.heapUsedMb(), to.heapMaxMb() ) );

		long gcCount = t.gcCount();
		if (gcCount > 0){
			sb.append( String.format( "  gc %d/%.0fms(%.1f%%)", gcCount, t.gcMillis(), t.gcFraction() * 100 ) );
		}
	}

	public static String renderSummary( String title, Interval t, long steps ){
		Sample to = t.to();
		List<String> lines = new ArrayList<>();

		lines.add( Ansi.wrap( title, Ansi.BOLD + Ansi.CYAN ) );
		lines.add( String.format( "  wall      %.2f s", t.wallSeconds() ) );
		lines.add( String.format( "  throughput %,d steps  (%,.0f/s)",
				steps, stepsPerSecond( steps, t.wallSeconds() ) ) );

		double cores = t.coresUsed();
		lines.add( cores >= 0
				? String.format( "  cpu       %.2f s over %.2f s wall = %.2f cores busy",
						t.cpuSeconds(), t.wallSeconds(), cores )
				: "  cpu       unavailable on this JVM" );

		lines.add( String.format( "  heap      %.0f MB used, %.0f MB committed, %.0f MB max",
				to.heapUsedMb(), to.heapCommitted / (1024.0 * 1024.0), to.heapMaxMb() ) );

		if (t.gcCount() > 0){
			lines.add( String.format( "  gc        %d collections, %.0f ms total (%.1f%% of wall)",
					t.gcCount(), t.gcMillis(), t.gcFraction() * 100 ) );
		} else {
			lines.add( "  gc        none" );
		}

		lines.add( "  threads   " + to.threads );

		return String.join( System.lineSeparator(), lines );
	}
}
