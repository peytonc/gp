package gp.comparator;

import java.util.Comparator;

import gp.Fitness;

public class FitnessComparators {
	public static final Comparator<Fitness> BY_COMBINED = new FitnessComparatorByCombined();
	public static final Comparator<Fitness> BY_MEAN_ERROR_CONFIDENCE_INTERVAL = new FitnessComparatorByMeanErrorConfidenceInterval();
	public static final Comparator<Fitness> BY_MEAN_CORRECT_CONFIDENCE_INTERVAL = new FitnessComparatorByMeanCorrectConfidenceInterval();
	public static final Comparator<Fitness> BY_MEAN_ERROR = new FitnessComparatorByMeanError();
	public static final Comparator<Fitness> BY_MEAN_CORRECT = new FitnessComparatorByMeanCorrect();
}
