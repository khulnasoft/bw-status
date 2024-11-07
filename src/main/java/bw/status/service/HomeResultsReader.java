package bw.status.service;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.net.UrlEscapers.urlFragmentEscaper;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;
import static java.util.Comparator.reverseOrder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.io.MoreFiles;
import com.google.errorprone.annotations.Immutable;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.glassfish.hk2.api.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bw.status.util.ZipFiles;
import bw.status.view.HomePageView.ResultsView;
import bw.status.view.HomePageView.ResultsView.Failure;
import bw.status.view.Results;

/**
 * Loads previously-uploaded results for display on the home page.
 */
@Singleton
public final class HomeResultsReader implements PreDestroy {
  private final FileStore fileStore;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final TaskScheduler taskScheduler;
  private final Logger logger = LoggerFactory.getLogger(getClass());

  // This number should be greater than the total number of results files we'll
  // ever have on disk at once.
  private static final int FILE_CACHE_MAX_SIZE = 10_000;

  @GuardedBy("this")
  private @Nullable LoadingCache<FileKey, FileSummary> fileCache;

  @GuardedBy("this")
  private @Nullable Future<?> purgeTask;

  @Inject
  public HomeResultsReader(FileStore fileStore,
                           ObjectMapper objectMapper,
                           Clock clock,
                           TaskScheduler taskScheduler) {

    this.fileStore = Objects.requireNonNull(fileStore);
    this.objectMapper = Objects.requireNonNull(objectMapper);
    this.clock = Objects.requireNonNull(clock);
    this.taskScheduler = Objects.requireNonNull(taskScheduler);
  }

  @Override
  public void preDestroy() {
    stop();
  }

  /**
   * Cleans up resources used by this service.
   */
  public synchronized void stop() {
    Future<?> task = this.purgeTask;
    if (task != null) {
      task.cancel(true);
      this.purgeTask = null;
    }
  }

  /**
   * Returns a view of all the previously-uploaded results, suitable for
   * rendering on the home page.
   *
   * @return a view of the results
   * @throws IOException if an I/O error occurs while reading the results
   */
  public ImmutableList<ResultsView> results() throws IOException {
    var noUuid = new ArrayList<FileSummary>();
    var byUuid = new HashMap<String, List<FileSummary>>();

    readAllFiles().forEach(
        (FileSummary summary) -> {
          if (summary.uuid() == null)
            noUuid.add(summary);
          else
            byUuid.computeIfAbsent(summary.uuid(), uuid -> new ArrayList<>())
                  .add(summary);
        });

    var results = new ArrayList<ResultsView>();

    for (FileSummary summary : noUuid)
      results.add(newResultsView(List.of(summary)));

    for (List<FileSummary> summaries : byUuid.values())
      results.add(newResultsView(summaries));

    return ImmutableList.sortedCopyOf(RESULTS_COMPARATOR, results);
  }

  /**
   * Returns a view of the only previously-uploaded results having the given
   * UUID, suitable for rendering on the home page, or {@code null} if there are
   * no results with the given UUID.
   *
   * @param uuid the UUID of the results to be viewed
   * @return a view of the results, or {@code null} if there are no matching
   *         results
   * @throws IOException if an I/O error occurs while reading the results
   */
  public @Nullable ResultsView resultsByUuid(String uuid) throws IOException {
    Objects.requireNonNull(uuid);

    ImmutableList<FileSummary> summaries =
        readAllFiles()
            .filter(summary -> uuid.equals(summary.uuid()))
            .collect(toImmutableList());

    return summaries.isEmpty()
        ? null
        : newResultsView(summaries);
  }

  private Stream<FileSummary> readAllFiles() throws IOException {
    Stream.Builder<FileKey> keys = Stream.builder();

    try (DirectoryStream<Path> files =
             Files.newDirectoryStream(fileStore.resultsDirectory(),
                                      "*.{json,zip}")) {

      for (Path file : files)
        keys.add(new FileKey(file));
    }

    LoadingCache<FileKey, FileSummary> cache = getFileCache();

    return keys.build()
               .map(key -> cache.get(key))
               .filter(summary -> summary != null);
  }

