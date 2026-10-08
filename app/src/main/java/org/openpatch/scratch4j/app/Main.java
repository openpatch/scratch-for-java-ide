package org.openpatch.scratch4j.app;

import org.openpatch.scratch4j.ui.StudioApp;

/** Entry point of the IDE. */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    StudioApp.prepareGraphics(); // before JavaFX picks its graphics pipeline
    javafx.application.Application.launch(StudioApp.class, args);
  }
}
