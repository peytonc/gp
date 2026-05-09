package gp;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.stat.interval.ConfidenceInterval;
import org.apache.commons.math3.stat.interval.WilsonScoreInterval;

public class Fitness {
	// sample size parameters
	public long generation;					// number of generations (or days) this program has survived
	public BigInteger count;				// total samples or generation*Tests.MAX_TEST_VECTORS, for Welford's online algorithm
	// size	parameters
	public int size;						// size of source code, which is nearly a fixed size (minor exception with identify # length)
	// CPU time parameters
	public long meanSpeed;					// mean CPU time taken to execute program across samples (i.e. # of generations)
	public long sumSpeed;					// aggregates the speeds, for Welford's online algorithm
	// BY_MEAN_ERROR and BY_MEAN_ERROR_CONFIDENCE_INTERVAL parameters
	public BigInteger meanError;			// sample mean of error (between actual and expected), for Welford's online algorithm
	private BigInteger sumOfMeansError;		// aggregates the sample means (each sample mean is the average difference between actuals and expected)
	public BigInteger sumErrorM2;			// aggregates the squared distance from the sample mean error, for Welford's online algorithm
	public BigInteger meanErrorScaled;		// scaled when speed/size is over allocation
	public BigInteger meanErrorConfidenceInterval;			// sample mean error plus margin of error
	public BigInteger meanErrorConfidenceIntervalScaled;	// scaled when speed/size is over allocation
	public BigInteger meanErrorEstimator;					// special case on initial samples to estimate meanError
	private BigInteger sumOfMeansErrorEstimator;			// special case on initial samples to estimate sumOfMeansError
	public BigInteger sumErrorM2Estimator;					// special case on initial samples to estimate sumErrorM2
	// BY_MEAN_CORRECT and BY_MEAN_CORRECT_CONFIDENCE_INTERVAL parameters
	public double meanCorrect;					// number of samples that are correct (e.g. no difference between actual and expected)
	private long sumCorrect;					// aggregates number of correct
	public double meanCorrectScaled;				// scaled when speed/size is over allocation
	public double meanCorrectConfidenceInterval;	// mean correct minus margin of error, obtain smallest value
	public double meanCorrectConfidenceIntervalScaled;	// scaled when speed/size is over allocation
	// BY_COMBINED parameters
	public BigInteger combinedFunction;		// a combined function of: correct, mean confidence interval, speed confidence interval, and size
	// program control parameters
	public boolean isComplete;				// successfully completed all test cases?
	// scaling parameters
	public long numeratorScaled;			// for scaling when speed/size is over allocation
	public long denominatorScaled;			// for scaling when speed/size is over allocation
	// statistical parameters
	private static final NormalDistribution normalDistribution = new NormalDistribution();
	private static final double confidenceLevel = 0.995;	// confidence level of confidence interval, in [0,1)
	private static final double alpha = (1.0 - confidenceLevel) / 2.0;
	private static final double zScore = normalDistribution.inverseCumulativeProbability(1.0 - alpha);
	private static final BigInteger zScore10000000000 = BigDecimal.valueOf(zScore * Constants.I10000000000.doubleValue()).toBigInteger();
	private static final WilsonScoreInterval wilsonScoreInterval = new WilsonScoreInterval();
	private static final long MAX_GENERATION_ESTIMATOR = 3;

	public Fitness() {
		// sample size parameters
		generation = 0;					// start at 0 and program will increment
		count = Constants.I0;
		// size parameters
		size = Integer.MAX_VALUE;
		// CPU time parameters
		meanSpeed = 0;
		sumSpeed = 0;
		// BY_MEAN_ERROR and BY_MEAN_ERROR_CONFIDENCE_INTERVAL parameters
		meanError = Constants.I0;
		sumOfMeansError = Constants.I0;
		sumErrorM2 = Constants.I0;
		meanErrorScaled = Constants.I0;
		meanErrorEstimator = Constants.I0;
		sumOfMeansErrorEstimator = Constants.I0;
		sumErrorM2Estimator = Constants.I0;
		// BY_MEAN_CORRECT and BY_MEAN_CORRECT_CONFIDENCE_INTERVAL parameters
		meanCorrect = 0;
		sumCorrect = 0;
		meanCorrectScaled = 0;
		// BY_COMBINED parameters
		combinedFunction = Constants.I0;
		// program control parameters
		isComplete = false;
		// scaling parameters
		numeratorScaled = 1;
		denominatorScaled = 1;
	}
	
