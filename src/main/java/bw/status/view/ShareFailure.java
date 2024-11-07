package bw.status.view;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.errorprone.annotations.Immutable;
import java.util.Objects;
import bw.status.config.ShareConfig;

/**
 * A view of a failed attempt by a user to share a results.json file.
 *
 * @param kind The kind of failure that occurred.
 * @param message A message describing why the results could not be shared,
 *        which may be displayed directly to the user.
 */
@Immutable
public record ShareFailure(

    @JsonProperty(value = "kind", required = true)
    Kind kind,

    @JsonProperty(value = "message", required = true)
    String message) {

  @JsonCreator
  public ShareFailure {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(message);
  }

  /**
   * A kind of failure that prevents results from being shared.
   */
  public enum Kind {
    /**
     * The results cannot be shared because the share directory has reached its
     * {@linkplain ShareConfig#maxDirectorySizeInBytes() maximum size}.
     */
    SHARE_DIRECTORY_FULL,

    /**
     * The results cannot be shared because the results.json file exceeds the
     * {@linkplain ShareConfig#maxFileSizeInBytes() maximum size} for individual
     * files.
     */
    FILE_TOO_LARGE,

    /**
     * The results cannot be shared because its {@link Results#testMetadata()}
     * is {@code null} or empty.
     */
    MISSING_TEST_METADATA,

    /**
     * The results cannot be shared because the results.json file isn't a valid
     * JSON encoding of {@link Results}.
     */
    INVALID_JSON
  }
}
