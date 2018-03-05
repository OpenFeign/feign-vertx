package feign;

import static feign.FeignException.errorExecuting;
import static feign.FeignException.errorReading;
import static feign.Util.ensureClosed;

import feign.InvocationHandlerFactory.MethodHandler;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import feign.vertx.VertxHttpClient;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Method handler for asynchronous HTTP requests via {@link VertxHttpClient}.
 * Inspired by {@link SynchronousMethodHandler}.
 *
 * @author Alexei KLENIN
 * @author Gordon McKinney
 */
final class AsynchronousMethodHandler implements MethodHandler {
  private static final long MAX_RESPONSE_BUFFER_SIZE = 8192L;

  private final MethodMetadata metadata;
  private final Target<?> target;
  private final VertxHttpClient client;
  private final Retryer retryer;
  private final List<RequestInterceptor> requestInterceptors;
  private final Logger logger;
  private final Logger.Level logLevel;
  private final RequestTemplate.Factory buildTemplateFromArgs;
  private final Decoder decoder;
  private final ErrorDecoder errorDecoder;
  private final boolean decode404;
  private final CircuitBreaker circuitBreaker;
  private       Method thisMethod;
  private       Method fallbackMethod;
  private       Object fallbackInstance;

  // This data structure allows us to cache fallback class instances for quick execution
  // MT safe, enabling multiple vertical processors to access this safely
  private final static ConcurrentHashMap<Class, Object> fallbackInstanceCache = new ConcurrentHashMap<>();
  
