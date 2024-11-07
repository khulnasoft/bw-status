package bw.status.testlib;

import static org.glassfish.hk2.utilities.ServiceLocatorUtilities.createAndPopulateServiceLocator;

import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.extras.provides.NoInstancesFilter;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;

/**
 * Utility methods for obtaining instances of services during tests.
 */
public final class TestServices {
  private TestServices() {
    throw new AssertionError("This class cannot be instantiated");
  }

  /**
   * Creates a new {@link ServiceLocator} instance and registers all services
   * that may be obtained in tests.
   *
   * <p>The caller of this method must ensure that the {@link
   * ServiceLocator#shutdown()} method of the returned {@link ServiceLocator}
   * instance is eventually called.
   */
  public static ServiceLocator createServiceLocator() {
    ServiceLocator locator = createAndPopulateServiceLocator();
    NoInstancesFilter.enableNoInstancesFilter(locator);
    ServiceLocatorUtilities.bind(locator, new TestServicesBinder());
    return locator;
  }
}
