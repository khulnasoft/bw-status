package bw.status.undertow.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathTemplateHandler;
import io.undertow.util.PathTemplateMatcher;
import java.util.ArrayDeque;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RequestValues}.
 */
public final class RequestValuesTest {
  private static final String PARAMETER_NAME = "foo";
  private static final int VALUE_IF_ABSENT = -11;
  private static final int VALUE_IF_MALFORMED = 62;
  private static final int EXPECTED_VALUE_AS_INT = 7;
  private static final String EXPECTED_VALUE_AS_STRING = "7";
  private static final String UNPARSEABLE_VALUE = "ten";

  /**
   * Verifies that {@link
   * RequestValues#queryParameter(HttpServerExchange, String)} reads a present
   * value from the query string.
   */
  @Test
  public void testQueryParameter_happyPath() {
    var exchange = new HttpServerExchange(null);
    exchange.addQueryParam(PARAMETER_NAME, EXPECTED_VALUE_AS_STRING);
    assertEquals(
        EXPECTED_VALUE_AS_STRING,
        RequestValues.queryParameter(exchange, PARAMETER_NAME));
  }

  /**
   * Verifies that {@link
   * RequestValues#queryParameter(HttpServerExchange, String)} returns {@code
   * null} when there is no query parameter with the given name.
   */
  @Test
  public void testQueryParameter_nullWhenNoValue() {
    var exchange = new HttpServerExchange(null);
    assertNull(RequestValues.queryParameter(exchange, PARAMETER_NAME));
  }

  /**
   * Verifies that {@link
   * RequestValues#queryParameter(HttpServerExchange, String)} returns {@code
   * null} when the exchange has an empty collection of values for the query
   * parameter with the given name.
   *
   * <p>This is a slightly different scenario than when there is no value for
   * the parameter at all.  It may be that the only way to reach this state is
   * to manipulate the query parameter value collection directly.
   */
  @Test
  public void testQueryParameter_nullWhenEmptyValueCollection() {
    var exchange = new HttpServerExchange(null);
    exchange.getQueryParameters().put(PARAMETER_NAME, new ArrayDeque<>());
    assertNull(RequestValues.queryParameter(exchange, PARAMETER_NAME));
  }

  /**
   * Verifies that {@link
   * RequestValues#queryParameter(HttpServerExchange, String)} returns {@code
   * null} when there are two values for the given query parameter.
   */
  @Test
  public void testQueryParameter_nullWhenTwoValues() {
    var exchange = new HttpServerExchange(null);
    exchange.addQueryParam(PARAMETER_NAME, EXPECTED_VALUE_AS_STRING);
    exchange.addQueryParam(PARAMETER_NAME, EXPECTED_VALUE_AS_STRING);
    assertNull(RequestValues.queryParameter(exchange, PARAMETER_NAME));
  }

  /**
   * Verifies that {@link
   * RequestValues#queryParameterAsInt(HttpServerExchange, String, int, int)}
   * reads a present integer value from the query string.
   */
  @Test
  public void testQueryParameterAsInt_happyPath() {
    var exchange = new HttpServerExchange(null);
    exchange.addQueryParam(PARAMETER_NAME, EXPECTED_VALUE_AS_STRING);
    assertEquals(
        EXPECTED_VALUE_AS_INT,
        RequestValues.queryParameterAsInt(
            exchange, PARAMETER_NAME, VALUE_IF_ABSENT, VALUE_IF_MALFORMED));
  }

  /**
   * Verifies that {@link
   * RequestValues#queryParameterAsInt(HttpServerExchange, String, int, int)}
   * returns the default value when there is no query parameter with the given
   * name.
   */
  @Test
  public void testQueryParameterAsInt_defaultWhenNoValue() {
    var exchange = new HttpServerExchange(null);
    assertEquals(
        VALUE_IF_ABSENT,
        RequestValues.queryParameterAsInt(
            exchange, PARAMETER_NAME, VALUE_IF_ABSENT, VALUE_IF_MALFORMED));
  }

