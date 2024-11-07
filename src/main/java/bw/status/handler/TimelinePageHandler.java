package bw.status.handler;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.undertow.util.StatusCodes.NOT_FOUND;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;
import static bw.status.undertow.extensions.RequestValues.pathParameter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bw.status.handler.routing.DisableCache;
import bw.status.handler.routing.Route;
import bw.status.service.FileStore;
import bw.status.service.MustacheRenderer;
import bw.status.util.ZipFiles;
import bw.status.view.Results;
import bw.status.view.TimelinePageView;
import bw.status.view.TimelinePageView.DataPointView;
import bw.status.view.TimelinePageView.FrameworkOptionView;
import bw.status.view.TimelinePageView.TestTypeOptionView;

/**
 * Handles requests for the timeline page.
 */
@Singleton
@Route(
    method = "GET",
    path = "/timeline/{framework}/{testType}",
    produces = "text/html; charset=utf-8")
@DisableCache
public final class TimelinePageHandler implements HttpHandler {
  private final FileStore fileStore;
  private final MustacheRenderer mustacheRenderer;
  private final ObjectMapper objectMapper;
  private final Logger logger = LoggerFactory.getLogger(getClass());

  @Inject
  public TimelinePageHandler(FileStore fileStore,
                             MustacheRenderer mustacheRenderer,
                             ObjectMapper objectMapper) {

    this.fileStore = Objects.requireNonNull(fileStore);
    this.mustacheRenderer = Objects.requireNonNull(mustacheRenderer);
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  @Override
  public void handleRequest(HttpServerExchange exchange) throws IOException {

    String selectedFramework =
        pathParameter(exchange, "framework").orElseThrow();

    Results.TestType selectedTestType =
        Results.TestType.deserialize(
            pathParameter(exchange, "testType").orElseThrow());

    if (selectedTestType == null) {
      exchange.setStatusCode(NOT_FOUND);
      return;
    }

    var allFrameworks = new HashSet<String>();

    Set<Results.TestType> missingTestTypes =
        EnumSet.allOf(Results.TestType.class);

    var dataPoints = new ArrayList<DataPointView>();

    try (DirectoryStream<Path> zipFiles =
             Files.newDirectoryStream(fileStore.resultsDirectory(),
                                      "*.zip")) {

      for (Path zipFile : zipFiles) {
        Results results;
        try {
          results =
              ZipFiles.readZipEntry(
                  /* zipFile= */
                  zipFile,
                  /* entryPath= */
                  "results.json",
                  /* entryReader= */
                  inputStream ->
                      objectMapper.readValue(inputStream, Results.class));

        } catch (IOException e) {
          logger.warn(
              "Ignoring results.zip file {} whose results.json file "
                  + "could not be parsed",
              zipFile, e);
          continue;
        }

        if (results == null) {
          logger.warn(
              "Ignoring results.zip file {} that did not contain a "
                  + "results.json file",
              zipFile);
          continue;
        }

        if (results.startTime() == null)
          // We could try to read the timestamp from somewhere else, but it's
          // not worth the added complexity.
          continue;

        allFrameworks.addAll(results.frameworks());

        missingTestTypes.removeIf(
            testType ->
                results.rps(
                    /* testType= */ testType,
                    /* framework= */ selectedFramework)
                != 0);

        double rps =
            results.rps(
                /* testType= */ selectedTestType,
                /* framework= */ selectedFramework);

        if (rps != 0) {
          dataPoints.add(
              new DataPointView(
                  /* time= */ results.startTime(),
                  /* rps= */ rps));
        }
      }
    }

    if (!allFrameworks.contains(selectedFramework)) {
      exchange.setStatusCode(NOT_FOUND);
      return;
    }

    dataPoints.sort(comparing(dataPoint -> dataPoint.time()));

    ImmutableList<TestTypeOptionView> testTypeOptions =
        Arrays
            .stream(Results.TestType.values())
            .sorted()
            .map(
                testType ->
                    new TestTypeOptionView(
                        /* testType= */ testType.serialize(),
                        /* isPresent= */ !missingTestTypes.contains(testType),
                        /* isSelected= */ testType == selectedTestType))
            .collect(toImmutableList());

    ImmutableList<FrameworkOptionView> frameworkOptions =
        allFrameworks
            .stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .map(
                framework ->
                    new FrameworkOptionView(
                        /* framework= */ framework,
                        /* isSelected= */ framework.equals(selectedFramework)))
            .collect(toImmutableList());

    var timelinePageView =
        new TimelinePageView(
            /* framework= */ selectedFramework,
            /* testType= */ selectedTestType.serialize(),
            /* dataPoints= */ ImmutableList.copyOf(dataPoints),
            /* testTypeOptions= */ testTypeOptions,
            /* frameworkOptions= */ frameworkOptions);

    String html = mustacheRenderer.render("timeline.mustache", timelinePageView);
    exchange.getResponseSender().send(html, UTF_8);
  }
}