	public Fitness(Fitness fitness) {
		// sample size parameters
		this.generation = fitness.generation;
		this.count = fitness.count;
		// size	parameters
		this.size = fitness.size;
		// CPU time parameters
		this.meanSpeed = fitness.meanSpeed;
		this.sumSpeed = fitness.sumSpeed;
		// BY_MEAN_ERROR and BY_MEAN_ERROR_CONFIDENCE_INTERVAL parameters
		this.meanError = fitness.meanError;
		this.sumOfMeansError = fitness.sumOfMeansError;
		this.sumErrorM2 = fitness.sumErrorM2;
		this.meanErrorScaled = fitness.meanErrorScaled;
		this.meanErrorConfidenceInterval = fitness.meanErrorConfidenceInterval;
		this.meanErrorConfidenceIntervalScaled = fitness.meanErrorConfidenceIntervalScaled;
		this.meanErrorEstimator = fitness.meanErrorEstimator;
		this.sumOfMeansErrorEstimator = fitness.sumOfMeansErrorEstimator;
		this.sumErrorM2Estimator = fitness.sumErrorM2Estimator;
		// BY_MEAN_CORRECT and BY_MEAN_CORRECT_CONFIDENCE_INTERVAL parameters
		this.meanCorrect = fitness.meanCorrect;
		this.sumCorrect = fitness.sumCorrect;
		this.meanCorrectScaled = fitness.meanCorrectScaled;
		this.meanCorrectConfidenceInterval = fitness.meanCorrectConfidenceInterval;
		this.meanCorrectConfidenceIntervalScaled = fitness.meanCorrectConfidenceIntervalScaled;
		// BY_COMBINED parameters
		this.combinedFunction = fitness.combinedFunction;
		// scaling parameters
		this.numeratorScaled = fitness.numeratorScaled;
		this.denominatorScaled = fitness.denominatorScaled;
		// scaling parameters
		this.isComplete = fitness.isComplete;
	}
	
	// add the sampled difference to the fitness parameters
	public void addDifference(BigInteger difference) {
		if(difference.compareTo(Constants.I0) == 0) {
			sumCorrect++;
		}
		count = count.add(Constants.I1);
		if(generation <= MAX_GENERATION_ESTIMATOR) {
			// special case on initial samples to estimate standard deviation from sub-samples standard deviation (by CLT)
			// update parameters of Welford's online algorithm, used to calculate statistical moments (e.g. mean, variance)
			BigInteger deltaPrevious = difference.subtract(meanErrorEstimator);
			sumOfMeansErrorEstimator = sumOfMeansErrorEstimator.add(difference);
			meanErrorEstimator = sumOfMeansErrorEstimator.divide(count);
			BigInteger deltaCurrent = difference.subtract(meanErrorEstimator);
			sumErrorM2Estimator = sumErrorM2Estimator.add(deltaPrevious.multiply(deltaCurrent));
		}
	}
	
	// add the sampled mean to the fitness parameters
	// a sample of getDifference are not Normally distributed, but by central limit theorem, the sample means are Normally distributed
	public void addSampleMean(BigInteger sampleMean) {
		// update parameters of Welford's online algorithm, used to calculate statistical moments (e.g. mean, variance)
		// modification to Welford's online algorithm to use integer sum, because it needs to scale with large integer division (and stay integer) instead of reals
		BigInteger deltaPrevious = sampleMean.subtract(meanError);
		sumOfMeansError = sumOfMeansError.add(sampleMean);
		meanError = sumOfMeansError.divide(BigInteger.valueOf(generation));
		BigInteger deltaCurrent = sampleMean.subtract(meanError);
		sumErrorM2 = sumErrorM2.add(deltaPrevious.multiply(deltaCurrent));
	}
	