  /**
   * Verifies that {@link
   * RequestValues#queryParameterAsInt(HttpServerExchange, String, int, int)}
   * returns the default value when the exchange has an empty collection of
   * values for the query parameter with the given name.
   *
   * <p>This is a slightly different scenario than when there is no value for
   * the parameter at all.  It may be that the only way to reach this state is
   * to manipulate the query parameter value collection directly.
   */
  @Test
  public void testQueryParameterAsInt_defaultWhenEmptyValueCollection() {
    var exchange = new HttpServerExchange(null);
    exchange.getQueryParameters().put(PARAMETER_NAME, new ArrayDeque<>());
    assertEquals(
        VALUE_IF_ABSENT,
        RequestValues.queryParameterAsInt(
            exchange, PARAMETER_NAME, VALUE_IF_ABSENT, VALUE_IF_MALFORMED));
  }

  /**
   * Verifies that {@link
   * RequestValues#queryParameterAsInt(HttpServerExchange, String, int, int)}
   * returns the default value when there are two values for the given query
   * parameter.
   */
  @Test
  public void testQueryParameterAsInt_defaultWhenTwoValues() {
    var exchange = new HttpServerExchange(null);
    exchange.addQueryParam(PARAMETER_NAME, EXPECTED_VALUE_AS_STRING);
    exchange.addQueryParam(PARAMETER_NAME, EXPECTED_VALUE_AS_STRING);
    assertEquals(
        VALUE_IF_MALFORMED,
        RequestValues.queryParameterAsInt(
            exchange, PARAMETER_NAME, VALUE_IF_ABSENT, VALUE_IF_MALFORMED));
  }

  /**
   * Verifies that {@link
   * RequestValues#queryParameterAsInt(HttpServerExchange, String, int, int)}
   * returns the default value when there is one value for the given query
   * parameter but it cannot be parsed as an integer.
   */
  @Test
  public void testQueryParameterAsInt_defaultWhenUnparseableValue() {
    var exchange = new HttpServerExchange(null);
    exchange.addQueryParam(PARAMETER_NAME, UNPARSEABLE_VALUE);
    assertEquals(
        VALUE_IF_MALFORMED,
        RequestValues.queryParameterAsInt(
            exchange, PARAMETER_NAME, VALUE_IF_ABSENT, VALUE_IF_MALFORMED));
  }

  /**
   * Verifies that {@link RequestValues#pathParameter(HttpServerExchange,
   * String)} returns the expected values for a request matching a registered
   * path template.
   */
  @Test
  public void testPathParameter_matchingPath() throws Exception {
    var handler = new PathTemplateHandler();
    handler.add("/prefix/{a}/{b}/*", exchange -> {});

    var exchange = new HttpServerExchange(null);
    exchange.setRelativePath("/prefix/foo/bar/baz/qux");
    handler.handleRequest(exchange);

    assertEquals(
        Optional.of("foo"),
        RequestValues.pathParameter(exchange, "a"));

    assertEquals(
        Optional.of("bar"),
        RequestValues.pathParameter(exchange, "b"));

    assertEquals(
        Optional.of("baz/qux"),
        RequestValues.pathParameter(exchange, "*"));

    assertEquals(
        Optional.empty(),
        RequestValues.pathParameter(exchange, "undeclared"));
  }

  /**
   * Verifies that {@link RequestValues#pathParameter(HttpServerExchange,
   * String)} returns empty values for a request not matching any registered
   * path template.
   */
  @Test
  public void testPathParameter_noMatchingPath() throws Exception {
    var handler = new PathTemplateHandler();
    handler.add("/prefix/{a}/{b}/*", exchange -> {});

    var exchange = new HttpServerExchange(null);
    exchange.setRelativePath("/wrong_prefix/foo/bar/baz/qux");
    handler.handleRequest(exchange);

    assertEquals(
        Optional.empty(),
        RequestValues.pathParameter(exchange, "a"));

    assertEquals(
        Optional.empty(),
        RequestValues.pathParameter(exchange, "b"));

    assertEquals(
        Optional.empty(),
        RequestValues.pathParameter(exchange, "*"));

    assertEquals(
        Optional.empty(),
        RequestValues.pathParameter(exchange, "undeclared"));
  }

  /**
   * Verifies that {@link RequestValues#pathParameter(HttpServerExchange,
   * String)} returns empty values for a request that was not processed by a
   * {@link PathTemplateMatcher}.
   */
  @Test
  public void testPathParameter_noMatcher() {
    var exchange = new HttpServerExchange(null);
    exchange.setRelativePath("/prefix/foo/bar/baz/qux");

    assertEquals(
        Optional.empty(),
        RequestValues.pathParameter(exchange, "*"));

    assertEquals(
        Optional.empty(),
        RequestValues.pathParameter(exchange, "undeclared"));
  }
}
