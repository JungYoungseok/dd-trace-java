package datadog.trace.instrumentation.googlehttpclient;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.googlehttpclient.GoogleHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.googlehttpclient.GoogleHttpClientDecorator.HTTP_REQUEST;
import static datadog.trace.instrumentation.googlehttpclient.HeadersInjectAdapter.SETTER;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class GoogleHttpClientInstrumentation extends Instrumenter.Tracing {
  public GoogleHttpClientInstrumentation() {
    super("google-http-client");
  }

  private final ElementMatcher<ClassLoader> CLASS_LOADER_MATCHER =
      hasClassesNamed("com.google.api.client.http.HttpRequest");

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return CLASS_LOADER_MATCHER;
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    // HttpRequest is a final class.  Only need to instrument it exactly
    // Note: the rest of com.google.api is ignored in AdditionalLibraryIgnoresMatcher to speed
    // things up
    return named("com.google.api.client.http.HttpRequest");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("com.google.api.client.http.HttpRequest", RequestState.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GoogleHttpClientDecorator",
      packageName + ".RequestState",
      packageName + ".HeadersInjectAdapter"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("execute")).and(takesArguments(0)),
        GoogleHttpClientInstrumentation.class.getName() + "$GoogleHttpClientAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("executeAsync"))
            .and(takesArguments(1))
            .and(takesArgument(0, (named("java.util.concurrent.Executor")))),
        GoogleHttpClientInstrumentation.class.getName() + "$GoogleHttpClientAsyncAdvice");
  }

  public static class GoogleHttpClientAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(@Advice.This final HttpRequest request) {

      final ContextStore<HttpRequest, RequestState> contextStore =
          InstrumentationContext.get(HttpRequest.class, RequestState.class);

      RequestState state = contextStore.get(request);

      if (state == null) {
        state = new RequestState(startSpan(HTTP_REQUEST));
        contextStore.put(request, state);
      }

      final AgentSpan span = state.getSpan();
      span.setMeasured(true);

      try (final AgentScope scope = activateSpan(span)) {
        DECORATE.afterStart(span);
        DECORATE.onRequest(span, request);
        propagate().inject(span, request, SETTER);
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.This final HttpRequest request,
        @Advice.Return final HttpResponse response,
        @Advice.Thrown final Throwable throwable) {

      final ContextStore<HttpRequest, RequestState> contextStore =
          InstrumentationContext.get(HttpRequest.class, RequestState.class);
      final RequestState state = contextStore.get(request);

      if (state != null) {
        final AgentSpan span = state.getSpan();

        try (final AgentScope scope = activateSpan(span)) {
          DECORATE.onResponse(span, response);
          DECORATE.onError(span, throwable);

          // If HttpRequest.setThrowExceptionOnExecuteError is set to false, there are no exceptions
          // for a failed request.  Thus, check the response code
          if (response != null && !response.isSuccessStatusCode()) {
            span.setError(true);
            span.setErrorMessage(response.getStatusMessage());
          }

          DECORATE.beforeFinish(span);
          span.finish();
        }
      }
    }
  }

  public static class GoogleHttpClientAsyncAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter(@Advice.This final HttpRequest request) {
      final AgentSpan span = startSpan(HTTP_REQUEST);

      final ContextStore<HttpRequest, RequestState> contextStore =
          InstrumentationContext.get(HttpRequest.class, RequestState.class);

      final RequestState state = new RequestState(span);
      contextStore.put(request, state);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.This final HttpRequest request, @Advice.Thrown final Throwable throwable) {

      if (throwable != null) {

        final ContextStore<HttpRequest, RequestState> contextStore =
            InstrumentationContext.get(HttpRequest.class, RequestState.class);
        final RequestState state = contextStore.get(request);

        if (state != null) {
          final AgentSpan span = state.getSpan();

          try (final AgentScope scope = activateSpan(span)) {
            DECORATE.onError(span, throwable);

            DECORATE.beforeFinish(span);
            span.finish();
          }
        }
      }
    }
  }
}