  private @Nullable FileSummary readFile(Path file) {
    Objects.requireNonNull(file);
    return switch (MoreFiles.getFileExtension(file)) {
      case "json" -> {
        try {
          yield readJsonFile(file);
        } catch (IOException e) {
          logger.warn("Exception reading results.json file {}", file, e);
          yield null;
        }
      }
      case "zip" -> {
        try {
          yield readZipFile(file);
        } catch (IOException e) {
          logger.warn("Exception reading results.zip file {}", file, e);
          yield null;
        }
      }
      default -> {
        logger.warn("Unknown format for results file {}", file);
        yield null;
      }
    };
  }

  @VisibleForTesting
  FileSummary readJsonFile(Path jsonFile) throws IOException {
    Objects.requireNonNull(jsonFile);

    Results results;
    try (InputStream inputStream = Files.newInputStream(jsonFile)) {
      results = objectMapper.readValue(inputStream, Results.class);
    }

    // TODO: Avoid using the last modified time of the file on disk, which may
    //       change for reasons completely unrelated to the run itself, and use
    //       something from the results.json file to give us a last modified
    //       time instead.
    FileTime lastModifiedTime  = Files.getLastModifiedTime(jsonFile);

    Path relativePath = fileStore.resultsDirectory().relativize(jsonFile);
    String fileName = Joiner.on('/').join(relativePath);

    return summarizeResults(
        /* results= */ results,
        /* fileName= */ fileName,
        /* lastUpdated= */ lastModifiedTime.toInstant(),
        /* backupCommitId= */ null,
        /* hasTestMetadataFile= */ false);
  }

  @VisibleForTesting
  @Nullable FileSummary readZipFile(Path zipFile) throws IOException {
    Objects.requireNonNull(zipFile);

    Results results =
        ZipFiles.readZipEntry(
            /* zipFile= */ zipFile,
            /* entryPath= */ "results.json",
            /* entryReader= */ inputStream ->
                                   objectMapper.readValue(inputStream,
                                                          Results.class));

    if (results == null) {
      logger.warn(
          "results.zip file {} does not contain a results.json file",
          zipFile);

      // If the zip doesn't contain a results.json at all, then we have nothing
      // useful to say to users about it, and we want to pretend it doesn't
      // exist.
      return null;
    }

    // TODO: Avoid using the last modified time of the file on disk, which may
    //       change for reasons completely unrelated to the run itself, and use
    //       something from the results.json file to give us a last modified
    //       time instead.
    FileTime lastModifiedTime = Files.getLastModifiedTime(zipFile);

    Path relativePath = fileStore.resultsDirectory().relativize(zipFile);
    String fileName = Joiner.on('/').join(relativePath);

    // If the results.json doesn't tell us the commit id, then search for a
    // commit_id.txt file.  We used to capture the git commit id in its own file
    // before we added it to results.json.
    String backupCommitId;
    if (results.git() != null)
      backupCommitId = null;
    else
      backupCommitId =
          ZipFiles.readZipEntry(
              /* zipFile= */ zipFile,
              /* entryPath= */ "commit_id.txt",
              /* entryReader= */
              inputStream -> {
                try (var isr = new InputStreamReader(inputStream, UTF_8);
                     var br = new BufferedReader(isr)) {
                  return br.readLine();
                }
              });

    // If the results.json doesn't contain the test metadata, then search for a
    // test_metadata.json file.  We used to capture the test metadata in its own
    // file before we added it to results.json.
    boolean hasTestMetadataFile;
    if (results.testMetadata() != null)
      hasTestMetadataFile = false;
    else
      hasTestMetadataFile =
          ZipFiles.readZipEntry(
              /* zipFile= */ zipFile,
              /* entryPath= */ "test_metadata.json",
              /* entryReader= */ inputStream -> "not null")
              != null;

    return summarizeResults(
        /* results= */ results,
        /* fileName= */ fileName,
        /* lastUpdated= */ lastModifiedTime.toInstant(),
        /* backupCommitId= */ backupCommitId,
        /* hasTestMetadataFile= */ hasTestMetadataFile);
  }

