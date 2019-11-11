package minijava;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.tools.JavaCompiler.CompilationTask;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import minijava.parser.MiniJavaLexer;
import minijava.parser.MiniJavaParser;

public class Species implements Runnable {
	public Fitness fitnessBest = null;
	public static final int MAX_EXECUTE_MILLISECONDS = 2000;
	public static final int MAX_EXECUTE_MILLISECONDS_95PERCENT = (int) Math.floor(0.95*MAX_EXECUTE_MILLISECONDS);
	public int stagnant = MAX_STAGNANT;
	public int species;
	
	private static final int MAX_STAGNANT = 4;	// number of years a species can live without progress on bestfit
	private static final int MAX_PARENT = 5;	// Size of parent pool
	private static final int MAX_CHILDREN = 3;	// Number of children each parent produces
	public static final int MAX_POPULATION = MAX_PARENT*MAX_CHILDREN + MAX_PARENT;	// Total population size
	private static final int MUTATE_FACTOR = 4;	// CROSSOVER=1, MUTATE=MUTATE_FACTOR, CROSSOVER to MUTATE ratio is 1/MUTATE_FACTOR
	private static final JavaCompiler JAVA_COMPILER = ToolProvider.getSystemJavaCompiler();
	private static final Logger LOGGER = Logger.getLogger(GP.class.getName());
	private Random random = new Random(GP.RANDOM_SEED);
	private static final int MAX_NEW_CODE_SEGMENT_SIZE = 75;
	private static final int MAX_GENERATION_PRESERVE = 3;
	private static final int MIN_GENERATIONAL_FITNESS = 3;	// minimum generational fitness before storage as bestfit
	private List<Program> listProgramParent = new ArrayList<Program>(MAX_PARENT);
	private List<Program> listProgramPopulation = new ArrayList<Program>(MAX_POPULATION);
	private List<CallableMiniJava> listCallable = new ArrayList<CallableMiniJava>(MAX_POPULATION);
	private String stringBestSourceCompact;
	private String stringBestSource;
	private int sizeSourceLength;
	private static int sizeBeforeRestrictMin = 0;
	private static int sizeBeforeRestrictMax = 0;
	private static int sizeBeforeRestrict = 0;
	private static int speedBeforeRestrictMin = 0;
	private static int speedBeforeRestrictMax = 0;
	private static int speedBeforeRestrict = 0;
	private static final double RESTRICT_MIN_PERCENT = 0.975;	// use small seasonal differences (i.e. RESTRICT_MAX_PERCENT-RESTRICT_MIN_PERCENT < 0.05)
	private static final double RESTRICT_MAX_PERCENT = 1.025;
	private Tests tests = null;
	
	private int daysPerYear;
	private final String PROGRAM_FILENAME;
	private int year;
	private int day;
	
