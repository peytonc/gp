package gp.comparator;

import java.util.Comparator;

import gp.Program;

public class ProgramComparatorByMeanCorrect implements Comparator<Program> {
    public int compare(Program program1, Program program2) { 
		int compare = FitnessComparators.BY_MEAN_CORRECT.compare(program1.fitness, program2.fitness);
		return compare;
    }
}
