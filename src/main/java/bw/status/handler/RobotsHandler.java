package bw.status.handler;

import io.undertow.server.HttpHandler;
import jakarta.inject.Singleton;
import org.glassfish.hk2.extras.provides.Provides;
import bw.status.handler.routing.Route;
import bw.status.undertow.extensions.FixedResponseBodyHandler;

/**
 * Handles requests for robots.txt.
 */
public final class RobotsHandler {
  private RobotsHandler() {
    throw new AssertionError("This class cannot be instantiated");
  }

  @Provides
  @Singleton
  @Route(
      method = "GET",
      path = "/robots.txt",
      produces = "text/plain; charset=utf-8")
  public static HttpHandler robotsHandler() {
    return new FixedResponseBodyHandler(
        """
        User-agent: *
        Allow: /$
        Allow: /assets
        Disallow: /
        """);
  }
}
