package bw.status.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.inject.Provider;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import bw.status.config.ApplicationConfig;
import bw.status.config.AssetsConfig;
import bw.status.config.EmailConfig;
import bw.status.config.FileStoreConfig;
import bw.status.config.HealthCheckConfig;
import bw.status.config.HttpServerConfig;
import bw.status.config.MustacheConfig;
import bw.status.config.RunCompleteMailerConfig;
import bw.status.config.RunProgressMonitorConfig;
import bw.status.config.ShareConfig;
import bw.status.testlib.TestServicesInjector;

/**
 * Tests for {@link ApplicationConfigFactory}.
 */
@ExtendWith(TestServicesInjector.class)
public final class ApplicationConfigFactoryTest {
  /**
   * Verifies that all configuration objects are available for injection.
   */
  @Test
  public void testAllConfigsInjectable(
      Provider<ApplicationConfig> applicationConfigProvider,
      Provider<HttpServerConfig> httpServerConfigProvider,
      Provider<AssetsConfig> assetsConfigProvider,
      Provider<MustacheConfig> mustacheConfigProvider,
      Provider<FileStoreConfig> fileStoreConfigProvider,
      Provider<RunProgressMonitorConfig> runProgressMonitorConfigProvider,
      Provider<RunCompleteMailerConfig> runCompleteMailerConfigProvider,
      Provider<ShareConfig> shareConfigProvider,
      Provider<HealthCheckConfig> healthCheckConfigProvider,
      Provider<EmailConfig> emailConfigProvider) {

    ApplicationConfig config = applicationConfigProvider.get();
    assertNotNull(config);

    assertEquals(
        config.http(),
        httpServerConfigProvider.get());

    assertEquals(
        config.assets(),
        assetsConfigProvider.get());

    assertEquals(
        config.mustache(),
        mustacheConfigProvider.get());

    assertEquals(
        config.fileStore(),
        fileStoreConfigProvider.get());

    assertEquals(
        config.runProgressMonitor(),
        runProgressMonitorConfigProvider.get());

    assertEquals(
        config.runCompleteMailer(),
        runCompleteMailerConfigProvider.get());

    assertEquals(
        config.share(),
        shareConfigProvider.get());

    assertEquals(
        config.healthCheck(),
        healthCheckConfigProvider.get());

    assertEquals(
        config.email(),
        emailConfigProvider.get());
  }

  /**
   * Verifies that the default configuration is used when no configuration file
   * is specified.
   */
  @Test
  public void testNoConfigFile(ApplicationConfigFactory configFactory)
      throws IOException {

    assertEquals(
        ApplicationConfig.defaultConfig(),
        configFactory.readConfigFile(null));
  }

  /**
   * Verifies that the default configuration is used when an empty configuration
   * file is specified.
   */
  @Test
  public void testEmptyConfigFile(ApplicationConfigFactory configFactory,
                                  FileSystem fileSystem)
      throws IOException {

    Path file = fileSystem.getPath("empty_config.yml");

    Files.write(
        file,
        List.of(
            "# This is a comment line.",
            "# This is another comment line."));

    assertEquals(
        ApplicationConfig.defaultConfig(),
        configFactory.readConfigFile(file.toString()));
  }

  /**
   * Verifies that an exception is thrown when a configuration file is specified
   * but that file does not exist.
   */
  @Test
  public void testMissingConfigFile(ApplicationConfigFactory configFactory) {
    assertThrows(
        IOException.class,
        () -> configFactory.readConfigFile("missing_file.yml"));
  }
}
