package minijava;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class CallableMiniJava implements Runnable {
	private Program program = null;
	
	public CallableMiniJava(Program program) {
		this.program = program;
	}
	
	@Override
	public void run() {
		if(program.vectors != null) {
			Class<?> cls = null;
			try {
				cls = program.programClassLoader.loadClass(Program.PACKAGE_SPECIES + program.species + "." + Program.PACKAGE_ID + program.ID + "." + Program.PROGRAM_CLASS);
			} catch (ClassNotFoundException e1) {
				program.vectors = null;
				e1.printStackTrace();
			}
			Method method = null;
			try {
				method = cls.getMethod("compute", ArrayList.class);
			} catch (NoSuchMethodException e1) {
				program.vectors = null;
				e1.printStackTrace();
			} catch (SecurityException e1) {
				e1.printStackTrace();
			}
			long timeStart = System.nanoTime();
			try {
				for(int index=0; index<program.vectors.size(); index++) {
					if(Thread.currentThread().isInterrupted()) {
						program.vectors = null;
						break;
					} else {
						method.invoke(null, program.vectors.get(index));
						if(program.vectors.get(index)==null || program.vectors.get(index).isEmpty()) {
							program.vectors = null;
							break;
						}
					}
				}
			} catch(Exception e) {
				System.out.println("CallableMiniJavaCallableMiniJavaCallableMiniJava");
				program.vectors = null;
				e.printStackTrace();
			}
			program.fitness.speed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - timeStart);
		}
	}
}