  private FileSummary summarizeResults(Results results,
                                       String fileName,
                                       Instant lastUpdated,
                                       @Nullable String backupCommitId,
                                       boolean hasTestMetadataFile) {

    Objects.requireNonNull(results);
    Objects.requireNonNull(fileName);
    Objects.requireNonNull(lastUpdated);

    String uuid = results.uuid();
    String name = results.name();
    String environmentDescription = results.environmentDescription();

    // The "completed" map in the results includes frameworks that won't show up
    // in the "succeeded" or "failed" maps because they had an error before they
    // could execute any of the test types.  For example, this will happen when
    // a rogue process holds onto a common port like 8080 and prevents a lot of
    // frameworks from starting up.
    int frameworksWithCleanSetup = 0;
    int frameworksWithSetupProblems = 0;

    for (String message : results.completed().values()) {
      if (isCompletedTimestamp(message))
        frameworksWithCleanSetup++;
      else
        frameworksWithSetupProblems++;
    }

    int completedFrameworks =
        frameworksWithCleanSetup + frameworksWithSetupProblems;

    int totalFrameworks = results.frameworks().size();
    int successfulTests = 0;
    int failedTests = 0;

    SetMultimap<String, Results.TestType> frameworkToFailedTestTypes =
        MultimapBuilder.hashKeys()
                       .enumSetValues(Results.TestType.class)
                       .build();

    for (Results.TestType testType : Results.TestType.values()) {
      for (String framework : results.frameworks()) {
        // Use a switch expression for exhaustiveness even though we don't need
        // the yielded value.
        int ignored = switch (results.testOutcome(testType, framework)) {
          case FAILED -> {
            failedTests++;
            frameworkToFailedTestTypes.put(framework, testType);
            yield 2;
          }

          case SUCCEEDED -> {
            successfulTests++;
            yield 1;
          }

          case NOT_IMPLEMENTED_OR_NOT_YET_TESTED -> 0; // Do nothing.
        };
      }
    }

    Instant startTime =
        (results.startTime() == null)
            ? null
            : Instant.ofEpochMilli(results.startTime());

    Instant completionTime =
        (results.completionTime() == null)
            ? null
            : Instant.ofEpochMilli(results.completionTime());

    String commitId;
    String repositoryUrl;
    String branchName;

    if (results.git() == null) {
      commitId = backupCommitId;
      repositoryUrl = null;
      branchName = null;
    } else {
      commitId = results.git().commitId();
      repositoryUrl = results.git().repositoryUrl();
      branchName = results.git().branchName();
    }

    boolean hasTestMetadata =
        results.testMetadata() != null || hasTestMetadataFile;

    var failures = new ArrayList<Failure>();

    var frameworksWithSetupIssues = new HashSet<String>();

    results.completed().forEach(
        (String framework, String message) -> {
          if (!isCompletedTimestamp(message)) {
            frameworksWithSetupIssues.add(framework);
          }
        });

    for (String framework : Sets.union(frameworkToFailedTestTypes.keySet(),
                                       frameworksWithSetupIssues)) {

      Set<Results.TestType> failedTestTypes =
          frameworkToFailedTestTypes.get(framework);

      boolean hadSetupProblems = frameworksWithSetupIssues.contains(framework);

      failures.add(
          new Failure(
              /* framework= */
              framework,

              /* failedTestTypes= */
              failedTestTypes
                  .stream()
                  .map(testType -> testType.serialize())
                  .collect(toImmutableList()),

              /* hadSetupProblems= */
              hadSetupProblems));
    }

    failures.sort(comparing(failure -> failure.framework(),
                            String.CASE_INSENSITIVE_ORDER));

    String lastCompletedFramework =
        results.frameworks()
               .asList()
               .reverse()
               .stream()
               .filter(framework -> results.completed().containsKey(framework))
               .findFirst()
               .orElse(null);

    return new FileSummary(
        /* fileName= */ fileName,
        /* uuid= */ uuid,
        /* commitId= */ commitId,
        /* repositoryUrl= */ repositoryUrl,
        /* branchName= */ branchName,
        /* name= */ name,
        /* environmentDescription= */ environmentDescription,
        /* startTime= */ startTime,
        /* completionTime= */ completionTime,
        /* lastUpdated= */ lastUpdated,
        /* completedFrameworks= */ completedFrameworks,
        /* frameworksWithCleanSetup= */ frameworksWithCleanSetup,
        /* frameworksWithSetupProblems= */ frameworksWithSetupProblems,
        /* totalFrameworks= */ totalFrameworks,
        /* successfulTests= */ successfulTests,
        /* failedTests= */ failedTests,
        /* hasTestMetadata= */ hasTestMetadata,
        /* failures= */ ImmutableList.copyOf(failures),
        /* lastCompletedFramework= */ lastCompletedFramework);
  }

