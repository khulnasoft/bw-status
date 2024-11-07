package bw.status.handler;

import static com.google.common.net.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static io.undertow.util.StatusCodes.NOT_FOUND;
import static bw.status.undertow.extensions.RequestValues.pathParameter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.util.Objects;
import bw.status.handler.routing.DisableCache;
import bw.status.handler.routing.Route;
import bw.status.handler.routing.SetHeader;
import bw.status.service.HomeResultsReader;
import bw.status.service.MustacheRenderer;
import bw.status.view.DetailPageView;
import bw.status.view.HomePageView.ResultsView;

/**
 * Handles requests for the results detail page.
 */
@Singleton
@Route(
    method = "GET",
    path = "/results/{uuid:[\\w-]+}",
    produces = "text/html; charset=utf-8")
@Route(
    method = "GET",
    path = "/results/{uuid:[\\w-]+}.json",
    produces = "application/json")
@DisableCache
// The JSON version of this endpoint is used by the BW website when rendering
// results by uuid.  Specifically, the BW website uses this endpoint to
// discover the names of the results.json and results.zip files associated with
// a given uuid.
@SetHeader(name = ACCESS_CONTROL_ALLOW_ORIGIN, value = "*")
public final class DetailPageHandler implements HttpHandler {
  private final HomeResultsReader homeResultsReader;
  private final MustacheRenderer mustacheRenderer;
  private final ObjectMapper objectMapper;

  @Inject
  public DetailPageHandler(HomeResultsReader homeResultsReader,
                           MustacheRenderer mustacheRenderer,
                           ObjectMapper objectMapper) {

    this.homeResultsReader = Objects.requireNonNull(homeResultsReader);
    this.mustacheRenderer = Objects.requireNonNull(mustacheRenderer);
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  @Override
  public void handleRequest(HttpServerExchange exchange) throws IOException {
    String uuid = pathParameter(exchange, "uuid").orElseThrow();

    boolean isJson =
        exchange.getAttachment(Route.MATCHED_ROUTE)
                .produces()
                .equals("application/json");

    ResultsView result = homeResultsReader.resultsByUuid(uuid);
    if (result == null) {
      exchange.setStatusCode(NOT_FOUND);
      return;
    }

    var detailPageView = new DetailPageView(result);

    if (isJson) {
      String json = objectMapper.writeValueAsString(detailPageView);
      exchange.getResponseSender().send(json);
    } else {
      String html = mustacheRenderer.render("detail.mustache", detailPageView);
      exchange.getResponseSender().send(html);
    }
  }
}
