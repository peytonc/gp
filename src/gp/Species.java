package gp;

import java.io.File;
import java.io.IOException;
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

import gp.comparator.FitnessComparators;
import gp.comparator.ProgramComparators;
import gp.parser.MiniJavaLexer;
import gp.parser.MiniJavaParser;
import gp.test.Tests;

public class Species implements Runnable {
	public Fitness fitnessBest = null;
	public int stagnant = MAX_STAGNANT_YEARS;
	public int species;
	
	// Size of parent pool reserved for by category
	private static final int MAX_BY_COMBINED = 2;
	private static final int MAX_BY_MEAN_ERROR_CONFIDENCE_INTERVAL = 2;
	private static final int MAX_BY_MEAN_CORRECT_CONFIDENCE_INTERVAL = 2;
	private static final int MAX_BY_MEAN_ERROR = 2;
	private static final int MAX_BY_MEAN_CORRECT = 2;
	// must match program/fitness categories
	private static final int maxByCategory[] = {MAX_BY_COMBINED,  MAX_BY_MEAN_ERROR_CONFIDENCE_INTERVAL, MAX_BY_MEAN_CORRECT_CONFIDENCE_INTERVAL, MAX_BY_MEAN_ERROR, MAX_BY_MEAN_CORRECT};	
	// Total size of parent pool
	private static final int MAX_PARENT =  MAX_BY_COMBINED + MAX_BY_MEAN_ERROR_CONFIDENCE_INTERVAL + MAX_BY_MEAN_CORRECT_CONFIDENCE_INTERVAL + MAX_BY_MEAN_ERROR + MAX_BY_MEAN_CORRECT;
	private static final int MAX_CHILDREN = 2;	// Number of children each parent produces
	public static final int MAX_POPULATION = MAX_PARENT*MAX_CHILDREN + MAX_PARENT;	// Total population size
	private static final int MAX_STAGNANT_YEARS = 4;	// number of years a species can live without progress on bestfit
	private static final int MUTATE_FACTOR = 4;	// CROSSOVER=1, MUTATE=MUTATE_FACTOR, CROSSOVER to MUTATE ratio is 1/MUTATE_FACTOR
	private static final int PARENT_FACTOR = 2;	// CHAMPION=1, PARENT=PARENT_FACTOR, CHAMPION to PARENT ratio is 1/PARENT_FACTOR
	private static final JavaCompiler JAVA_COMPILER = ToolProvider.getSystemJavaCompiler();
	private static final Logger LOGGER = Logger.getLogger(Species.class.getName());
	private Random random = new Random(GP.RANDOM_SEED);
	private static final int MAX_NEW_CODE_SEGMENT_SIZE = 75;
	private List<Program> listProgramChampion = new ArrayList<Program>(MAX_PARENT);
	private List<Program> listProgramParent = new ArrayList<Program>(MAX_PARENT);
	private List<Program> listProgramPopulation = new ArrayList<Program>(MAX_POPULATION);
	private String stringBestSource;
	
	private final String PROGRAM_FILENAME;
	private int year;
	private int day;
	

	public Species(int species, String sourceOrigin) {
		if(maxByCategory.length != ProgramComparators.MAX_CATEGORIES) {
			LOGGER.severe("maxParentByCategory.length != ProgramComparators.MAX_CATEGORIES");
		}
		this.species = species;
		PROGRAM_FILENAME = new String("data" + File.separator + "GeneticProgram" + species + ".java");
		try {
			stringBestSource = new String(Files.readAllBytes(Paths.get(PROGRAM_FILENAME)));
			stringBestSource = removeSpace(stringBestSource);
		} catch (IOException e) {
			stringBestSource = sourceOrigin;
			try {
				Files.write(Paths.get(PROGRAM_FILENAME),stringBestSource.getBytes());
			} catch (IOException e1) {
				e1.printStackTrace();
			}
		}
		// add initial program as parent (not champion) in order to invoke and obtain fitness
		Program program = new Program(stringBestSource, species, 0, null, null, null);
		program.fitness.generation--;	// Program constructor increments generation, but in this case, it should not 
		listProgramParent.add(program);
	}
	
	public void initalizeYear(int year) {
		this.year = year;
		stagnant--;
	}
	
