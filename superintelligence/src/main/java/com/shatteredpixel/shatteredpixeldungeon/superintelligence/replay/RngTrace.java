package com.shatteredpixel.shatteredpixeldungeon.superintelligence.replay;
import com.watabou.utils.RandomTrace;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
public class RngTrace {
	private static final String HEADER =
			"# spd-rng-trace v3\tstep\tbaseDraws\tbaseFingerprint\tsites";
	private final List< String > lines = new ArrayList<>();
	private final java.util.Map< String, Integer > previousSites = new java.util.HashMap<>();
	public RngTrace(){
		lines.add( HEADER );
	}
	public void dungeonSeed( long seed ){
		lines.add( "# dungeonSeed=" + seed );
	}
	public void onReset(){
		generationBaseDraws = RandomTrace.baseDraws();
		generationBaseFingerprint = RandomTrace.baseFingerprint();
		RandomTrace.reset();
		previousSites.clear();
		previousSites.putAll( RandomTrace.sites() );
		StringBuilder window = new StringBuilder();
		for (java.util.Map.Entry< String, Integer > e : RandomTrace.sites().entrySet()){
			if (e.getValue() <= 0) continue;
			if (window.length() > 0) window.append( ',' );
			window.append( e.getKey() ).append( '=' ).append( e.getValue() );
		}
		lines.add( "# generationSites=" + window );
		dungeonSeed( com.shatteredpixel.shatteredpixeldungeon.Dungeon.seed );
		lines.add( "# generationBaseDraws=" + generationBaseDraws
				+ " generationBaseFingerprint=" + generationBaseFingerprint );
	}
	private long generationBaseDraws;
	private long generationBaseFingerprint;
	private String sitesThisStep(){
		java.util.Map< String, Integer > current = RandomTrace.sites();
		java.util.List< String > parts = new ArrayList<>();
		for (java.util.Map.Entry< String, Integer > e : current.entrySet()){
			int delta = e.getValue() - previousSites.getOrDefault( e.getKey(), 0 );
			if (delta > 0) parts.add( e.getKey() + "=" + delta );
		}
		previousSites.clear();
		previousSites.putAll( current );
		java.util.Collections.sort( parts );
		return parts.isEmpty() ? "-" : String.join( ",", parts );
	}
	public void sample( int step ){
		lines.add( step + "\t" + RandomTrace.baseDraws() + "\t" + RandomTrace.baseFingerprint()
				+ "\t" + sitesThisStep() );
	}
	public ReplayIO.StepObserver observer(){
		return new ReplayIO.StepObserver() {
			@Override public void onReset(){ RngTrace.this.onReset(); }
			@Override public void onStep( int index, Replay.Step step, boolean running ){
				sample( index );
			}
		};
	}
	public int size(){
		return lines.size() - 1;
	}
	public String render(){
		return String.join( "\n", lines ) + "\n";
	}
	public void writeTo( Path path ) throws IOException {
		Files.write( path, render().getBytes( StandardCharsets.UTF_8 ) );
	}
	public static String compare( Path golden, RngTrace actual ){
		java.util.LinkedHashMap< String, String > expected;
		try {
			expected = parse( Files.readAllLines( golden, StandardCharsets.UTF_8 ) );
		} catch (IOException e){
			return "cannot read the golden trace " + golden
					+ " (" + e.getClass().getSimpleName() + ")";
		}
		java.util.LinkedHashMap< String, String > got = parse( actual.lines );
		if (expected.isEmpty()){
			return "golden trace " + golden + " has no samples";
		}
		for (java.util.Map.Entry< String, String > e : expected.entrySet()){
			String mine = got.get( e.getKey() );
			if (mine == null){
				return "this run produced no trace for step " + e.getKey()
						+ ", which the golden trace has. It stopped early; the golden has "
						+ expected.size() + " samples and this run has " + got.size() + ".";
			}
			if (!e.getValue().equals( mine )){
				return "first divergence at step " + e.getKey() + ":\n"
						+ "  golden: " + e.getValue() + "\n"
						+ "  actual: " + mine;
			}
		}
		for (String label : got.keySet()){
			if (!expected.containsKey( label )){
				return "this run produced a trace for step " + label
						+ ", which the golden trace does not have; it ran further than the recording did";
			}
		}
		return "";
	}
	private static java.util.LinkedHashMap< String, String > parse( List< String > lines ){
		java.util.LinkedHashMap< String, String > out = new java.util.LinkedHashMap<>();
		for (String line : lines){
			if (line == null) continue;
			line = line.trim();
			if (line.isEmpty() || line.startsWith( "#" )) continue;
			int tab = line.indexOf( '\t' );
			if (tab < 0) continue;
			out.put( line.substring( 0, tab ), line.substring( tab + 1 ) );
		}
		return out;
	}
}
