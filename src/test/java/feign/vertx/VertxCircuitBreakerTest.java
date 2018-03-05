package feign.vertx;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import feign.FeignException;
import feign.Logger;
import feign.Request;
import feign.VertxFeign;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import feign.slf4j.Slf4jLogger;
import feign.vertx.testcase.IcecreamServiceApi;
import feign.vertx.testcase.domain.Bill;
import feign.vertx.testcase.domain.Flavor;
import feign.vertx.testcase.domain.IceCreamOrder;
import feign.vertx.testcase.domain.OrderGenerator;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test the use of the Vertx CircuitBreaker with Feign
 */
@RunWith(VertxUnitRunner.class)
public class VertxCircuitBreakerTest {
  private Vertx vertx = Vertx.vertx();

  @Rule
  public WireMockRule wireMockRule = new WireMockRule(8089);

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  private String flavorsStr;

  private CircuitBreaker breaker;
  private IcecreamServiceApi client;

  private OrderGenerator generator = new OrderGenerator();

  /**
   * Configure Mock response data, circuit breaker, and Feign client.
   * @throws Exception
   */
  @Before
  public void setUp() throws Exception {
    /* Given */
    flavorsStr = Arrays
              .stream(Flavor.values())
              .map(flavor -> "\"" + flavor + "\"")
              .collect(Collectors.joining(", ", "[ ", " ]"));

    breaker = CircuitBreaker.create("breaker1", vertx,
                                      new CircuitBreakerOptions() // DO NOT change these values without considering the test cases below
                                              .setMaxFailures(2) // number of failure before opening the circuit
                                              .setTimeout(1000) // consider a failure if the operation does not succeed in time
                                              .setFallbackOnFailure(true) // Required if we want the fallback to activate
                                              .setResetTimeout(10000) // time spent in open state before attempting to re-try
                                   );

    client = VertxFeign
            .builder()
            .vertx(vertx)
            .options( new Request.Options( 10000, 10000))
            .encoder(new JacksonEncoder(TestUtils.MAPPER))
            .decoder(new JacksonDecoder(TestUtils.MAPPER))
            .logger(new Slf4jLogger())
            .logLevel(Logger.Level.FULL)
            .circuitBreaker(breaker) // <--- BREAKER
            .target(IcecreamServiceApi.class, "http://localhost:8089");

  }

  /**
   * Ensure WireMock doesn't have pending requests queued (useful after failure testing)
   * Ensure the circuit breaker doesn't carry a lingering state
   * @throws InterruptedException
   */
  @After
  public void resetWireMock() throws InterruptedException {
      // Wait to ensure any pending timers have a chance to fire
      // For example: The breaker timer fires despite a successful response, indicating a bug
      Thread.sleep(4000);
      // Now reset WireMock and the Break for the next test
      wireMockRule.resetRequests();
  }

  /**
   * Test the circuit breaker during a happy-path immediate successful response
   * @param context Test Context
   */
  @Test
  public void testBreakerSuccess(TestContext context) {

    stubFor(get(urlEqualTo("/icecream/flavors"))
            .withHeader("Accept", equalTo("application/json"))
            .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(flavorsStr)));

    Async async = context.async();

