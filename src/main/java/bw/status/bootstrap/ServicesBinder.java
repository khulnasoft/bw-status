package bw.status.bootstrap;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.glassfish.hk2.extras.provides.ProvidesListener;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import bw.status.handler.AboutPageHandler;
import bw.status.handler.AssetsHandler;
import bw.status.handler.DetailPageHandler;
import bw.status.handler.DownloadResultsHandler;
import bw.status.handler.HealthCheckHandler;
import bw.status.handler.HomePageHandler;
import bw.status.handler.HomeUpdatesHandler;
import bw.status.handler.LastSeenCommitHandler;
import bw.status.handler.RobotsHandler;
import bw.status.handler.ShareDownloadHandler;
import bw.status.handler.SharePageHandler;
import bw.status.handler.ShareUploadHandler;
import bw.status.handler.TimelinePageHandler;
import bw.status.handler.UnzipResultsHandler;
import bw.status.handler.UploadResultsHandler;
import bw.status.hk2.extensions.TopicsModule;
import bw.status.service.ApplicationConfigFactory;
import bw.status.service.Authenticator;
import bw.status.service.ClockFactory;
import bw.status.service.DiffGenerator;
import bw.status.service.EmailSender;
import bw.status.service.FileStore;
import bw.status.service.FileSystemFactory;
import bw.status.service.HealthChecker;
import bw.status.service.HomeResultsReader;
import bw.status.service.HttpServer;
import bw.status.service.MustacheRenderer;
import bw.status.service.ObjectMapperFactory;
import bw.status.service.RunCompleteMailer;
import bw.status.service.RunProgressMonitor;
import bw.status.service.TaskScheduler;
import bw.status.service.TickerFactory;

/**
 * Registers all of this application's service classes.
 */
public final class ServicesBinder extends AbstractBinder {
  /**
   * The path to this application's YAML configuration file, or {@code null} if
   * a default configuration should be used.
   */
  private final @Nullable String configFilePath;

  public ServicesBinder(@Nullable String configFilePath) {
    this.configFilePath = configFilePath;
  }

  @Override
  protected void configure() {
    install(new TopicsModule());

    if (configFilePath != null)
      bind(configFilePath)
          .to(String.class)
          .named(ApplicationConfigFactory.CONFIG_FILE_PATH);

    addActiveDescriptor(ProvidesListener.class);
    addActiveDescriptor(ApplicationConfigFactory.class);
    addActiveDescriptor(ObjectMapperFactory.class);
    addActiveDescriptor(ClockFactory.class);
    addActiveDescriptor(TickerFactory.class);
    addActiveDescriptor(FileSystemFactory.class);
    addActiveDescriptor(HttpServer.class);
    addActiveDescriptor(Authenticator.class);
    addActiveDescriptor(MustacheRenderer.class);
    addActiveDescriptor(HomeResultsReader.class);
    addActiveDescriptor(EmailSender.class);
    addActiveDescriptor(DiffGenerator.class);
    addActiveDescriptor(FileStore.class);
    addActiveDescriptor(RunProgressMonitor.class);
    addActiveDescriptor(RunCompleteMailer.class);
    addActiveDescriptor(TaskScheduler.class);
    addActiveDescriptor(HealthChecker.class);
    addActiveDescriptor(HomePageHandler.class);
    addActiveDescriptor(HomeUpdatesHandler.class);
    addActiveDescriptor(UploadResultsHandler.class);
    addActiveDescriptor(RobotsHandler.class);
    addActiveDescriptor(DownloadResultsHandler.class);
    addActiveDescriptor(UnzipResultsHandler.class);
    addActiveDescriptor(TimelinePageHandler.class);
    addActiveDescriptor(DetailPageHandler.class);
    addActiveDescriptor(AboutPageHandler.class);
    addActiveDescriptor(AssetsHandler.class);
    addActiveDescriptor(LastSeenCommitHandler.class);
    addActiveDescriptor(ShareUploadHandler.class);
    addActiveDescriptor(ShareDownloadHandler.class);
    addActiveDescriptor(SharePageHandler.class);
    addActiveDescriptor(HealthCheckHandler.class);
  }
}