	public void extinction() {
		if(fitnessBest.toString() != null) {
			LOGGER.info("EXTY" + year + "S" + species + " " + fitnessBest.toString() + "\t" + stringBestSource);
		} else {
			LOGGER.warning("EXTY" + year + "S" + species + " fitnessBest.toString()==null\t" + stringBestSource);
		}
		try {
			Files.delete(Paths.get(PROGRAM_FILENAME));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public void initalizeDay(int day) {
		this.day = day;
	}
	
	@Override
	public void run() {
		// Species has a normal priority and is often sleeping (normal priority to interrupt CallableMiniJava)
		Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
		createPopulation();
		compilePopulation();
		executePopulation();
		evaluatePopulation();
		downselectPopulation();
		/*if(day%1 == 0 && listProgramParent!=null && !listProgramParent.isEmpty()) {
			int count = 0;
			long milli = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
			for(Program program : listProgramParent) {
				LOGGER.info("\tdownselectPopulation\t" + milli + "\t" + year + "\t" + day + "\t" + count + "\t" + program.species + "\t" + program.fitness.toString() + "\t" + program.source);
				count++;
			}
		}*/
		promoteChampion();
		if(day%1 == 0 && listProgramChampion!=null && !listProgramChampion.isEmpty()) {
			int count = 0;
			long milli = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
			for(Program program : listProgramChampion) {
				LOGGER.info("\tpromoteChampion\t" + milli + "\t" + year + "\t" + day + "\t" + count + "\t" + program.species + "\t" + program.fitness.toString() + "\t" + program.source);
				count++;
			}
		}
		storeBestFitness();
	}

	public void createPopulation() {
		int indexPackage = 0;
		String source;
		listProgramPopulation.clear();
		for(Program program : listProgramParent) {
			source = replacePackage(program.source, species, indexPackage);
			//make ANTLR parsers for parents, used by crossover
			if(program.miniJavaParser==null || program.blockContext==null) {
				MiniJavaLexer miniJavaLexer = new MiniJavaLexer(CharStreams.fromString(program.source));
				program.miniJavaParser = new MiniJavaParser(new CommonTokenStream(miniJavaLexer));	// may contain incorrect ID
				program.blockContext = program.miniJavaParser.program().block();	// ANTLR only allows one call to program() before EOF error?
			}
			//copy fitness to preserve statistical moments
			Program programParent = new Program(source, species, indexPackage, program.fitness, program.miniJavaParser, program.blockContext);
			programParent.fitness.reset(programParent.source.length());
			listProgramPopulation.add(programParent);	// add parent to population
			indexPackage++;
		}
		
		// create population using parent/champion by mutation and crossover
		int indexParent = 0;
		List<Program> listProgram = listProgramParent;
		if(listProgramParent.isEmpty()) {
			listProgram = listProgramChampion;
		}
		for(Program program : listProgram) {
			Program program1 = program;
			if(random.nextInt(PARENT_FACTOR) == 0) {
				if(listProgramChampion.size() > indexParent) {
					// use champion instead of parent, same index thus same category
					program1 = listProgramChampion.get(indexParent);
				}
			}
			for(int indexChild=0; indexChild<MAX_CHILDREN; indexChild++) {
				Program program2 = null;	// program2==null means mutate
				if(random.nextInt(MUTATE_FACTOR) == 0) {
					if(random.nextInt(PARENT_FACTOR) == 0) {
						if(!listProgramChampion.isEmpty()) {
							// program2 is champion crossover
							program2 = listProgramChampion.get(random.nextInt(listProgramChampion.size()));
						}
					} else {
						if(!listProgramParent.isEmpty()) {
							// program2 is parent crossover
							program2 = listProgramParent.get(random.nextInt(listProgramParent.size()));
						}
					}
				}
				source = createProgram(program1, program2, program1.source);
				source = replacePackage(source, species, indexPackage);
				Program programChild = new Program(source, species, indexPackage, null, null, null);
				listProgramPopulation.add(programChild);	// add child to population
				indexPackage++;
			}
			indexParent++;
		}
	}
	
	public void compilePopulation() { 
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
		Iterator<Program> iteratorProgram = listProgramPopulation.iterator(); 
		while(iteratorProgram.hasNext()) {
			Program program = iteratorProgram.next();
			try (StandardJavaFileManager standardJavaFileManager = JAVA_COMPILER.getStandardFileManager(diagnostics, Locale.ENGLISH, null)) {
				Iterable<Program> javaFileObject = Arrays.asList(program);
				ProgramClassSimpleJavaFileObject programClassSimpleJavaFileObject = null;
				try {
					programClassSimpleJavaFileObject = new ProgramClassSimpleJavaFileObject(Program.PACKAGE_SPECIES + species + "." + Program.PACKAGE_ID + program.ID + "." + Program.PROGRAM_CLASS);
				} catch (Exception e) {
					iteratorProgram.remove();
					e.printStackTrace();
				}
				ProgramForwardingJavaFileManager programForwardingJavaFileManager = new ProgramForwardingJavaFileManager(standardJavaFileManager, programClassSimpleJavaFileObject, program.programClassLoader);
				CompilationTask compilerTask = JAVA_COMPILER.getTask(null, programForwardingJavaFileManager, diagnostics, null, null, javaFileObject);
				//Compile and check for program errors, random code may have compile errors
				if (!compilerTask.call()) {
					iteratorProgram.remove();
					for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
						LOGGER.severe(diagnostic.getMessage(null));
				    }
				}
			} catch (IOException e) {
				LOGGER.severe("Must use JDK (Java bin directory must contain javac)");
				e.printStackTrace();
			}
		}
	}
	
	public void executePopulation() {
		List<CallableMiniJava> listCallable = new ArrayList<CallableMiniJava>(MAX_POPULATION);
		List<Program> listProgramPopulationCompleted = new ArrayList<Program>(MAX_POPULATION);
		
		do {	// run all program until completed or eliminated from population
			ExecutorService executorService = Executors.newFixedThreadPool(GP.THREADS_PER_SPECIES-1);
			try {	// Load the class and use it
				for(Program program : listProgramPopulation) {
					listCallable.add(new CallableMiniJava(program));
				}
				for(CallableMiniJava callableMiniJava : listCallable) {
					executorService.execute(callableMiniJava);
				}
				executorService.shutdown();
				if(!executorService.awaitTermination(Environment.MAX_EXECUTE_MILLISECONDS, TimeUnit.MILLISECONDS)) {
					executorService.shutdownNow();
					long timeStart = System.nanoTime();
					while(!executorService.awaitTermination(Environment.MAX_EXECUTE_MILLISECONDS, TimeUnit.MILLISECONDS)) {
						LOGGER.warning("Runaway species #" + species + " for " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - timeStart) + "ms");
					}
				}
				
				Iterator<Program> iteratorProgram = listProgramPopulation.iterator();
				while (iteratorProgram.hasNext()) {
					Program program = iteratorProgram.next();
			        if(program.vectors == null) {
			        	// remove program when vectors is null
			        	iteratorProgram.remove();
			        	if(program.ID < 10) {
			        		LOGGER.info("\tnull\t" + program.fitness.toString() + "\t" + program.source);
			        	}
			        } else if(program.fitness.meanSpeed > Environment.MAX_EXECUTE_MILLISECONDS_90PERCENT) {
			        	// remove program when it exceeds MAX_EXECUTE_MILLISECONDS_90PERCENT
			        	iteratorProgram.remove();
			        	if(program.ID < 10) {
			        		LOGGER.info("\tmeanSpeed\t" + program.fitness.toString() + "\t" + program.source);
			        	}
			        } else if(program.fitness.isComplete) {	// remove program when completed and add to completed list
			        	if(program.fitness.meanSpeed!=Long.MAX_VALUE && program.fitness.size!=Integer.MAX_VALUE) {
			        		listProgramPopulationCompleted.add(program);
			        	}
			        	iteratorProgram.remove();
			        }
			    }
			} catch (InterruptedException e) {
				e.printStackTrace();
				LOGGER.severe("Exception on species #" + species);
			}
			listCallable.clear();
		} while (!listProgramPopulation.isEmpty());
		listProgramPopulation = listProgramPopulationCompleted;
	}
	