    client.getAvailableFlavors().setHandler(res -> {
      if (res.succeeded()) {
        try {
          Collection<Flavor> flavors = res.result();
          assertThat(flavors)
              .hasSize(Flavor.values().length)
              .containsAll(Arrays.asList(Flavor.values()));
          async.complete();
        } catch (Throwable exception) {
          context.fail(exception);
        }
      } else {
        context.fail(res.cause());
      }
    });
  }

  /**
   * Test the circuit breaker with a slow responding circuit that exceeds the timeout.
   * This is the simple test as the REST request has no arguments
   * @param context Test Context
   */
  @Test
  public void testBreakerFallbackSimple(TestContext context) throws InterruptedException {

    stubFor(get(urlEqualTo("/icecream/flavors"))
            .withHeader("Accept", equalTo("application/json"))
            .willReturn(aResponse()
                        .withFixedDelay(2000) // Longer than the 1000ms breaker timeout :-)
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(flavorsStr)));

    Async async = context.async();

    client.getAvailableFlavors().setHandler(res -> {
      if (res.succeeded()) {
        try {
          Collection<Flavor> flavors = res.result();
          assertThat(flavors)
              .hasSize(1)
              .containsAll(Arrays.asList(Flavor.BANANA));
          async.complete();
        } catch (Throwable exception) {
          context.fail(exception);
        }
      } else {
        context.fail(res.cause());
      }
    });
  }

  /**
   * Test the circuit breaker with a slow responding circuit that exceeds the timeout.
   * This is the complex test as the REST request has one or more nontivial arguments
   * along with a non-trival response.
   * @param context Test Context
   */
  @Test
  public void testBreakerFallbackComplex(TestContext context) throws InterruptedException {
    /* Given */
    IceCreamOrder order = generator.generate();
    Bill bill = Bill.makeBill(order);
    String orderStr = TestUtils.encodeAsJsonString(order);
    String billStr = TestUtils.encodeAsJsonString(bill);

    stubFor(post(urlEqualTo("/icecream/orders"))
        .withHeader("Content-Type", equalTo("application/json"))
        .withHeader("Accept", equalTo("application/json"))
        .withRequestBody(equalToJson(orderStr))
        .willReturn(aResponse()
                    .withFixedDelay(2000) // Longer than the 1000ms breaker timeout :-)
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(billStr)));

    Async async = context.async();

    // Since we're expecting our fallback method to be executed, rather than
    // the mock above. We will discount the bill by 10% to match the fallback logic.
    bill.setPrice(bill.getPrice()*0.90f);

    /* When */
    client.makeOrder(order).setHandler(res -> {

      /* Then */
      if (res.succeeded()) {
        try {
          Assertions.assertThat(res.result())
              .isEqualToComparingFieldByFieldRecursively(bill);
          async.complete();
        } catch (Throwable exception) {
          context.fail(exception);
        }
      } else {
        context.fail(res.cause());
      }
    });
  }

  /**
   * Test the circuit breaker with a slow responding circuit that exceeds the timeout.
   * This test ensures the framework can handle a fallback method that throws an unchecked exception
   * @param context Test Context
   */
  @Test
  public void testBreakerFallbackException(TestContext context) throws InterruptedException {
    /* Given */
    IceCreamOrder order = generator.generate();
    int orderId = order.getId();
    String orderStr = TestUtils.encodeAsJsonString(order);

    stubFor(get(urlEqualTo("/icecream/orders/" + orderId))
        .withHeader("Accept", equalTo("application/json"))
        .willReturn(aResponse()
                    .withStatus(200)
                    .withFixedDelay(2000) // Longer than the 1000ms breaker timeout :-)
                    .withHeader("Content-Type", "application/json")
                    .withBody(orderStr)));


    Async async = context.async();

    /* When */
    client.findOrder(orderId).setHandler(res -> {

      /* Then */
      if (res.failed()) {
        try {
          Assertions.assertThat(res.cause())
              .isInstanceOf(IllegalAccessError.class);

          async.complete();
        } catch (Throwable exception) {
          context.fail(exception);
        }
      } else {
        context.fail(res.cause());
      }
    });
  }

  /**
   * Test the circuit breaker with a slow responding circuit that exceeds the timeout.
   * This test exercises a fallback method that has not been implemented (return null)
   * @param context Test Context
   */
  @Test
  public void testBreakerFallbackUndefined(TestContext context) throws InterruptedException {
    /* Given */
    Bill bill = Bill.makeBill(generator.generate());
    String billStr = TestUtils.encodeAsJsonString(bill);

    stubFor(post(urlEqualTo("/icecream/bills/pay"))
        .withHeader("Content-Type", equalTo("application/json"))
        .withRequestBody(equalToJson(billStr))
        .willReturn(aResponse()
                    .withStatus(200)
                    .withFixedDelay(2000) // Longer than the 1000ms breaker timeout :-)
                    ));

    Async async = context.async();

    /* When */
    client.payBill(bill).setHandler(res -> {
      /* Then */
      if (res.failed()) {
        try {
          Assertions.assertThat(res.cause())
              .isInstanceOf(IllegalStateException.class);

          Assertions.assertThat(res.cause().getMessage())
              .containsIgnoringCase("CircuitBreaker");

          async.complete();
        } catch (Throwable exception) {
          context.fail(exception);
        }
      } else {
        context.fail(res.cause());
      }
    });
  }

}
