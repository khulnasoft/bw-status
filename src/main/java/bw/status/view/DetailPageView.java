package bw.status.view;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.errorprone.annotations.Immutable;
import java.util.Objects;
import bw.status.view.HomePageView.ResultsView;

/**
 * A view of the results detail page.
 *
 * @param result The results of a single run.
 */
@Immutable
public record DetailPageView(

    @JsonProperty(value = "result", required = true)
    ResultsView result) {

  @JsonCreator
  public DetailPageView {
    Objects.requireNonNull(result);
  }
}
