package bw.status.undertow.extensions;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.HttpHeaders.USER_AGENT;
import static com.google.common.net.HttpHeaders.VARY;
import static com.google.common.net.MediaType.ANY_TYPE;
import static io.undertow.util.StatusCodes.BAD_REQUEST;
import static io.undertow.util.StatusCodes.INTERNAL_SERVER_ERROR;
import static io.undertow.util.StatusCodes.NOT_ACCEPTABLE;
import static io.undertow.util.StatusCodes.NO_CONTENT;
import static io.undertow.util.StatusCodes.OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static bw.status.testlib.MoreAssertions.assertMediaType;

import com.google.common.net.MediaType;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.SetHeaderHandler;
import io.undertow.util.Headers;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import bw.status.testlib.HttpTester;
import bw.status.testlib.TestServicesInjector;

/**
 * Tests for {@link AcceptHandler}.
 */
@ExtendWith(TestServicesInjector.class)
public final class AcceptHandlerTest {
  /**
   * Verifies that an {@link AcceptHandler} with no handlers added rejects all
   * requests.
   */
  @Test
  public void testNoMediaTypesAllowed(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler = AcceptHandler.builder().build();

    String path = http.addHandler(handler);

    HttpResponse<String> response1 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "text/plain")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(NOT_ACCEPTABLE, response1.statusCode());

    HttpResponse<String> response2 =
        http.client().send(
            http.newRequestBuilder(path).build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(NOT_ACCEPTABLE, response2.statusCode());
  }

