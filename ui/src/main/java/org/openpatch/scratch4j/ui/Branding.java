package org.openpatch.scratch4j.ui;

import javafx.scene.image.Image;

/** Images shared by the window and the welcome screen. */
final class Branding {

  private static final Image ICON = load("app-icon.png");
  private static final Image IDLE = load("cat-idle.gif");
  private static final Image WALK = load("cat-walk.gif");

  private Branding() {}

  static Image icon() { return ICON; }

  static Image idle() { return IDLE; }

  static Image walk() { return WALK; }

  private static Image load(String name) {
    var resource = Branding.class.getResource("branding/" + name);
    if (resource == null) {
      throw new IllegalStateException("Missing app image: " + name);
    }
    return new Image(resource.toExternalForm());
  }
}