	public void evaluatePopulation() {
		Iterator<Program> iteratorProgram = listProgramPopulation.iterator();
		while (iteratorProgram.hasNext()) {
			Program program = iteratorProgram.next();
			if(Tests.getTests().getDifferences(program.vectors, program.fitness) == false) {
				iteratorProgram.remove();
			}
		}
	}
	
	public void downselectPopulation() {
		Iterator<Program> iteratorProgramPopulation;
		listProgramParent.clear();
		
		// save the best set of programs (per each fitness category)
		for (int category=0; category<maxByCategory.length && !listProgramPopulation.isEmpty(); category++) {
			int sizeOfCurrentCategory = 0;
			Collections.sort(listProgramPopulation, ProgramComparators.getProgramComparators().category.get(category));
			iteratorProgramPopulation = listProgramPopulation.iterator();
			while (iteratorProgramPopulation.hasNext()) {
				Program programPopulation = iteratorProgramPopulation.next();
				if(sizeOfCurrentCategory>=maxByCategory[category]) {
					break;
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
					for(Program programChampion : listProgramChampion) {
						if(removeSpace(programChampion.source).equalsIgnoreCase(stringSource)) {
							exists = true;
							break;
						}
					}
				}
				if(!exists) {
					listProgramParent.add(programPopulation);
					sizeOfCurrentCategory++;
				}
				iteratorProgramPopulation.remove();
			}
		}
	}
	