	// add the sampled speed to the fitness parameters 
	public void addSampleSpeed(long sampleSpeed) {
		sumSpeed += sampleSpeed;
		meanSpeed = sumSpeed/generation;
	}
	
	// reset all daily fitness parameters
	public void reset(int size) {
		isComplete = false;
		this.size = size;
	}
	
	public void update() {
		if(meanSpeed > Environment.getEnvironment().speedBeforeRestrict) {
			// if speed exceeds restriction then punish fitness (by increasing value)
			numeratorScaled = meanSpeed;
			denominatorScaled = Environment.getEnvironment().speedBeforeRestrict;
		} else {
			// reset scaling factors before another day
			numeratorScaled = 1;
			denominatorScaled = 1;
		}
		if(size > Environment.getEnvironment().sizeBeforeRestrict) {
			// if size exceeds restriction then punish fitness (by increasing value)
			numeratorScaled *= size;
			denominatorScaled *= Environment.getEnvironment().sizeBeforeRestrict;
		}
		BigInteger numeratorScaledBigInteger = BigInteger.valueOf(numeratorScaled);
		BigInteger denominatorScaledBigInteger = BigInteger.valueOf(denominatorScaled);
		
		// BY_MEAN_ERROR and BY_MEAN_ERROR_CONFIDENCE_INTERVAL parameters
		meanErrorScaled = meanError.multiply(numeratorScaledBigInteger).divide(denominatorScaledBigInteger);
		BigInteger standardDeviation;
		if(generation <= MAX_GENERATION_ESTIMATOR) {
			// special case on initial samples to estimate standard deviation with sub-samples via CLT
			BigInteger varianceDivisor = count.subtract(Constants.I1);
			if(varianceDivisor.compareTo(Constants.I0) <= 0) {
				varianceDivisor = Constants.I1;
			}
			standardDeviation = Util.sqrt(sumErrorM2Estimator.divide(varianceDivisor));
			standardDeviation = standardDeviation.divide(Util.sqrt(count));
		} else {
			standardDeviation = Util.sqrt(sumErrorM2.divide(BigInteger.valueOf(generation-1)));
		}
		// calculate maximum value of confidence interval range
		BigInteger marginOfError = zScore10000000000.multiply(standardDeviation).divide(Util.sqrt(BigInteger.valueOf(generation))).divide(Constants.I10000000000);
		meanErrorConfidenceInterval = meanError.add(marginOfError);
		meanErrorConfidenceIntervalScaled = meanErrorConfidenceInterval.multiply(numeratorScaledBigInteger).divide(denominatorScaledBigInteger);
		
		// BY_MEAN_CORRECT and BY_MEAN_CORRECT_CONFIDENCE_INTERVAL parameters
		meanCorrect = sumCorrect/count.doubleValue();
		// flip numerator and denominator to punish fitness, because numeratorScaled>denominatorScaled
		meanCorrectScaled = meanCorrect*denominatorScaled/numeratorScaled;	
		ConfidenceInterval confidenceInterval = wilsonScoreInterval.createInterval(count.intValue(), (int)sumCorrect, confidenceLevel);
		meanCorrectConfidenceInterval = confidenceInterval.getLowerBound();
		// flip numerator and denominator to punish fitness, because numeratorScaled>denominatorScaled
		meanCorrectConfidenceIntervalScaled = meanCorrectConfidenceInterval*denominatorScaled/numeratorScaled;
		
		// BY_COMBINED parameters
		combinedFunction = meanErrorConfidenceIntervalScaled;
		double incorrectPercent = 1.0 - meanCorrectConfidenceInterval;
		combinedFunction = BigDecimal.valueOf(incorrectPercent * combinedFunction.doubleValue()).toBigInteger();
	}

	
	public String toString() {
		return generation + "\t" + combinedFunction + "\t" + meanErrorConfidenceIntervalScaled + "\t" + meanCorrectConfidenceIntervalScaled + "\t" + meanErrorScaled + "\t" + meanCorrectScaled + "\t" +  meanSpeed + "\t" + size;
	}
}