	public Species(int species, String sourceOrigin, Tests tests, int daysPerYear) {
		this.species = species;
		this.tests = tests;
		this.daysPerYear = daysPerYear;
		PROGRAM_FILENAME = new String("data" + File.separator + "GeneticProgram" + species + ".java");
		try {
			stringBestSource = new String(Files.readAllBytes(Paths.get(PROGRAM_FILENAME)));
		} catch (IOException e) {
			stringBestSource = sourceOrigin;
			try {
				Files.write(Paths.get(PROGRAM_FILENAME),stringBestSource.getBytes());
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}
		sizeSourceLength = stringBestSource.length();
		stringBestSourceCompact = removeSpace(sourceOrigin);
	}
	
	public void initalizeYear(int year) {
		this.year = year;
		sizeBeforeRestrictMin = (int)(RESTRICT_MIN_PERCENT * sizeSourceLength);
		sizeBeforeRestrictMax = (int)(RESTRICT_MAX_PERCENT * sizeSourceLength);
		speedBeforeRestrictMin = (int)(RESTRICT_MIN_PERCENT * MAX_EXECUTE_MILLISECONDS/2.0);
		speedBeforeRestrictMax = (int)(RESTRICT_MAX_PERCENT * MAX_EXECUTE_MILLISECONDS/2.0);
		stagnant--;
	}
	
	public void extinction() {
		LOGGER.info("EXTY" + year + "S" + species + " " + fitnessBest.toString() + stringBestSource);
		try {
			Files.delete(Paths.get(PROGRAM_FILENAME));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void initalizeDay(int day) {
		if (sizeSourceLength < stringBestSource.length()) {	
			sizeSourceLength++;		// smooth convergence for fitness function
		} else if (sizeSourceLength > stringBestSource.length()) {
			sizeSourceLength--;
		}
		this.day = day;
	}
	
	@Override
	public void run() {
		createEnviroment();
		createPopulation();
		compilePopulation();
		executePopulation();
		evaluatePopulation();
		if(day%1 == 0 && listProgramPopulation!=null && !listProgramPopulation.isEmpty()) {
			LOGGER.info("BY" + year + "D" + day + "S" + listProgramPopulation.get(0).species + "ID" + listProgramPopulation.get(0).ID + " " + listProgramPopulation.get(0).fitness.toString() + listProgramPopulation.get(0).source);
			//LOGGER.info("WY" + year + "D" + day + "S" + listProgramPopulation.get(listProgramPopulation.size()-1).species + "ID" + listProgramPopulation.get(listProgramPopulation.size()-1).ID + " " + listProgramPopulation.get(listProgramPopulation.size()-1).fitness.toString() + listProgramPopulation.get(listProgramPopulation.size()-1).source);
		}
		storeBestFitness();
		downselectPopulation();
	}
	
	public void createEnviroment() {
		// model environment resource (specifically program size) as Summer-Winter-Summer or sizeBeforeRestrictMax-sizeBeforeRestrictMin-sizeBeforeRestrictMax
		double percent = (double)(day%daysPerYear)/daysPerYear;
		double cosineWithOffset = (Math.cos(percent*2*Math.PI)+1)/2;
		sizeBeforeRestrict = (int)(sizeBeforeRestrictMin + cosineWithOffset*(sizeBeforeRestrictMax-sizeBeforeRestrictMin));
		speedBeforeRestrict = (int)(speedBeforeRestrictMin + cosineWithOffset*(speedBeforeRestrictMax-speedBeforeRestrictMin));
	}

	public void createPopulation() {
		int indexPackage = 0;
		String source;
		listProgramPopulation.clear();
		if(listProgramParent.isEmpty()) {
			// create new program using best source. increase generational fitness because program was historically best
			Program program = new Program(stringBestSource, species, 0, sizeBeforeRestrict, speedBeforeRestrict, MIN_GENERATIONAL_FITNESS, MIN_GENERATIONAL_FITNESS, tests);
			listProgramParent.add(program);
			LOGGER.info("RESTARTEDY" + year + "D" + day + "S" + listProgramParent.get(0).species + "ID" + listProgramParent.get(0).ID + " " + listProgramParent.get(0).source);
		}
		//make ANTLR parsers for parents, used by crossover
		for(Program program : listProgramParent) {
			long generation = program.fitness.generation;
			long generationalFitness = program.fitness.generationalFitness;
			source = replacePackage(program.source, species, indexPackage);
			Program programParent = new Program(source, species, indexPackage, sizeBeforeRestrict, speedBeforeRestrict, generation, generationalFitness, tests);
			MiniJavaLexer miniJavaLexer = new MiniJavaLexer(CharStreams.fromString(programParent.source));
			programParent.miniJavaParser = new MiniJavaParser(new CommonTokenStream(miniJavaLexer));	// may contain incorrect ID
			programParent.blockContext = programParent.miniJavaParser.program().block();	// ANTLR only allows one call to program() before EOF error?
			program.miniJavaParser = programParent.miniJavaParser;
			program.blockContext = programParent.blockContext;
			listProgramPopulation.add(programParent);	// add parent to population
			indexPackage++;
		}
		for(Program program : listProgramParent) {
			for(int indexChild=0; indexChild<MAX_CHILDREN; indexChild++) {
				Program program2;
				if(random.nextInt() % MUTATE_FACTOR == 0) {	
					program2 = listProgramParent.get(random.nextInt(listProgramParent.size()));		// program2!=null means crossover
				} else {
					program2 = null;	// program2==null means mutate
				}
				source = createProgram(program, program2, program.source);
				source = replacePackage(source, species, indexPackage);
				listProgramPopulation.add(new Program(source, species, indexPackage, sizeBeforeRestrict, speedBeforeRestrict, 0, 0, tests));	// add child to population
				indexPackage++;
			}
		}
	}
	
	public void compilePopulation() { 
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
		for(Program program : listProgramPopulation) {
			try (StandardJavaFileManager standardJavaFileManager = JAVA_COMPILER.getStandardFileManager(diagnostics, Locale.ENGLISH, null)) {
				Iterable<? extends JavaFileObject> javaFileObject = Arrays.asList(program);
				ProgramClassSimpleJavaFileObject programClassSimpleJavaFileObject = null;
				try {
					programClassSimpleJavaFileObject = new ProgramClassSimpleJavaFileObject(Program.PACKAGE_SPECIES + species + "." + Program.PACKAGE_ID + program.ID + "." + Program.PROGRAM_CLASS);
				} catch (Exception e) {
					program.vectors = null;
					e.printStackTrace();
				}
				ProgramForwardingJavaFileManager programForwardingJavaFileManager = new ProgramForwardingJavaFileManager(standardJavaFileManager, programClassSimpleJavaFileObject, program.programClassLoader);
				CompilationTask compilerTask = JAVA_COMPILER.getTask(null, programForwardingJavaFileManager, diagnostics, null, null, javaFileObject);
				Boolean success = compilerTask.call();
				for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
					program.vectors = null;
					LOGGER.severe(diagnostic.getMessage(null));
			    }
				if (!success) {	//Compile and check for program errors, random code may have compile errors
					if(diagnostics.getDiagnostics().size() != 0) {
						program.vectors = null;
					}
				    
				}
			} catch (IOException e) {
				LOGGER.severe("Must use JDK (Java bin directory must contain javac)");
				e.printStackTrace();
			}
		}
	}
	
	public void executePopulation() {
		List<Program> listProgramPopulationCompleted = new ArrayList<Program>(MAX_POPULATION);
		
		do {	// run all program until completed or eliminated from population
			ExecutorService executorService = Executors.newFixedThreadPool(GP.THREADS_PER_SPECIES-1);
			try {	// Load the class and use it
				listCallable.clear();
				for(Program program : listProgramPopulation) {
					if(program.vectors != null) {
						listCallable.add(new CallableMiniJava(program));
					}
				}
				for(CallableMiniJava callableMiniJava : listCallable) {
					executorService.execute(callableMiniJava);
				}
				executorService.shutdown();
				if(!executorService.awaitTermination(MAX_EXECUTE_MILLISECONDS, TimeUnit.MILLISECONDS)) {
					executorService.shutdownNow();
					int milliseconds = MAX_EXECUTE_MILLISECONDS;
					while(!executorService.awaitTermination(MAX_EXECUTE_MILLISECONDS, TimeUnit.MILLISECONDS)) {
						milliseconds += MAX_EXECUTE_MILLISECONDS;
						LOGGER.warning("Runaway species #" + species + " for " + milliseconds + " milliseconds");
					}
				}
				
				for (Iterator<Program> iteratorProgram = listProgramPopulation.iterator(); iteratorProgram.hasNext();) {
					Program program = iteratorProgram.next();
			        if(program.vectors == null) {	// remove program when vectors is null
			        	iteratorProgram.remove();
			        } else if(program.fitness.speed > MAX_EXECUTE_MILLISECONDS_95PERCENT) {	// remove program when it exceeds MAX_EXECUTE_MILLISECONDS_95PERCENT
			        	iteratorProgram.remove();
			        } else if(program.fitness.isComplete) {	// remove program when completed and add to completed list
			        	iteratorProgram.remove();
			        	listProgramPopulationCompleted.add(program);
			        }
			    }
			} catch (InterruptedException e) {
				e.printStackTrace();
				LOGGER.severe("Exception on species #" + species);
			}
		} while (!listProgramPopulation.isEmpty());
		listProgramPopulation = listProgramPopulationCompleted;
	}
	
	public void evaluatePopulation() {
		for(Program program : listProgramPopulation) {
			BigInteger differenceAndCorrect[] = tests.getDifferences(program.vectors);
			if(differenceAndCorrect == null) {
				program.fitness.difference = Constants.LONG_MAX_VALUE;
				program.fitness.correct = 0;
			} else {
				program.fitness.correct = differenceAndCorrect[1].intValue();
				program.fitness.difference = differenceAndCorrect[0].divide(BigInteger.valueOf(program.vectors.size()));
				program.fitness.calculateFitness();
			}
		}
	}
	
	public void storeBestFitness() {
		if(listProgramPopulation==null || listProgramPopulation.isEmpty()) {
			return;
		}
		Collections.sort(listProgramPopulation);
		if(fitnessBest == null) {
			stagnant = MAX_STAGNANT;
			fitnessBest = listProgramPopulation.get(0).fitness;
			stringBestSource = listProgramPopulation.get(0).source;
			stringBestSourceCompact = removeSpace(stringBestSource);
			LOGGER.info("NEWY" + year + "D" + day + "S" + listProgramPopulation.get(0).species + "ID" + listProgramPopulation.get(0).ID + " " + fitnessBest.toString() + listProgramPopulation.get(0).source);
		} else if(listProgramPopulation.get(0).fitness.generationalFitness>=MIN_GENERATIONAL_FITNESS && fitnessBest.compareTo(listProgramPopulation.get(0).fitness) > 0) {
			stagnant = MAX_STAGNANT;
			fitnessBest = listProgramPopulation.get(0).fitness;
			try {
				LOGGER.info("BSTY" + year + "D" + day + "S" + listProgramPopulation.get(0).species + "ID" + listProgramPopulation.get(0).ID + " " + fitnessBest.toString() + listProgramPopulation.get(0).source);
				String source = listProgramPopulation.get(0).source;
				source = replacePackage(source, species, 0);
				if(!removeSpace(source).equals(stringBestSourceCompact)) {	// only save if different (reduce storage writes)
					stringBestSource = source;
					stringBestSourceCompact = removeSpace(stringBestSource);	
					Files.write(Paths.get(PROGRAM_FILENAME),source.getBytes());
					storeBestFitnessGlobal();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	synchronized public void storeBestFitnessGlobal() {
		final String PROGRAM_FILENAME_GLOBAL = new String("data" + File.separator + "GeneticProgram.java");
		if(GP.fitnessBestGlobal == null) {
			GP.fitnessBestGlobal = fitnessBest;
		} else if(GP.fitnessBestGlobal.compareTo(fitnessBest) > 0) {
			GP.fitnessBestGlobal = fitnessBest;
			try {
				Files.write(Paths.get(PROGRAM_FILENAME_GLOBAL),stringBestSource.getBytes());
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	public void downselectPopulation() {
		int indexPackage = 0;
		Iterator<Program> iteratorProgramPopulation;
		listProgramParent.clear();
		
		// save a set of most fit programs, without regards to generational fitness (MAX_PARENT-MAX_GENERATION_PRESERVE) 
		// assumes that listProgramPopulation is already sorted according to fitness [Collections.sort(listProgramPopulation)]
		iteratorProgramPopulation = listProgramPopulation.iterator();
		while (iteratorProgramPopulation.hasNext()) {
			Program programPopulation = iteratorProgramPopulation.next();
			if(indexPackage>=(MAX_PARENT-MAX_GENERATION_PRESERVE)) {
				break;
			}
			if(programPopulation.fitness.difference.compareTo(Constants.LONG_MAX_VALUE)>=0 || programPopulation.fitness.speed==Integer.MAX_VALUE || programPopulation.fitness.size==Integer.MAX_VALUE) {
				iteratorProgramPopulation.remove();
				continue;
			}
			boolean exists = false;
			String stringSource = removeSpace(programPopulation.source);
			for(Program programParent : listProgramParent) {
				if(removeSpace(programParent.source).equalsIgnoreCase(stringSource)) {
					exists = true;
					break;
				}
			}
			if(!exists) {
				long generation = programPopulation.fitness.generation + 1;						// survived another generation
				long generationalFitness = programPopulation.fitness.generationalFitness + 1;	// program is a top performer, so increase the generational fitness
				Program programCopy = new Program(replacePackage(programPopulation.source, species, indexPackage), species, indexPackage, sizeBeforeRestrict, speedBeforeRestrict, generation, generationalFitness, tests);
				listProgramParent.add(programCopy);
				indexPackage++;
			}
			iteratorProgramPopulation.remove();
		}
		
		// preserve a set of programs based on historical fitness (MAX_GENERATION_PRESERVE)
		Collections.sort(listProgramPopulation, new ProgramComparatorByGenerational());
		iteratorProgramPopulation = listProgramPopulation.iterator();
		while (iteratorProgramPopulation.hasNext()) {
			Program programPopulation = iteratorProgramPopulation.next();
			if(indexPackage>=MAX_PARENT) {
				break;
			}
			if(programPopulation.fitness.difference.compareTo(Constants.LONG_MAX_VALUE)>=0 || programPopulation.fitness.speed==Integer.MAX_VALUE || programPopulation.fitness.size==Integer.MAX_VALUE) {
				continue;
			}
			boolean exists = false;
			String stringSource = removeSpace(programPopulation.source);
			for(Program programParent : listProgramParent) {
				if(removeSpace(programParent.source).equalsIgnoreCase(stringSource)) {
					exists = true;
					break;
				}
			}
			if(!exists) {
				long generation = programPopulation.fitness.generation + 1;						// survived another generation
				long generationalFitness = programPopulation.fitness.generationalFitness - 1;	// program was preserved based on historical performance, so decrease the generational fitness
				Program programCopy = new Program(replacePackage(programPopulation.source, species, indexPackage), species, indexPackage, sizeBeforeRestrict, speedBeforeRestrict, generation, generationalFitness, tests);
				listProgramParent.add(programCopy);
				indexPackage++;
			}
		}
	}
	
	public static String replacePackage(String source, int species, int packageNumber) {
		return source.replaceFirst("package species[0-9][^;]*;", "package " + Program.PACKAGE_SPECIES + species + "." + Program.PACKAGE_ID + packageNumber + ";");
	}
	
	private String removeSpace(String source) {
		return source.replaceAll("\\s+","");
	}
	
	private String createProgram(Program program1, Program program2, String source) {
		List<ParseTree> listParseTree1 = new ArrayList<ParseTree>();
		getParseTreeNonLiteral(listParseTree1, program1.blockContext);
		ParseTree parseTree1 = listParseTree1.get(random.nextInt(listParseTree1.size()));
		
		int a = parseTree1.getSourceInterval().a;
		int b = parseTree1.getSourceInterval().b;
		int size = program1.miniJavaParser.getTokenStream().size()-1;	// remove last token inserted by ANTLR, <EOF>
		StringBuilder stringBuilder = new StringBuilder(source.length());
		for(int index=0; index<a; index++) {
			stringBuilder.append(program1.miniJavaParser.getTokenStream().get(index).getText());
			stringBuilder.append(" ");
		}
		//Oddity, but to calculate total size of prepend and append source code
		StringBuilder stringBuilderAppend = new StringBuilder(source.length());
		for(int index=b+1; index<size; index++) {
			stringBuilderAppend.append(program1.miniJavaParser.getTokenStream().get(index).getText());
			stringBuilderAppend.append(" ");
		}

		int length = stringBuilder.length() + stringBuilderAppend.length();
		if(program2 == null) {	// mutate source
			stringBuilder.append(generator(program1.miniJavaParser, parseTree1));	// replace interval [a,b] with random segment of code of same type
		} else {	// crossover program and program2
			List<ParseTree> listParseTree2 = new ArrayList<ParseTree>();
			getParseTreeNonLiteral(listParseTree2, program2.blockContext);
			List<ParseTree> listParseTreeCandidate = new ArrayList<ParseTree>();
			if(length < sizeBeforeRestrict) {
				for(ParseTree parseTree2 : listParseTree2) {	// add all equivalent class types
					if(length + parseTree2.getText().length() < sizeBeforeRestrict) {
						if(!parseTree2.getClass().getName().equals("minijava.parser.MiniJavaParser$BlockContext")) {	// don't add blocks, as it results in equivalent program
							if(parseTree2.getClass().getName().equals(parseTree1.getClass().getName())) {
								if (parseTree2.getClass().getName().equals("org.antlr.v4.runtime.tree.TerminalNodeImpl")) {	// TerminalNodeImpl has multiple sub-types
									TerminalNode terminalNode1 = (TerminalNode)parseTree1;
									TerminalNode terminalNode2 = (TerminalNode)parseTree2;
									if(terminalNode1.getSymbol().getType() == terminalNode2.getSymbol().getType()) {
										listParseTreeCandidate.add(parseTree2);
									}
								} else {
									listParseTreeCandidate.add(parseTree2);
								}
							}
						}
					}
				}
			}
			if(listParseTreeCandidate.size() > 0) {	// crossover if equivalent class type exists
				ParseTree parseTree2 = listParseTreeCandidate.get(random.nextInt(listParseTreeCandidate.size()));
				stringBuilder.append(Generator.getCode(program2.miniJavaParser, parseTree2));
			} else {	// mutate source because no equivalent class types
				stringBuilder.append(generator(program1.miniJavaParser, parseTree1));	// replace interval [a,b] with random segment of code of same type
			}
		}
		stringBuilder.append(" ");
		stringBuilder.append(stringBuilderAppend);
		return stringBuilder.toString();
	}
	
	private String generator(MiniJavaParser miniJavaParser, ParseTree parseTree) {
		switch(parseTree.getClass().getName()) {
			case "minijava.parser.MiniJavaParser$BlockContext":
				return Generator.generateBlockContext(MAX_NEW_CODE_SEGMENT_SIZE);
			case "minijava.parser.MiniJavaParser$DeclarationContext":
				return Generator.generateDeclarationContext(MAX_NEW_CODE_SEGMENT_SIZE);
			case "minijava.parser.MiniJavaParser$LongArrayDeclarationContext":
				return Generator.generateLongArrayDeclarationContext(MAX_NEW_CODE_SEGMENT_SIZE);
			case "minijava.parser.MiniJavaParser$LongDeclarationContext":
				return Generator.generateLongDeclarationContext(MAX_NEW_CODE_SEGMENT_SIZE);
			case "minijava.parser.MiniJavaParser$BooleanDeclarationContext":
				return Generator.generateBooleanDeclarationContext(MAX_NEW_CODE_SEGMENT_SIZE);
			case "minijava.parser.MiniJavaParser$StatementContext":
				return Generator.generateStatementContext(MAX_NEW_CODE_SEGMENT_SIZE, miniJavaParser, parseTree);
			case "minijava.parser.MiniJavaParser$ExpressionNumericContext":
				return Generator.generateExpressionNumericContext(MAX_NEW_CODE_SEGMENT_SIZE, miniJavaParser, parseTree);
			case "minijava.parser.MiniJavaParser$ExpressionBooleanContext":
				return Generator.generateExpressionBooleanContext(MAX_NEW_CODE_SEGMENT_SIZE, miniJavaParser, parseTree);
			case "minijava.parser.MiniJavaParser$LongArrayValueContext":
				return Generator.generateLongArrayValueContext(MAX_NEW_CODE_SEGMENT_SIZE);
			case "org.antlr.v4.runtime.tree.TerminalNodeImpl":
				TerminalNode terminalNode = (TerminalNode)parseTree;
				return Generator.generateTerminalNode(MAX_NEW_CODE_SEGMENT_SIZE, miniJavaParser, parseTree, MiniJavaParser.VOCABULARY.getSymbolicName(terminalNode.getSymbol().getType()));
			default:
				return null;
		}
	}
	
	private void getParseTreeNonLiteral(List<ParseTree> listParseTree, ParseTree parseTree) {
		if(parseTree==null || parseTree.getText()==null) {
			return;
		}
		String text = "'" + parseTree.getText() + "'";
		boolean isLiteral = false;
		for(int index=0; index<MiniJavaParser.VOCABULARY.getMaxTokenType(); index++) {
			if(text.equals(MiniJavaParser.VOCABULARY.getLiteralName(index))) {
				isLiteral = true;
				break;
			}
		}
		if(!isLiteral) {
			listParseTree.add(parseTree);
			for(int index=0; index<parseTree.getChildCount(); index++) {
				ParseTree parseTreeChild = parseTree.getChild(index);
				getParseTreeNonLiteral(listParseTree, parseTreeChild);
			}
		}
	}
}