	public void promoteChampion() {
		if(listProgramChampion.size()<MAX_PARENT || listProgramParent.size()<MAX_PARENT) {
			while(listProgramChampion.size()<MAX_PARENT && !listProgramParent.isEmpty()) {
				Program programParent = listProgramParent.remove(0);	// initial promotion of best parents to champions
				if(programParent.miniJavaParser==null || programParent.blockContext==null) {
					MiniJavaLexer miniJavaLexer = new MiniJavaLexer(CharStreams.fromString(programParent.source));
					programParent.miniJavaParser = new MiniJavaParser(new CommonTokenStream(miniJavaLexer));	// may contain incorrect ID
					programParent.blockContext = programParent.miniJavaParser.program().block();	// ANTLR only allows one call to program() before EOF error?
				}
				listProgramChampion.add(programParent);
				if(listProgramChampion.size() == MAX_PARENT) {
					// sort champions per category
					int indexChampionFirst = 0;
					int indexChampionLast = 0;
					for (int category=0; category<maxByCategory.length; category++) {
						indexChampionLast = indexChampionFirst + maxByCategory[category] - 1;
						Collections.sort(listProgramChampion.subList(indexChampionFirst, indexChampionLast+1), ProgramComparators.getProgramComparators().category.get(category));
						indexChampionFirst += maxByCategory[category];
					}
				}
			}
		} else {
			// in each category, only check best ranked parent against worst ranked champion, and swap if parent is better
			int indexChampion = 0;
			int indexParent = 0;
			Program programChampion;
			Program programParent;
			for (int category=0; category<maxByCategory.length; category++) {
				indexChampion = indexParent + maxByCategory[category] - 1;
				programChampion = listProgramChampion.get(indexChampion);	// worst ranked champion in this category
				programParent = listProgramParent.get(indexParent);			// best ranked parent in this category
				int compare = ProgramComparators.getProgramComparators().category.get(category).compare(programChampion, programParent);
				if(compare > 0) {
					listProgramChampion.remove(indexChampion);
					listProgramParent.set(indexParent, programChampion);	// replace programParent with programChampion at indexParent
					while(compare>0 && indexChampion>indexParent) {
						programChampion = listProgramChampion.get(indexChampion-1);
						compare = ProgramComparators.getProgramComparators().category.get(category).compare(programChampion, programParent);
						if(compare > 0) {
							indexChampion--;
						}
					}
					if(programParent.miniJavaParser==null || programParent.blockContext==null) {
						MiniJavaLexer miniJavaLexer = new MiniJavaLexer(CharStreams.fromString(programParent.source));
						programParent.miniJavaParser = new MiniJavaParser(new CommonTokenStream(miniJavaLexer));	// may contain incorrect ID
						programParent.blockContext = programParent.miniJavaParser.program().block();	// ANTLR only allows one call to program() before EOF error?
					}
					listProgramChampion.add(indexChampion, programParent);
				}
				indexParent += maxByCategory[category];
			}
		}
	}
	