  private AsynchronousMethodHandler(
      final Target<?> target,
      final VertxHttpClient client,
      final Retryer retryer,
      final List<RequestInterceptor> requestInterceptors,
      final Logger logger,
      final Logger.Level logLevel,
      final MethodMetadata metadata,
      final RequestTemplate.Factory buildTemplateFromArgs,
      final Decoder decoder,
      final ErrorDecoder errorDecoder,
      final boolean decode404,
      CircuitBreaker circuitBreaker) {
    this.target = target;
    this.client = client;
    this.retryer = retryer;
    this.requestInterceptors = requestInterceptors;
    this.logger = logger;
    this.logLevel = logLevel;
    this.metadata = metadata;
    this.buildTemplateFromArgs = buildTemplateFromArgs;
    this.errorDecoder = errorDecoder;
    this.decoder = decoder;
    this.decode404 = decode404;
    this.circuitBreaker = circuitBreaker;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Future invoke(final Object[] argv) {
    final RequestTemplate template = buildTemplateFromArgs.create(argv);
    final Retryer retryer = this.retryer.clone();

    final ResultHandlerWithRetryer handler = new ResultHandlerWithRetryer(template, argv, retryer);
    executeAndDecode(template, argv).setHandler(handler);

    return handler.getResultFuture();
  }

  /**
   * Executes request from {@code template} with {@code this.client} and decodes the response.
   * Result or occurred error wrapped in returned Future.
   *
   * @param template  request template
   * @param argv request argument for use when calling the fallback method
   *
   * @return future with decoded result or occurred error
   */
  private Future<Object> executeAndDecode(final RequestTemplate template, final Object[] argv) {
    final Request request = targetRequest(template);
    final Future<Object> decodedResultFuture = Future.future();
    final AsynchronousMethodHandler that = this;

    logRequest(request);

    final Instant start = Instant.now();

    /**
     * Define the handler as a variable as we need to use it in two
     * different scenarios towards the end of the function.
     */
    final Handler<AsyncResult<Response>> handler = res -> {
      boolean shouldClose = true;

      final long elapsedTime = Duration.between(start, Instant.now()).toMillis();

      // Process the response!
      if (res.succeeded()) {

        /* Just as executeAndDecode in SynchronousMethodHandler but wrapped in Future */
        Response response = res.result();

        try {
          // TODO: check why this buffering is needed
          if (logLevel != Logger.Level.NONE) {
            response = logger.logAndRebufferResponse(
                metadata.configKey(),
                logLevel,
                response,
                elapsedTime);
          }

          if (Response.class == metadata.returnType()) {
            if (response.body() == null) {
              decodedResultFuture.complete(response);
            } else if (response.body().length() == null
                || response.body().length() > MAX_RESPONSE_BUFFER_SIZE) {
              shouldClose = false;
              decodedResultFuture.complete(response);
            } else {
              final byte[] bodyData = Util.toByteArray(response.body().asInputStream());
              decodedResultFuture.complete(Response.create(
                  response.status(),
                  response.reason(),
                  response.headers(),
                  bodyData));
            }
          } else if (response.status() >= 200 && response.status() < 300) {
            if (Void.class == metadata.returnType()) {
              decodedResultFuture.complete();
            } else {
              decodedResultFuture.complete(decode(response));
            }
          } else if (decode404 && response.status() == 404) {
            decodedResultFuture.complete(decoder.decode(response, metadata.returnType()));
          } else {
            decodedResultFuture.fail(errorDecoder.decode(metadata.configKey(), response));
          }
        } catch (final IOException ioException) {
          logIoException(ioException, elapsedTime);
          decodedResultFuture.fail(errorReading(request, response, ioException));
        } catch (FeignException exception) {
          decodedResultFuture.fail(exception);
        } finally {
          if (shouldClose) {
            ensureClosed(response.body());
          }
        }
      } else {
        if (res.cause() instanceof IOException) {
          logIoException((IOException) res.cause(), elapsedTime);
          decodedResultFuture.fail(errorExecuting(request, (IOException) res.cause()));
        } else {
          decodedResultFuture.fail(res.cause());
        }
      }
    };

    /**
     * Now execute the REST call
     */
    if ( null == circuitBreaker ) {
      // No breaker, use a traditional call with the handler defined above
      client.execute(request).setHandler(handler);
    }
    else {
      // Breaker present, execute the REST call fallback semantics
      circuitBreaker.executeWithFallback(
          //
          // Operation to be performed. (breakerFuture tells us the state of the breaker)
          //
          breakerFuture ->
              // HTTP Client request
              client.execute(request).setHandler( result -> {
                // Process result
                if ( breakerFuture.isComplete() ) {
                  // Breaker has already fired, this is typically due to the breaker timeout < response time.
                  // Close the response without processing it to ensure no sockets are leaked
                  if ( result.succeeded() ) {
                    ensureClosed(result.result().body());
                  }
                }
                else {
                  // Run the handler to process the result, the side effect is decodedResultFuture will be marked success/fail
                  handler.handle(result);

                  // Bubble up the status to the circuit breaker by updating breakerFuture
                  if (decodedResultFuture.succeeded()) {
                    breakerFuture.complete(decodedResultFuture.result());
                  } else {
                    breakerFuture.fail(decodedResultFuture.cause());
                  }
                }
              }),
          //
          // Fallback when breaker is open
          //
          throwable -> {
            Future result = null;

            try {
              if ( null != fallbackMethod ) {
                // Invoke the fallback method!
                result = (Future) that.fallbackMethod.invoke(that.fallbackInstance, argv);
              }
            }
            catch (InvocationTargetException ite) {
              result = Future.future();
              result.fail(ite.getCause()); // getCause() is the exception thrown by the function
            }
            catch (Throwable t) {
              result = Future.future();
              result.fail(t);
            }
            finally {
              if ( null == result ) {
                // Fallback provided a null response (unimplemented fallback)
                // Provide a nicer exception class for the breaker exception (default is io.vertx.core.impl.NoStackTraceThrowable)
                decodedResultFuture.fail( new IllegalStateException( "CircuitBreaker: "+throwable.getMessage()) );
              }
              else if ( result.succeeded() ) {
                decodedResultFuture.complete(result.result()); // Success :-)
              }
              else {
                decodedResultFuture.fail(result.cause()); // Failure :-(
              }
            }
            return null; // null because we use decodedResultFuture to signal completion
    });
    }

    // Returns the future that will contain the final results
    return decodedResultFuture;
  }

  /**
   * Associates request to defined target.
   *
   * @param template  request template
   *
   * @return fully formed request
   */
  private Request targetRequest(final RequestTemplate template) {
    for (final RequestInterceptor interceptor : requestInterceptors) {
      interceptor.apply(template);
    }

    return target.apply(new RequestTemplate(template));
  }

  /**
   * Transforms HTTP response body into object using decoder.
   *
   * @param response  HTTP response
   *
   * @return decoded result
   *
   * @throws IOException IO exception during the reading of InputStream of response
   * @throws DecodeException when decoding failed due to a checked or unchecked exception besides
   *     IOException
   * @throws FeignException when decoding succeeds, but conveys the operation failed
   */
  private Object decode(final Response response) throws IOException, FeignException {
    try {
      return decoder.decode(response, metadata.returnType());
    } catch (final FeignException feignException) {
      /* All feign exception including decode exceptions */
      throw feignException;
    } catch (final RuntimeException unexpectedException) {
      /* Any unexpected exception */
      throw new DecodeException(unexpectedException.getMessage(), unexpectedException);
    }
  }

  /**
   * Logs request.
   *
   * @param request  HTTP request
   */
  private void logRequest(final Request request) {
    if (logLevel != Logger.Level.NONE) {
      logger.logRequest(metadata.configKey(), logLevel, request);
    }
  }

  /**
   * Logs IO exception.
   *
   * @param exception  IO exception
   * @param elapsedTime  time spent to execute request
   */
  private void logIoException(final IOException exception, final long elapsedTime) {
    if (logLevel != Logger.Level.NONE) {
      logger.logIOException(metadata.configKey(), logLevel, exception, elapsedTime);
    }
  }

  /**
   * Logs retry.
   */
  private void logRetry() {
    if (logLevel != Logger.Level.NONE) {
      logger.logRetry(metadata.configKey(), logLevel);
    }
  }

  static final class Factory {
    private final VertxHttpClient client;
    private final Retryer retryer;
    private final List<RequestInterceptor> requestInterceptors;
    private final Logger logger;
    private final Logger.Level logLevel;
    private final boolean decode404;
    private final CircuitBreaker circuitBreaker;

    Factory(
        final VertxHttpClient client,
        final Retryer retryer,
        final List<RequestInterceptor> requestInterceptors,
        final Logger logger,
        final Logger.Level logLevel,
        final boolean decode404,
        final CircuitBreaker circuitBreaker) {
      this.client = client;
      this.retryer = retryer;
      this.requestInterceptors = requestInterceptors;
      this.logger = logger;
      this.logLevel = logLevel;
      this.decode404 = decode404;
      this.circuitBreaker = circuitBreaker;
    }

    MethodHandler create(
        final Target<?> target,
        final MethodMetadata metadata,
        final RequestTemplate.Factory buildTemplateFromArgs,
        final Decoder decoder,
        final ErrorDecoder errorDecoder) {
      return new AsynchronousMethodHandler(
          target,
          client,
          retryer,
          requestInterceptors,
          logger,
          logLevel,
          metadata,
          buildTemplateFromArgs,
          decoder,
          errorDecoder,
          decode404,
          circuitBreaker);
    }
  }

  /**
   * Handler for {@link AsyncResult} able to retry execution of request. In this case handler passed
   * to new request.
   *
   * @param <T>  type of response
   */
  private final class ResultHandlerWithRetryer<T> implements Handler<AsyncResult<T>> {
    private final RequestTemplate template;
    private final Retryer retryer;
    private final Object[] argv;
    private final Future<T> resultFuture = Future.future();

    private ResultHandlerWithRetryer(final RequestTemplate template, final Object[] argv, final Retryer retryer) {
      this.template = template;
      this.argv = argv;
      this.retryer = retryer;
    }

    /**
     * In case of failure retries HTTP request passing itself as handler.
     *
     * @param result  result of asynchronous HTTP request execution
     */
    @Override
    @SuppressWarnings("unchecked")
    public void handle(AsyncResult<T> result) {
      if (result.succeeded()) {
        this.resultFuture.complete(result.result());
      } else {
        try {
          throw result.cause();
        } catch (final RetryableException retryableException) {
          try {
            this.retryer.continueOrPropagate(retryableException);
            logRetry();
            ((Future<T>) executeAndDecode(this.template, argv)).setHandler(this);
          } catch (final RetryableException noMoreRetryAttempts) {
            this.resultFuture.fail(noMoreRetryAttempts);
          }
        } catch (final Throwable otherException) {
          this.resultFuture.fail(otherException);
        }
      }
    }

    /**
     * Returns a future that will be completed after successful execution or after all attempts
     * finished by fail.
     *
     * @return future with result of attempts
     */
    private Future<?> getResultFuture() {
      return this.resultFuture;
    }
  }


  /**
   * Locate the fallback instance from the {@code @Fallback} annotation
   * This may return null if none is available
   * @return
   */
  private Object getFallbackInstance() throws IllegalAccessException, InstantiationException {
    Object ret = fallbackInstanceCache.get(target.type());

    if (null == ret) {
      // Cache miss, find the fallback class
      Class fallbackClass = null;

      for (Annotation annotation : target.type().getAnnotations()) {
        if (annotation instanceof Fallback) {
          fallbackClass = ((Fallback) annotation).value();
          break;
        }
      }

      if (null != fallbackClass) {
        ret = fallbackClass.newInstance();
        fallbackInstanceCache.put(target.type(), ret);
      }
    }

    return ret;
  }

  /**
   * Return the fallback method or null if there isn't one available
   * @param method
   * @param fallbackInstance
   * @return
   */
  private Method getFallbackMethod(final Method method, final Object fallbackInstance) {
    Method ret = null;

    if ( null != fallbackInstance ) {
      // Make sure fallbackInstance is derived from the same interface as our target.type()
      boolean fallbackInstaceCompatible = false;
      for( Class iface : fallbackInstance.getClass().getInterfaces() ) {
        if ( iface.equals(target.type()) ) {
          fallbackInstaceCompatible = true;
          break;
        }
      }

      // Find the exact method on the fallbackInstance that matches the method argument
      if ( fallbackInstaceCompatible ) {
        for( Method m : fallbackInstance.getClass().getDeclaredMethods() ) {
          // Now match the method to the instance
          if ( m.getName().equals(method.getName()) ) {
            if ( m.getReturnType().equals(method.getReturnType())) {
              if ( m.getParameterCount() == method.getParameterCount() ) {
                ret = m; // we set to null if there's a mismatch
                Parameter[] a = m.getParameters();
                Parameter[] b = method.getParameters();
                for (int i = 0; i < a.length; i++) {
                  if ( a[i].getType() != b[i].getType() ) {
                    ret = null;
                    break;
                  }
                }
              }
            }
          }

          if ( null != ret ) break; // done
        }
      }
    }

    return ret;
  }

  /**
   * To enable circuit breaker fallback we need to have the original Method
   * definition. Unfortunately the core Feign interfaces and factories do not pass
   * this to a MethodHandler (this class).
   * Call setMethodDetail with the reflection data for the matching Feign method.
   * Side-effect: this will also configure fallbackInstance and fallbackMethod
   * @param method
   * @throws IllegalArgumentException when the method doesn't match this asynchronous handler
   */
  public void updateMethodDetail(Method method) throws IllegalArgumentException {
    // Validate the method argument matches this handler's configuration
    try {
      String methodKey = Feign.configKey(Class.forName(target.type().getName()), method);
      if ( metadata.configKey().equals(methodKey) ) {
        // Store the method
        thisMethod = method;
        /// Now fetch the fallback instance and method where available
        fallbackInstance = getFallbackInstance();
        fallbackMethod = getFallbackMethod(thisMethod, fallbackInstance);
      }
      else {
        // Problem
        throw new IllegalArgumentException("Method key '"+methodKey+"' does not match this handler's key signature '"+ metadata.configKey()+"'");
      }
    } catch (ClassNotFoundException e) {
      // Problem
      throw new IllegalArgumentException("The target for this handler cannot be found: "+target.type().getName());
    } catch (IllegalAccessException | InstantiationException e) {
      throw new IllegalArgumentException("A fallback class was found but could not be instantiated for "+target.type().getName());
    }
  }
}
