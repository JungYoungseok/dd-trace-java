package datadog.trace.core.histogram;

import datadog.trace.api.Platform;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.InvocationTargetException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This indirection exists to make sure the class `DDSketch` can never be loaded on JDK7 */
@SuppressForbidden
public final class Histograms {

  private static final Logger log = LoggerFactory.getLogger(Histograms.class);

  private final boolean loadStub;

  private static final Histograms INSTANCE = new Histograms(!Platform.isJavaVersionAtLeast(8));

  public Histograms(boolean loadStub) {
    this.loadStub = loadStub;
  }

  HistogramFactory newFactory() {
    if (loadStub) {
      return load("datadog.trace.core.histogram.StubHistogram");
    }
    return load("datadog.trace.core.histogram.DDSketchHistogramFactory");
  }

  /**
   * Load reflectively to ensure that DDSketch is never loaded on JDK7
   *
   * @return a histogram factory
   */
  public static HistogramFactory newHistogramFactory() {
    return INSTANCE.newFactory();
  }

  private static HistogramFactory load(String name) {
    try {
      return (HistogramFactory) Class.forName(name).getConstructor().newInstance();
    } catch (InstantiationException
        | InvocationTargetException
        | NoSuchMethodException
        | IllegalAccessException
        | ClassNotFoundException e) {
      log.debug("Failed to load {}", name, e);
      return new StubHistogram();
    }
  }
}
