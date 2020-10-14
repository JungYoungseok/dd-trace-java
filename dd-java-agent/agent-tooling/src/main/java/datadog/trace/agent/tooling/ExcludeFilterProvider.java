package datadog.trace.agent.tooling;

import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType;
import java.util.Map;
import java.util.Set;

/**
 * Used to allow an {@link Instrumenter} to opt out of broad instrumentations like {@link Runnable}.
 *
 * <p>These are looked up in a separate pass before the {@link Instrumenter} is allowed to add
 * instrumentations, to be able to opt out of field injection which will need to check for
 * exclusions immediately.
 */
public interface ExcludeFilterProvider {

  /**
   * @return A mapping from {@link ExcludeType} -> {@link Set<String>} for the class names that
   *     should be excluded from broad instrumentations like {@link Runnable}
   */
  Map<ExcludeType, Set<String>> excludedClasses();
}
