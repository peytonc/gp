package minijava;

public class Fitness implements Comparable<Fitness> {
	public int difference;
	public long speed;
	public int size;
	
	public Fitness() {
		difference = Integer.MAX_VALUE;
	}
	public String toString() {
		return "Fitness{difference=" + difference + ",speed=" + speed + ",size=" + size + "}";
	}
	
	@Override
	public int compareTo(Fitness fitness) {
		int compare = Integer.compare(difference, fitness.difference);
		if(compare == 0) {
			compare = Long.compare(speed, fitness.speed);
			if(compare == 0) {
				compare = Integer.compare(size, fitness.size);
			}
		}
		return compare;
	}
}