	public void storeBestFitness() {
		if(listProgramChampion==null || listProgramChampion.isEmpty()) {
			return;
		}
		if(fitnessBest == null) {
			stagnant = MAX_STAGNANT_YEARS;
			fitnessBest = new Fitness(listProgramChampion.get(0).fitness);
			stringBestSource = listProgramChampion.get(0).source;
		} else if(FitnessComparators.BY_COMBINED.compare(fitnessBest, listProgramChampion.get(0).fitness) > 0) {
			stagnant = MAX_STAGNANT_YEARS;
			fitnessBest = new Fitness(listProgramChampion.get(0).fitness);
			try {
				GP.LOGGER_BEST.info("\t" + year + "\t" + day + "\t" + listProgramChampion.get(0).species + "\t" + listProgramChampion.get(0).ID + "\t" + listProgramChampion.get(0).fitness + "\t" + listProgramChampion.get(0).source);
				String source = listProgramChampion.get(0).source;
				source = replacePackage(source, species, 0);
				if(!source.equals(stringBestSource)) {	// only save if different (reduce storage writes)
					stringBestSource = source;
					String sourceWithHeader = "/* " + fitnessBest.toString() + " */\n" + source;
					Files.write(Paths.get(PROGRAM_FILENAME), sourceWithHeader.getBytes());
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
			GP.fitnessBestGlobal = new Fitness(fitnessBest);
		} else if(FitnessComparators.BY_COMBINED.compare(GP.fitnessBestGlobal, fitnessBest) > 0) {
			GP.fitnessBestGlobal = new Fitness(fitnessBest);
			try {
				GP.LOGGER_BEST.info("\t" + year + "\t" + day + "\t" + -1 + "\t" + -1 + "\t" + GP.fitnessBestGlobal + "\t" + stringBestSource);
				String sourceWithHeader = "/* " + GP.fitnessBestGlobal.toString() + " */\n" + stringBestSource;
				Files.write(Paths.get(PROGRAM_FILENAME_GLOBAL),sourceWithHeader.getBytes());
				GP.sizeSourceLength = stringBestSource.length();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	public static String replacePackage(String source, int species, int packageNumber) {
		return source.replaceFirst("package species[0-9][^;]*;", "package " + Program.PACKAGE_SPECIES + species + "." + Program.PACKAGE_ID + packageNumber + ";");
	}
	
	public static String removeSpace(String source) {
		return source.replaceAll("\\s+"," ");
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
			if(length < Environment.getEnvironment().sizeBeforeRestrict) {
				for(ParseTree parseTree2 : listParseTree2) {	// add all equivalent class types
					if(length + parseTree2.getText().length() < Environment.getEnvironment().sizeBeforeRestrict) {
						if(!parseTree2.getClass().getName().equals("gp.parser.MiniJavaParser$BlockContext")) {	// don't add blocks, as it results in equivalent program
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
			case "gp.parser.MiniJavaParser$BlockContext":
				return Generator.generateBlockContext(MAX_NEW_CODE_SEGMENT_SIZE);
			case "gp.parser.MiniJavaParser$DeclarationContext":
				return Generator.generateDeclarationContext(MAX_NEW_CODE_SEGMENT_SIZE);
			case "gp.parser.MiniJavaParser$LongArrayDeclarationContext":
				return Generator.generateLongArrayDeclarationContext(MAX_NEW_CODE_SEGMENT_SIZE);
			case "gp.parser.MiniJavaParser$LongDeclarationContext":
				return Generator.generateLongDeclarationContext(MAX_NEW_CODE_SEGMENT_SIZE);
			case "gp.parser.MiniJavaParser$BooleanDeclarationContext":
				return Generator.generateBooleanDeclarationContext(MAX_NEW_CODE_SEGMENT_SIZE);
			case "gp.parser.MiniJavaParser$StatementContext":
				return Generator.generateStatementContext(MAX_NEW_CODE_SEGMENT_SIZE, miniJavaParser, parseTree);
			case "gp.parser.MiniJavaParser$ExpressionNumericContext":
				return Generator.generateExpressionNumericContext(MAX_NEW_CODE_SEGMENT_SIZE, miniJavaParser, parseTree);
			case "gp.parser.MiniJavaParser$ExpressionBooleanContext":
				return Generator.generateExpressionBooleanContext(MAX_NEW_CODE_SEGMENT_SIZE, miniJavaParser, parseTree);
			case "gp.parser.MiniJavaParser$LongArrayValueContext":
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
