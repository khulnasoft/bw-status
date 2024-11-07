package bw.status.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.errorprone.annotations.Immutable;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;
import bw.status.service.FileStore;

/**
 * The configuration for sharing results.json files.
 *
 * @param minSecondsBetweenEmails The minimum number of seconds that must pass
 *        after sending one email before sending another.  This is used to
 *        prevent accidental self-spam if there are multiple requests to upload
 *        results when the share directory is full.
 * @param maxDirectorySizeInBytes The maximum size of the {@link
 *        FileStore#shareDirectory()} in bytes.
 * @param maxFileSizeInBytes The maximum size of a single file (before zip
 *        compression) that can be uploaded to the {@link
 *        FileStore#shareDirectory()} in bytes.
 * @param bwStatusOrigin The <a href="https://url.spec.whatwg.org/#origin"
 *        >origin</a> for this application, containing the scheme and domain but
 *        no path.  Must not end with a slash.
 * @param bwWebsiteOrigin The <a href="https://url.spec.whatwg.org/#origin"
 *        >origin</a> for the main BW website, containing the scheme and domain
 *        but no path.  Must not end with a slash.
 */
@Immutable
@Singleton
public record ShareConfig(long minSecondsBetweenEmails,
                          long maxDirectorySizeInBytes,
                          long maxFileSizeInBytes,
                          String bwStatusOrigin,
                          String bwWebsiteOrigin) {

  public ShareConfig {
    Objects.requireNonNull(bwStatusOrigin);
    Objects.requireNonNull(bwWebsiteOrigin);
  }

  @JsonCreator
  public static ShareConfig create(
      @JsonProperty(value = "minSecondsBetweenEmails", required = false)
      @Nullable Long minSecondsBetweenEmails,

      @JsonProperty(value = "maxDirectorySizeInBytes", required = false)
      @Nullable Long maxDirectorySizeInBytes,

      @JsonProperty(value = "maxFileSizeInBytes", required = false)
      @Nullable Long maxFileSizeInBytes,

      @JsonProperty(value = "bwStatusOrigin", required = false)
      @Nullable String bwStatusOrigin,

      @JsonProperty(value = "bwWebsiteOrigin", required = false)
      @Nullable String bwWebsiteOrigin) {

    return new ShareConfig(
        /* minSecondsBetweenEmails= */
        Objects.requireNonNullElse(
            minSecondsBetweenEmails,
            DEFAULT_MIN_SECONDS_BETWEEN_EMAILS),

        /* maxDirectorySizeInBytes= */
        Objects.requireNonNullElse(
            maxDirectorySizeInBytes,
            DEFAULT_MAX_DIRECTORY_SIZE_IN_BYTES),

        /* maxFileSizeInBytes= */
        Objects.requireNonNullElse(
            maxFileSizeInBytes,
            DEFAULT_MAX_FILE_SIZE_IN_BYTES),

        /* bwStatusOrigin= */
        Objects.requireNonNullElse(
            bwStatusOrigin,
            DEFAULT_BW_STATUS_ORIGIN),

        /* bwWebsiteOrigin= */
        Objects.requireNonNullElse(
            bwWebsiteOrigin,
            DEFAULT_BW_WEBSITE_ORIGIN));
  }

  public static ShareConfig defaultConfig() {
    return create(null, null, null, null, null);
  }

  private static final long DEFAULT_MIN_SECONDS_BETWEEN_EMAILS =
      Duration.ofDays(1).toSeconds();

  private static final long DEFAULT_MAX_DIRECTORY_SIZE_IN_BYTES =
      1_000_000_000; // 1GB

  private static final long DEFAULT_MAX_FILE_SIZE_IN_BYTES =
      5_000_000; // 5MB

  private static final String DEFAULT_BW_STATUS_ORIGIN =
      "https://bw-status.khulnasoft.com";

  private static final String DEFAULT_BW_WEBSITE_ORIGIN =
      "https://www.khulnasoft.com";
}
