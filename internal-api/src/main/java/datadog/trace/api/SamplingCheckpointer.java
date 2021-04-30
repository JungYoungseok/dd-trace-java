package datadog.trace.api;

import static datadog.trace.api.Checkpointer.CPU;
import static datadog.trace.api.Checkpointer.END;
import static datadog.trace.api.Checkpointer.ENQUEUED;
import static datadog.trace.api.Checkpointer.IO;
import static datadog.trace.api.Checkpointer.SPAN;
import static datadog.trace.api.Checkpointer.THREAD_MIGRATION;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SamplingCheckpointer {

  private static final Logger log = LoggerFactory.getLogger(SamplingCheckpointer.class);

  private static final AtomicReferenceFieldUpdater<SamplingCheckpointer, Checkpointer> CAS =
      AtomicReferenceFieldUpdater.newUpdater(
          SamplingCheckpointer.class, Checkpointer.class, "checkpointer");

  private volatile Checkpointer checkpointer;

  private static final SamplingCheckpointer HOLDER =
      new SamplingCheckpointer(NoOpCheckpointer.NO_OP);

  private static volatile Checkpointer CHECKPOINTER = HOLDER.checkpointer;

  public SamplingCheckpointer(Checkpointer checkpointer) {
    this.checkpointer = checkpointer;
  }

  public static void register(Checkpointer checkpointer) {
    if (CAS.compareAndSet(HOLDER, NoOpCheckpointer.NO_OP, checkpointer)) {
      CHECKPOINTER = HOLDER.checkpointer;
    } else {
      log.debug(
          "failed to register checkpointer {} - {} already registered",
          checkpointer.getClass(),
          CHECKPOINTER.getClass());
    }
  }

  public static void onComplexEvent(AgentSpan span, int flags) {
    checkpoint(span, flags);
  }

  public static void onSpanStart(AgentSpan span) {
    checkpoint(span, SPAN);
  }

  public static void onEnqueue(AgentSpan span) {
    checkpoint(span, ENQUEUED);
  }

  public static void onCommenceWork(AgentSpan span) {
    checkpoint(span, CPU);
  }

  public static void onCompleteWork(AgentSpan span) {
    checkpoint(span, CPU | END);
  }

  public static void onCommenceIO(AgentSpan span) {
    checkpoint(span, IO);
  }

  public static void onCompleteIO(AgentSpan span) {
    checkpoint(span, IO | END);
  }

  public static void onThreadMigration(AgentSpan span) {
    checkpoint(span, THREAD_MIGRATION);
  }

  public static void onAsyncResume(AgentSpan span) {
    checkpoint(span, THREAD_MIGRATION | END);
  }

  public static void onSpanFinish(AgentSpan span) {
    checkpoint(span, SPAN | END);
  }

  private static void checkpoint(AgentSpan span, int flags) {
    if (sample(span)) {
      AgentSpan.Context context = span.context();
      CHECKPOINTER.checkpoint(context.getTraceId(), context.getSpanId(), flags);
    }
  }

  private static boolean sample(AgentSpan span) {
    // FIXME use isEligibleForDropping once merged
    return true;
  }

  private static final class NoOpCheckpointer implements Checkpointer {

    static final NoOpCheckpointer NO_OP = new NoOpCheckpointer();

    @Override
    public void checkpoint(DDId traceId, DDId spanId, int flags) {}
  }
}
