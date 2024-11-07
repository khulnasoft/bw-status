package bw.status.handler;

import static io.undertow.util.StatusCodes.BAD_REQUEST;
import static java.nio.charset.StandardCharsets.UTF_8;
import static bw.status.undertow.extensions.RequestValues.queryParameterAsInt;

import com.google.common.collect.ImmutableList;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import bw.status.handler.routing.DisableCache;
import bw.status.handler.routing.Route;
import bw.status.service.FileStore;
import bw.status.service.HomeResultsReader;
import bw.status.service.MustacheRenderer;
import bw.status.view.HomePageView;
import bw.status.view.HomePageView.ResultsView;

/**
 * Handles requests for the home page.
 */
@Singleton
@Route(method = "GET", path = "/", produces = "text/html; charset=utf-8")
@DisableCache
public final class HomePageHandler implements HttpHandler {
  private final MustacheRenderer mustacheRenderer;
  private final HomeResultsReader homeResultsReader;
  private final FileStore fileStore;

  @Inject
  public HomePageHandler(MustacheRenderer mustacheRenderer,
                         HomeResultsReader homeResultsReader,
                         FileStore fileStore) {

    this.mustacheRenderer = Objects.requireNonNull(mustacheRenderer);
    this.homeResultsReader = Objects.requireNonNull(homeResultsReader);
    this.fileStore = Objects.requireNonNull(fileStore);
  }

  @Override
  public void handleRequest(HttpServerExchange exchange) throws IOException {
    ImmutableList<ResultsView> results = homeResultsReader.results();

    int skip =
        queryParameterAsInt(
            /* exchange= */ exchange,
            /* parameterName= */ "skip",
            /* valueIfAbsent= */ 0,
            /* valueIfMalformed= */ -1);

    int limit =
        queryParameterAsInt(
            /* exchange= */ exchange,
            /* parameterName= */ "limit",
            /* valueIfAbsent= */ 50,
            /* valueIfMalformed= */ -1);

    if (skip < 0 || limit < 0) {
      exchange.setStatusCode(BAD_REQUEST);
      return;
    }

    ImmutableList<ResultsView> resultsOnThisPage =
        results.subList(Math.min(results.size(), skip),
                        Math.min(results.size(), skip + limit));

    String announcement = null;

    if (Files.isRegularFile(fileStore.announcementFile())) {
      List<String> lines =
          Files.readAllLines(fileStore.announcementFile(), UTF_8);

      announcement = String.join("\n", lines).strip();

      if (announcement.isEmpty())
        announcement = null;
    }

    var homePageView =
        new HomePageView(
            /* results= */ resultsOnThisPage,
            /* skip= */ skip,
            /* limit= */ limit,
            /* next= */ skip + limit,
            /* hasNext= */ skip + limit < results.size(),
            /* announcement= */ announcement);

    String html = mustacheRenderer.render("home.mustache", homePageView);
    exchange.getResponseSender().send(html, UTF_8);
  }
}