  private ResultsView newResultsView(Iterable<FileSummary> summaries) {
    Objects.requireNonNull(summaries);

    FileSummary mostRecentJson = null;
    FileSummary mostRecentZip = null;

    for (FileSummary summary : summaries) {
      if (summary.fileName().endsWith(".json")) {
        if (mostRecentJson == null
            || summary.lastUpdated().isAfter(mostRecentJson.lastUpdated())) {
          mostRecentJson = summary;
        }
      } else if (summary.fileName().endsWith(".zip")) {
        if (mostRecentZip == null
            || summary.lastUpdated().isAfter(mostRecentZip.lastUpdated())) {
          mostRecentZip = summary;
        }
      }
    }

    FileSummary summary;
    // Prefer the results.zip file.  The zip file is uploaded at the end of the
    // run and should contain the complete, final results.json.
    if (mostRecentZip != null)
      summary = mostRecentZip;
    else if (mostRecentJson != null)
      summary = mostRecentJson;
    else
      throw new IllegalArgumentException(
          "There must be at least one results file");

    String uuid = summary.uuid();
    String name = summary.name();
    String environmentDescription = summary.environmentDescription();
    int frameworksWithCleanSetup = summary.frameworksWithCleanSetup();
    int frameworksWithSetupProblems = summary.frameworksWithSetupProblems();
    int successfulTests = summary.successfulTests();
    int failedTests = summary.failedTests();
    ImmutableList<Failure> failures = summary.failures();
    int completedFrameworks = summary.completedFrameworks();
    int totalFrameworks = summary.totalFrameworks();
    Instant startTime = summary.startTime();
    Instant completionTime = summary.completionTime();
    Instant lastUpdated = summary.lastUpdated();
    String commitId = summary.commitId();
    String repositoryUrl = summary.repositoryUrl();
    String branchName = summary.branchName();

    Duration elapsedDuration;
    Duration estimatedRemainingDuration;

    if (startTime == null)
      elapsedDuration = null;

    else {
      Instant endTime =
          (completionTime == null)
              // TODO: Use lastUpdated here instead of now?
              ? clock.instant()
              : completionTime;
      elapsedDuration = Duration.between(startTime, endTime);
    }

    if (completionTime != null
        || startTime == null
        || elapsedDuration == null
        || completedFrameworks == 0)
      estimatedRemainingDuration = null;

    else
      estimatedRemainingDuration =
          elapsedDuration.multipliedBy(totalFrameworks)
                         .dividedBy(completedFrameworks)
                         .minus(elapsedDuration);

    String startTimeString =
        (startTime == null)
            ? null
            : startTime.atZone(clock.getZone())
                       .toLocalDateTime()
                       .format(DISPLAYED_TIME_FORMATTER);

    String completionTimeString =
        (completionTime == null)
            ? null
            : completionTime.atZone(clock.getZone())
                            .toLocalDateTime()
                            .format(DISPLAYED_TIME_FORMATTER);

    String lastUpdatedString =
        lastUpdated.atZone(clock.getZone())
                   .toLocalDateTime()
                   .format(DISPLAYED_TIME_FORMATTER);

    // TODO: Don't display huge durations when it looks like the run is defunct.

    String elapsedDurationString =
        (elapsedDuration == null)
            ? null
            : formatDuration(elapsedDuration);

    String estimatedRemainingDurationString =
        (estimatedRemainingDuration == null)
            ? null
            : formatDuration(estimatedRemainingDuration);

    String browseRepositoryUrl;
    String browseCommitUrl;
    String browseBranchUrl;

    if (repositoryUrl == null) {
      browseRepositoryUrl = null;
      browseCommitUrl = null;
      browseBranchUrl = null;
    } else {
      Matcher githubMatcher = GITHUB_REPOSITORY_PATTERN.matcher(repositoryUrl);
      if (githubMatcher.matches()) {

        browseRepositoryUrl =
            "https://github.com" + githubMatcher.group("path");

        browseCommitUrl =
            browseRepositoryUrl + "/tree/" + commitId;

        browseBranchUrl =
            (branchName == null)
                ? null
                : browseRepositoryUrl + "/tree/" + branchName;

      } else {
        browseRepositoryUrl = null;
        browseCommitUrl = null;
        browseBranchUrl = null;
      }
    }

    String jsonFileName =
        (mostRecentJson == null)
            ? null
            : mostRecentJson.fileName();

    String zipFileName =
        (mostRecentZip == null)
            ? null
            : mostRecentZip.fileName();

    String visualizeResultsUrl;
    if (uuid != null
        && ((mostRecentJson != null && mostRecentJson.hasTestMetadata())
            || (mostRecentZip != null && mostRecentZip.hasTestMetadata())))
      visualizeResultsUrl =
          // TODO: Make this origin configurable?
          "https://www.khulnasoft.com/benchmarks/#"
              + urlFragmentEscaper().escape("section=test&runid=" + uuid);
    else
      visualizeResultsUrl = null;

    String lastCompletedFramework = summary.lastCompletedFramework();

    return new ResultsView(
        /* uuid= */ uuid,
        /* name= */ name,
        /* environmentDescription= */ environmentDescription,
        /* completedFrameworks= */ completedFrameworks,
        /* frameworksWithCleanSetup= */ frameworksWithCleanSetup,
        /* frameworksWithSetupProblems= */ frameworksWithSetupProblems,
        /* totalFrameworks= */ totalFrameworks,
        /* successfulTests= */ successfulTests,
        /* failedTests= */ failedTests,
        /* startTime= */ startTimeString,
        /* completionTime= */ completionTimeString,
        /* lastUpdated= */ lastUpdatedString,
        /* elapsedDuration= */ elapsedDurationString,
        /* estimatedRemainingDuration= */ estimatedRemainingDurationString,
        /* commitId= */ commitId,
        /* repositoryUrl= */ repositoryUrl,
        /* branchName= */ branchName,
        /* browseRepositoryUrl= */ browseRepositoryUrl,
        /* browseCommitUrl= */ browseCommitUrl,
        /* browseBranchUrl= */ browseBranchUrl,
        /* failures= */ failures,
        /* jsonFileName= */ jsonFileName,
        /* zipFileName= */ zipFileName,
        /* visualizeResultsUrl= */ visualizeResultsUrl,
        /* lastCompletedFramework= */ lastCompletedFramework);
  }

