package minijava;

import java.net.URI;
import java.util.ArrayList;

import javax.tools.SimpleJavaFileObject;

import minijava.parser.MiniJavaParser;
import minijava.parser.MiniJavaParser.BlockContext;

/**
 * A file object used to represent source coming from a string.
 */
public class Program extends SimpleJavaFileObject implements Comparable<Program> {
	public final static String PROGRAM_CLASS = new String("GeneticProgram");
	public final static String PACKAGE_SPECIES = new String("species");
	public final static String PACKAGE_ID = new String("id");
	public String source;
	public Fitness fitness = new Fitness();
	public ArrayList<ArrayList<Long>> vectors;
	public int species;
	public int ID;
	public ProgramClassLoader programClassLoader = null;
	public MiniJavaParser miniJavaParser = null;
	public BlockContext blockContext = null;
	
	/**
	 * Constructs a new JavaSourceFromString.
	 * 
	 * @param className
	 *            the name of the compilation unit represented by this file object
	 * @param source
	 *            the source code for the compilation unit represented by this file object
	 */
	Program(String source, int species, int ID, int sizeBeforeRestrict, Tests tests) {
		super(URI.create("string:///" + PACKAGE_SPECIES + species + '/' + PACKAGE_ID + ID + '/' + PROGRAM_CLASS + Kind.SOURCE.extension), Kind.SOURCE);
		this.source = new String(source);
		fitness.size = source.length();
		fitness.sizeBeforeRestrict = sizeBeforeRestrict;
		this.species = species;
		this.ID = ID;
		vectors = new ArrayList<ArrayList<Long>>(tests.listTests.size());
		for(int index=0; index<tests.listTests.size(); index++) {
			ArrayList<Long> arrayList = new ArrayList<Long>(tests.listTests.get(index).listTest);
			vectors.add(arrayList);
		}
		programClassLoader = new ProgramClassLoader(ClassLoader.getSystemClassLoader());
	}

	@Override
	public CharSequence getCharContent(boolean ignoreEncodingErrors) {
		return source;
	}
	
	@Override
	public int compareTo(Program program) {
		return fitness.compareTo(program.fitness);
	}
	
}