  /**
   * Verifies that an {@link AcceptHandler} routes requests to the correct
   * handler when the media types for each handler are unrelated.
   */
  @Test
  public void testUnrelatedMediaTypes(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            .add(
                "text/plain",
                new FixedResponseBodyHandler("plaintextHandler"))
            .add(
                "application/json",
                new FixedResponseBodyHandler("jsonHandler"))
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response1 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "text/plain")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response1.statusCode());
    assertEquals("plaintextHandler", response1.body());

    HttpResponse<String> response2 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "application/json")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response2.statusCode());
    assertEquals("jsonHandler", response2.body());

    HttpResponse<String> response3 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "foo/bar")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(NOT_ACCEPTABLE, response3.statusCode());

    HttpResponse<String> response4 =
        http.client().send(
            http.newRequestBuilder(path).build(),
            HttpResponse.BodyHandlers.ofString());

    // We don't know which handler handled it -- that's unspecified -- but we
    // know it was one of our handlers.
    assertEquals(OK, response4.statusCode());
    assertTrue(
        Set.of("plaintextHandler", "jsonHandler").contains(response4.body()),
        "Unexpected response body: " + response4.body());
  }

  /**
   * Verifies that an {@link AcceptHandler} routes requests to the most
   * specific matching handler when the media types for each handler are related
   * and overlapping.
   */
  @Test
  public void testMostSpecificMediaType(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            // Intentional ordering of .add(...) calls.
            .add(
                "text/plain",
                new FixedResponseBodyHandler("plainHandler"))
            // Add a more specific media type.
            .add(
                "text/plain;charset=utf-8",
                new FixedResponseBodyHandler("utf8Handler"))
            // Add a less specific media type.
            .add(
                "text/*",
                new FixedResponseBodyHandler("otherHandler"))
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response1 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "text/plain;charset=utf-8")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response1.statusCode());
    assertEquals("utf8Handler", response1.body());

    HttpResponse<String> response2 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "text/plain;charset=us-ascii")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response2.statusCode());
    assertEquals("plainHandler", response2.body());

    HttpResponse<String> response3 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "text/plain")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response3.statusCode());
    assertEquals("utf8Handler", response3.body());

    HttpResponse<String> response4 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "text/css")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response4.statusCode());
    assertEquals("otherHandler", response4.body());

    HttpResponse<String> response5 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "foo/bar")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(NOT_ACCEPTABLE, response5.statusCode());

    HttpResponse<String> response6 =
        http.client().send(
            http.newRequestBuilder(path)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

    // We don't know which handler handled it -- that's unspecified -- but we
    // know it was one of our handlers.
    assertEquals(OK, response6.statusCode());
    assertTrue(
        Set.of("plainHandler", "utf8Handler", "otherHandler")
           .contains(response6.body()),
        "Unexpected response body: " + response6.body());
  }

  /**
   * Verifies that an {@link AcceptHandler} supports wildcards in both the
   * server-supported media types and the incoming {@code Accept} headers.
   */
  @Test
  public void testWildcards(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            .add(
                "text/*",
                new FixedResponseBodyHandler("textHandler"))
            .add(
                "text/*; charset=utf-8",
                new FixedResponseBodyHandler("textUtf8Handler"))
            .add(
                "text/html",
                new FixedResponseBodyHandler("htmlHandler"))
            .add(
                "text/html; charset=utf-8",
                new FixedResponseBodyHandler("htmlUtf8Handler"))
            .add(
                "*/*",
                new FixedResponseBodyHandler("anyHandler"))
            .add(
                "*/*; charset=utf-8",
                new FixedResponseBodyHandler("anyUtf8Handler"))
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response1 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "application/json, text/xml, text/html")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response1.statusCode());
    assertEquals("anyUtf8Handler", response1.body());

    HttpResponse<String> response2 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT,
                        "application/json;q=0.5, text/xml, text/html")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response2.statusCode());
    assertEquals("textUtf8Handler", response2.body());

    HttpResponse<String> response3 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT,
                        "application/json;q=0.5, text/xml;q=0.5, text/html")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response3.statusCode());
    assertEquals("htmlUtf8Handler", response3.body());

    HttpResponse<String> response4 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "application/json, text/xml, text/*")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response4.statusCode());
    assertEquals("anyUtf8Handler", response4.body());

    HttpResponse<String> response5 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT,
                        "application/json;q=0.5, text/xml, text/*")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response5.statusCode());
    assertEquals("textUtf8Handler", response5.body());

    HttpResponse<String> response6 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT,
                        "application/json;q=0.5, text/xml;q=0.5, text/*")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response6.statusCode());
    assertEquals("htmlUtf8Handler", response6.body());

    HttpResponse<String> response7 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT,
                        "application/json;q=0.5, text/xml;q=0.5, */*")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response7.statusCode());
    assertEquals("htmlUtf8Handler", response7.body());

    HttpResponse<String> response8 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT,
                        "application/json;charset=utf-16, text/xml, text/html")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response8.statusCode());
    assertEquals("anyHandler", response8.body());

    HttpResponse<String> response9 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT,
                        "application/json;charset=utf-16;q=0.5, text/xml;charset=utf-16, text/html")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response9.statusCode());
    assertEquals("textHandler", response9.body());

    HttpResponse<String> response10 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "application/json;charset=utf-16;q=0.5, text/xml;charset=utf-16;q=0.5, text/html;charset=utf-16")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response10.statusCode());
    assertEquals("htmlHandler", response10.body());
  }

  /**
   * Verifies that {@link AcceptHandler.Builder#add(String,
   * HttpHandler)} throws an exception for an already-added method.
   */
  @Test
  public void testDuplicateMediaTypeRejected() {
    AcceptHandler.Builder builder =
        AcceptHandler
            .builder()
            .add("text/plain", exchange -> {});

    assertThrows(
        IllegalStateException.class,
        () -> builder.add("text/plain", exchange -> {}));
  }

  /**
   * Verifies that an {@link AcceptHandler} with a handler for {@link
   * MediaType#ANY_TYPE} allows requests having any well-formed {@code Accept}
   * header and requests with no {@code Accept} header at all, but rejects
   * requests having a malformed {@code Accept} header.
   */
  @Test
  public void testAnyMediaType(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            .add(
                ANY_TYPE,
                new FixedResponseBodyHandler("wildcardHandler"))
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response1 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "foo/bar")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response1.statusCode());
    assertEquals("wildcardHandler", response1.body());

    HttpResponse<String> response2 =
        http.client().send(
            http.newRequestBuilder(path).build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response2.statusCode());
    assertEquals("wildcardHandler", response2.body());

    HttpResponse<String> response3 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "invalid_media_type")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(NOT_ACCEPTABLE, response3.statusCode());
    assertEquals("", response3.body());
  }

  /**
   * Verifies that an {@link AcceptHandler} prefers more specific media types in
   * the {@code Accept} header over less specific media types.
   */
  @Test
  public void testAcceptSpecificity(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            .add(
                "text/plain;format=flowed",
                new FixedResponseBodyHandler("textHandler"))
            .add(
                // Provides a more specific media type than the text handler,
                // but matches against a less specific type in the Accept
                // header.
                "foo/bar;a=1;b=2",
                new FixedResponseBodyHandler("otherHandler"))
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "*/*, text/*, text/plain, text/plain;format=flowed")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response.statusCode());
    assertEquals("textHandler", response.body());
  }

  /**
   * Verifies that an {@link AcceptHandler} adds a {@code Content-Type} header
   * to the response.
   */
  @Test
  public void testContentTypeSet(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            .add(
                "text/plain",
                new FixedResponseBodyHandler("textHandler"))
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response =
        http.client().send(
            http.newRequestBuilder(path).build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response.statusCode());

    assertMediaType(
        "text/plain",
        response.headers()
                .firstValue(CONTENT_TYPE)
                .orElse(null));

    assertEquals("textHandler", response.body());
  }

  /**
   * Verifies that an {@link AcceptHandler} does not add a {@code Content-Type}
   * header to the response if the media type of the handler has a wildcard.
   */
  @Test
  public void testContentTypeNotSetIfWildcard(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            .add(
                "text/*",
                new FixedResponseBodyHandler("textHandler"))
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response =
        http.client().send(
            http.newRequestBuilder(path).build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response.statusCode());

    assertEquals(
        Optional.empty(),
        response.headers().firstValue(CONTENT_TYPE));

    assertEquals("textHandler", response.body());
  }

  /**
   * Verifies that an {@link AcceptHandler} does not modify the {@code
   * Content-Type} header of a response that already contains a {@code
   * Content-Type} header.
   */
  @Test
  public void testContentTypeNotOverridden(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            .add(
                "text/plain",
                new SetHeaderHandler(
                    new FixedResponseBodyHandler("textHandler"),
                    CONTENT_TYPE,
                    "application/json"))
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response =
        http.client().send(
            http.newRequestBuilder(path).build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response.statusCode());

    assertMediaType(
        "application/json",
        response.headers()
                .firstValue(CONTENT_TYPE)
                .orElse(null));

    assertEquals("textHandler", response.body());
  }

  /**
   * Verifies that an {@link AcceptHandler} does not add a {@code Content-Type}
   * header to the response if the handler threw an exception.
   */
  @Test
  public void testContentTypeNotSetIfExceptionThrown(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            .add(
                "text/plain",
                exchange -> {
                  throw new IOException();
                })
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response =
        http.client().send(
            http.newRequestBuilder(path).build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(INTERNAL_SERVER_ERROR, response.statusCode());

    assertEquals(
        Optional.empty(),
        response.headers().firstValue(CONTENT_TYPE));

    assertEquals("", response.body());
  }

  /**
   * Verifies that an {@link AcceptHandler} adds a {@code Content-Type} header
   * to the response when the response body is empty and the status code is 2xx
   * but not 204.
   */
  @Test
  public void testContentTypeSetOnEmptyOkResponse(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            .add(
                "text/plain",
                exchange -> {})
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response =
        http.client().send(
            http.newRequestBuilder(path).build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response.statusCode());

    assertMediaType(
        "text/plain",
        response.headers()
                .firstValue(CONTENT_TYPE)
                .orElse(null));

    assertEquals("", response.body());
  }

  /**
   * Verifies that an {@link AcceptHandler} does not add a {@code Content-Type}
   * header to the response when the status code is 204.
   */
  @Test
  public void testContentTypeNotSetOnNoContentResponse(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            .add(
                "text/plain",
                exchange -> {
                  exchange.setStatusCode(NO_CONTENT);
                })
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response =
        http.client().send(
            http.newRequestBuilder(path).build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(NO_CONTENT, response.statusCode());

    assertEquals(
        Optional.empty(),
        response.headers().firstValue(CONTENT_TYPE));

    assertEquals("", response.body());
  }

  /**
   * Verifies that an {@link AcceptHandler} does not add a {@code Content-Type}
   * header to the response when the response body is empty and the status code
   * is not 2xx.
   */
  @Test
  public void testContentTypeNotSetOnEmptyNotOkResponse(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            .add(
                "text/plain",
                exchange -> {
                  exchange.setStatusCode(BAD_REQUEST);
                })
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response =
        http.client().send(
            http.newRequestBuilder(path).build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(BAD_REQUEST, response.statusCode());

    assertEquals(
        Optional.empty(),
        response.headers().firstValue(CONTENT_TYPE));

    assertEquals("", response.body());
  }

  /**
   * Verifies that an {@link AcceptHandler} adds a {@code Content-Type} header
   * to the response when the response body is non-empty and the status code is
   * not 2xx.
   */
  @Test
  public void testContentTypeSetOnNonEmptyNotOkResponse(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            .add(
                "text/plain",
                exchange -> {
                  exchange.setStatusCode(BAD_REQUEST);
                  exchange.getResponseSender().send("bad");
                })
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response =
        http.client().send(
            http.newRequestBuilder(path).build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(BAD_REQUEST, response.statusCode());

    assertMediaType(
        "text/plain",
        response.headers()
                .firstValue(CONTENT_TYPE)
                .orElse(null));

    assertEquals("bad", response.body());
  }

  /**
   * Verifies that an {@link AcceptHandler} adds {@code Vary: Accept} to all
   * responses.
   */
  @Test
  public void testVaryHeaderAdded(HttpTester http)
      throws IOException, InterruptedException {

    AcceptHandler handler =
        AcceptHandler
            .builder()
            .add(
                "text/plain;a=1",
                exchange -> {
                  exchange.getResponseSender().send("handler1");
                })
            .add(
                "text/plain;a=2",
                exchange -> {
                  exchange.getResponseHeaders().add(Headers.VARY, USER_AGENT);
                  exchange.getResponseSender().send("handler2");
                })
            .build();

    String path = http.addHandler(handler);

    HttpResponse<String> response1 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "text/plain;a=1")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response1.statusCode());

    assertEquals(
        Set.of(ACCEPT),
        Set.copyOf(response1.headers().allValues(VARY)));

    assertEquals("handler1", response1.body());

    HttpResponse<String> response2 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "text/plain;a=2")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(OK, response2.statusCode());

    assertEquals(
        Set.of(ACCEPT, USER_AGENT),
        Set.copyOf(response2.headers().allValues(VARY)));

    assertEquals("handler2", response2.body());

    HttpResponse<String> response3 =
        http.client().send(
            http.newRequestBuilder(path)
                .header(ACCEPT, "text/plain;a=3")
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(NOT_ACCEPTABLE, response3.statusCode());

    assertEquals(
        Set.of(ACCEPT),
        Set.copyOf(response3.headers().allValues(VARY)));

    assertEquals("", response3.body());
  }
}