  /**
   * Returns the lazy-initialized internal cache.
   */
  private synchronized LoadingCache<FileKey, FileSummary> getFileCache() {
    LoadingCache<FileKey, FileSummary> existing = this.fileCache;
    if (existing != null)
      return existing;

    LoadingCache<FileKey, FileSummary> cache =
        Caffeine.newBuilder()
                .maximumSize(FILE_CACHE_MAX_SIZE)
                .build(key -> readFile(key.file));

    this.purgeTask =
        taskScheduler.repeat(
            /* task= */ () -> purgeUnreachableCacheKeys(cache),
            /* initialDelay= */ Duration.ofHours(1),
            /* interval= */ Duration.ofHours(1));

    this.fileCache = cache;
    return cache;
  }

  /**
   * Trims the internal cache, removing entries that are "dead" because they
   * have {@linkplain FileKey#isUnreachable() unreachable} keys.
   */
  private static void purgeUnreachableCacheKeys(
      LoadingCache<FileKey, FileSummary> cache) {

    ImmutableSet<FileKey> unreachableKeys =
        cache.asMap()
             .keySet()
             .stream()
             .filter(key -> key.isUnreachable())
             .collect(toImmutableSet());

    cache.invalidateAll(unreachableKeys);
  }

  /**
   * A cache key pointing to a results.json or results.zip file on disk.
   */
  @Immutable
  private static final class FileKey {
    final Path file;

    // When the file is modified, this cache key becomes unreachable.
    final FileTime lastModifiedTime;

    FileKey(Path file) throws IOException {
      this.file = Objects.requireNonNull(file);
      this.lastModifiedTime = Files.getLastModifiedTime(file);
    }

    @Override
    public boolean equals(@Nullable Object object) {
      return object instanceof FileKey that
          && this.file.equals(that.file)
          && this.lastModifiedTime.equals(that.lastModifiedTime);
    }

