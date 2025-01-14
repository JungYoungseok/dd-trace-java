package datadog.trace.instrumentation.jms;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jms.JMSDecorator.CONSUMER_DECORATE;
import static datadog.trace.instrumentation.jms.JMSDecorator.JMS_CONSUME;
import static datadog.trace.instrumentation.jms.MessageExtractAdapter.GETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import datadog.trace.bootstrap.instrumentation.jms.MessageConsumerState;
import datadog.trace.bootstrap.instrumentation.jms.SessionState;
import java.util.HashMap;
import java.util.Map;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
import javax.jms.Session;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class JMSMessageConsumerInstrumentation extends Instrumenter.Tracing {

  public JMSMessageConsumerInstrumentation() {
    super("jms", "jms-1", "jms-2");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("javax.jms.MessageConsumer");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("javax.jms.MessageConsumer"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JMSDecorator",
      packageName + ".MessageExtractAdapter",
      packageName + ".MessageExtractAdapter$1",
      packageName + ".MessageInjectAdapter",
      packageName + ".DatadogMessageListener"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("javax.jms.Message", AgentSpan.class.getName());
    contextStore.put("javax.jms.MessageConsumer", MessageConsumerState.class.getName());
    contextStore.put("javax.jms.Session", SessionState.class.getName());
    return contextStore;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("receive").and(takesArguments(0).or(takesArguments(1))).and(isPublic()),
        JMSMessageConsumerInstrumentation.class.getName() + "$ConsumerAdvice");
    transformation.applyAdvice(
        named("receiveNoWait").and(takesArguments(0)).and(isPublic()),
        JMSMessageConsumerInstrumentation.class.getName() + "$ConsumerAdvice");
    transformation.applyAdvice(
        named("close").and(takesArguments(0)).and(isPublic()),
        JMSMessageConsumerInstrumentation.class.getName() + "$Close");
    transformation.applyAdvice(
        isMethod()
            .and(named("setMessageListener"))
            .and(takesArgument(0, hasInterface(named("javax.jms.MessageListener")))),
        getClass().getName() + "$DecorateMessageListener");
  }

  public static class ConsumerAdvice {

    @Advice.OnMethodEnter
    public static void beforeReceive(@Advice.This final MessageConsumer consumer) {
      MessageConsumerState messageConsumerState =
          InstrumentationContext.get(MessageConsumer.class, MessageConsumerState.class)
              .get(consumer);
      if (null != messageConsumerState) {
        // closes the scope, and finishes the span for AUTO_ACKNOWLEDGE
        messageConsumerState.closePreviousMessageScope();
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onMessageProduced(
        @Advice.This final MessageConsumer consumer,
        @Advice.Return final Message message,
        @Advice.Thrown final Throwable throwable) {
      if (message == null) {
        // don't create spans (traces) for each poll if the queue is empty
        return;
      }
      MessageConsumerState messageConsumerState =
          InstrumentationContext.get(MessageConsumer.class, MessageConsumerState.class)
              .get(consumer);
      if (null != messageConsumerState) {
        final Context extractedContext = propagate().extract(message, GETTER);
        AgentSpan span = startSpan(JMS_CONSUME, extractedContext);
        // this scope is intentionally not closed here
        // it stays open until the next call to get a
        // message, or the consumer is closed
        AgentScope scope = activateSpan(span);
        messageConsumerState.capture(scope);
        CONSUMER_DECORATE.afterStart(span);
        CONSUMER_DECORATE.onConsume(span, message, messageConsumerState.getResourceName());
        CONSUMER_DECORATE.onError(span, throwable);
        if (messageConsumerState.isClientAcknowledge()) {
          // span will be finished by a call to Message.acknowledge
          InstrumentationContext.get(Message.class, AgentSpan.class).put(message, span);
        } else if (messageConsumerState.isTransactedSession()) {
          // span will be finished by Session.commit
          InstrumentationContext.get(Session.class, SessionState.class)
              .get((Session) messageConsumerState.getSession())
              .add(span);
        }
        // for AUTO_ACKNOWLEDGE, span is not finished until next call to receive, or close
      }
    }
  }

  public static class Close {
    @Advice.OnMethodEnter
    public static void beforeClose(@Advice.This final MessageConsumer consumer) {
      MessageConsumerState messageConsumerState =
          InstrumentationContext.get(MessageConsumer.class, MessageConsumerState.class)
              .get(consumer);
      if (null != messageConsumerState) {
        messageConsumerState.closePreviousMessageScope();
      }
    }
  }

  public static class DecorateMessageListener {
    @Advice.OnMethodEnter
    public static void setMessageListener(
        @Advice.This MessageConsumer messageConsumer,
        @Advice.Argument(value = 0, readOnly = false) MessageListener listener) {
      if (!(listener instanceof DatadogMessageListener)) {
        MessageConsumerState messageConsumerState =
            InstrumentationContext.get(MessageConsumer.class, MessageConsumerState.class)
                .get(messageConsumer);
        if (null != messageConsumerState) {
          SessionState sessionState =
              InstrumentationContext.get(Session.class, SessionState.class)
                  .get((Session) messageConsumerState.getSession());
          if (null != sessionState) {
            listener =
                new DatadogMessageListener(
                    InstrumentationContext.get(Message.class, AgentSpan.class),
                    listener,
                    messageConsumerState,
                    sessionState);
          }
        }
      }
    }
  }
}
