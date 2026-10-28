package org.openpatch.scratch4j.app;

import java.awt.Desktop;
import java.io.File;
import org.openpatch.scratch4j.ui.StudioApp;

/** Entry point of the IDE. */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    StudioApp.prepareGraphics(); // before JavaFX picks its graphics pipeline
    registerOpenFileHandler();
    javafx.application.Application.launch(StudioApp.class, args);
  }

  /**
   * macOS hands a double-clicked {@code .scratch4j} to the app as an event, not as an
   * argument. The handler has to be in place before launch to get the file that started Studio.
   */
  private static void registerOpenFileHandler() {
    if (!System.getProperty("os.name", "").startsWith("Mac") || !Desktop.isDesktopSupported()) return;
    Desktop desktop = Desktop.getDesktop();
    if (!desktop.isSupported(Desktop.Action.APP_OPEN_FILE)) return;
    desktop.setOpenFileHandler(event ->
        StudioApp.openFiles(event.getFiles().stream().map(File::toPath).toList()));
  }
}