    @Override
    public int hashCode() {
      return file.hashCode() ^ lastModifiedTime.hashCode();
    }

    /**
     * Returns {@code true} if the file has been modified since this cache key
     * was created, meaning this cache key is effectively unreachable.
     */
    boolean isUnreachable() {
      try {
        return !lastModifiedTime.equals(Files.getLastModifiedTime(file));
      } catch (IOException ignored) {
        //
        // This would happen if the file is deleted, for example.
        //
        // Since an exception here implies that constructing a new key for this
        // file would also throw an exception, this key is unreachable.
        //
        return true;
      }
    }
  }

  /**
   * Information extracted from a results.json or results.zip file.
   */
  @Immutable
  @VisibleForTesting
  record FileSummary(String fileName,
                     @Nullable String uuid,
                     @Nullable String commitId,
                     @Nullable String repositoryUrl,
                     @Nullable String branchName,
                     @Nullable String name,
                     @Nullable String environmentDescription,
                     @Nullable Instant startTime,
                     @Nullable Instant completionTime,
                     Instant lastUpdated,
                     int completedFrameworks,
                     int frameworksWithCleanSetup,
                     int frameworksWithSetupProblems,
                     int totalFrameworks,
                     int successfulTests,
                     int failedTests,
                     boolean hasTestMetadata,
                     // TODO: Avoid sharing the Failure data type with
                     //       HomePageView?
                     ImmutableList<Failure> failures,
                     @Nullable String lastCompletedFramework) {

    FileSummary {
      Objects.requireNonNull(fileName);
      Objects.requireNonNull(lastUpdated);
      Objects.requireNonNull(failures);
    }
  }

  private static String formatDuration(Duration duration) {
    long seconds = duration.toSeconds();
    long minutes = seconds / 60;
    long hours = minutes / 60;
    seconds %= 60;
    minutes %= 60;

    if (minutes >= 30)
      hours++;

    if (seconds >= 30)
      minutes++;

    if (hours > 0)
      return "~"
          + NumberFormat.getIntegerInstance(Locale.ROOT).format(hours)
          + " hour"
          + ((hours == 1) ? "" : "s");

    if (minutes > 0)
      return "~"
          + NumberFormat.getIntegerInstance(Locale.ROOT).format(minutes)
          + " minute"
          + ((minutes == 1) ? "" : "s");

    return "< 1 minute";
  }

  /**
   * {@code true} if the message looks like a timestamp in the {@link
   * Results#completed()} map.
   *
   * @param message a value from the {@link Results#completed()} map
   * @return {@code true} if the value is a timestamp, indicating that the
   *         framework started and stopped correctly, or {@code false} if the
   *         message is an error message, indicating that the framework did not
   *         start or stop correctly
   */
  private static boolean isCompletedTimestamp(String message) {
    Objects.requireNonNull(message);
    try {
      LocalDateTime.parse(message, COMPLETED_TIMESTAMP_FORMATTER);
      return true;
    } catch (DateTimeParseException ignored) {
      return false;
    }
  }

  private static final DateTimeFormatter COMPLETED_TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);

  @VisibleForTesting
  static final DateTimeFormatter DISPLAYED_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' h:mm a", Locale.ROOT);

  /**
   * The ordering of results displayed on the home page.
   */
  //
  // In practice, the results files are named like this:
  //
  //   results.{uploaded_at_date_time}.{json|zip}
  //
  // where {uploaded_at_date_time} is in the format "yyyy-MM-dd-HH-mm-ss-SSS".
  //
  // Therefore, sorting by file name effectively sorts the results by when they
  // were uploaded, and this comparator puts the most recently uploaded results
  // first.
  //
  private static final Comparator<ResultsView> RESULTS_COMPARATOR =
      comparing(
          results -> {
            // The JSON file name is a better sort key because it should remain
            // constant throughout the whole run, whereas the zip file name is
            // expected to change at the end of the run (from null to non-null).
            if (results.jsonFileName() != null)
              return results.jsonFileName();

            else if (results.zipFileName() != null)
              return results.zipFileName();

            else
              return "";
          },
          reverseOrder());

  private static final Pattern GITHUB_REPOSITORY_PATTERN =
      Pattern.compile("^(https|git)://github\\.com(?<path>/.*)\\.git$");
}
