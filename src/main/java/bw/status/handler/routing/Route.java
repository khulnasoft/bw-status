package bw.status.handler.routing;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import bw.status.undertow.extensions.AcceptHandler;
import bw.status.undertow.extensions.MediaTypeHandler;
import bw.status.undertow.extensions.MethodHandler;
import bw.status.undertow.extensions.PathPatternHandler;
import bw.status.undertow.extensions.RequestValues;
import bw.status.util.PathPattern;

/**
 * Indicates that the annotated service is an {@link HttpHandler} that handles
 * requests with the specified {@linkplain HttpServerExchange#getRequestMethod()
 * method}, {@linkplain HttpServerExchange#getRelativePath() path}, and media
 * type, and that produces responses with the specified media type.
 *
 * <p>A single HTTP handler may be annotated with multiple routes to indicate
 * that it accepts requests matching any of those routes.
 *
 * <p>Multiple routes may refer to the same path as long as they refer to
 * different methods or media types.  It is an error to declare two identical
 * routes.
 *
 * <p>Support for OPTIONS requests is added automatically to all paths.  Support
 * for HEAD requests is added automatically to all paths that have a GET {@link
 * #method()} declared.  These default handlers can be overridden on a
 * path-specific basis by declaring a route whose {@link #method()} is OPTIONS
 * or HEAD.
 *
 * <p>Routing is implemented in this order:
 *
 * <ol>
 * <li>By request path, using a {@link PathPatternHandler}.  If there is no
 *     route with a matching {@link #path()}, then the response is {@code 404
 *     Not Found}.
 * <li>By request method, using a {@link MethodHandler}.  If there is no route
 *     with that {@link #method()}, and the method is not one of the
 *     automatically-supported methods, then the response is {@code 405 Method
 *     Not Allowed}.
 * <li>By request media type, which is determined from the {@code Content-Type}
 *     header of the request, using a {@link MediaTypeHandler}.  If there is no
 *     route that {@link #consumes()} that media type, then the response is
 *     {@code 415 Unsupported Media Type}.
 * <li>By accepted response media type, which is determined from the {@code
 *     Accept} header of the request, using an {@link AcceptHandler}.  If there
 *     is no route that {@link #produces()} that media type, then the response
 *     is {@code 406 Not Acceptable}.
 * </ol>
 */
@Repeatable(Routes.class)
@Qualifier
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
    ElementType.TYPE,
    ElementType.METHOD,
    ElementType.FIELD,
    ElementType.PARAMETER
})
public @interface Route {
  /**
   * The {@linkplain HttpServerExchange#getRequestMethod() request method}
   * matched by this route, such as GET or POST.
   */
  String method();

  /**
   * The {@linkplain PathPattern path pattern} that defines which {@linkplain
   * HttpServerExchange#getRelativePath() request paths} are matched by this
   * route.  The values of path variables can be read from a request using
   * {@link RequestValues#pathParameter(HttpServerExchange, String)}.
   */
  String path();

  /**
   * The request media type matched by this route.  The default value
   * <code>*&#47;*</code> matches all requests, including requests that do not
   * include a {@code Content-Type} header.
   */
  String consumes() default "*/*";

  /**
   * The media type of responses from this route, to be matched against the
   * {@code Accept} header of the incoming request.  The default value
   * <code>*&#47;*</code> matches requests with any well-formed {@code Accept}
   * header and requests without an {@code Accept} header.
   */
  String produces() default "*/*";

  /**
   * The key for an {@linkplain HttpServerExchange#getAttachment(AttachmentKey)
   * attachment} added to all requests that match some route, where the value of
   * the attachment is the matching {@link Route} annotation.
   *
   * <p>This may be useful when an HTTP handler annotated with multiple routes
   * should behave differently depending on which route is matched.
   */
  AttachmentKey<Route> MATCHED_ROUTE = AttachmentKey.create(Route.class);
}
