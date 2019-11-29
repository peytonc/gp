package minijava.comparator;

import java.util.Comparator;

import minijava.Fitness;

public class FitnessComparatorByConfidenceInterval implements Comparator<Fitness> {
    public int compare(Fitness fitness1, Fitness fitness2) { 
		int compare = 0;
		compare = fitness1.confidenceIntervalUpperScaled.compareTo(fitness2.confidenceIntervalUpperScaled);
		if(compare == 0) {
			compare = Long.compare(fitness1.speed, fitness2.speed);
			if(compare == 0) {
				compare = Integer.compare(fitness1.size, fitness2.size);
				if(compare == 0) {
					compare = fitness1.meanScaled.compareTo(fitness2.meanScaled);
					if(compare == 0) {
						compare = Integer.compare(fitness2.correctScaled, fitness1.correctScaled);		// flip order to obtain largest correct first
					}
				}
			}
		}
		return compare;
    } 
}
