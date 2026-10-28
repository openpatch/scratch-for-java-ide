package org.openpatch.scratch4j.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A project someone handed over as one file: a project ZIP or an Online-IDE
 * workspace JSON. Project > Import project (ZIP / workspace JSON) imports it, and so
 * does starting Studio with the file as its first argument. A {@code .scratch4j} file
 * is a project ZIP under the extension the installers register, so that the Online-IDE's
 * "Open in Scratch for Java Studio" opens with a double-click.
 */
final class SharedProjectFile {

  private SharedProjectFile() {}

  /** The extension the installers associate with Studio. */
  static final String EXTENSION = ".scratch4j";

  /** True for an existing {@code .zip}, {@code .scratch4j} or {@code .json} file. */
  static boolean accepts(Path file) {
    String name = name(file);
    return Files.isRegularFile(file)
        && (isJson(file) || name.endsWith(".zip") || name.endsWith(EXTENSION));
  }

  /** True when {@code file} is a workspace JSON rather than a project ZIP. */
  static boolean isJson(Path file) {
    return name(file).endsWith(".json");
  }

  private static String name(Path file) {
    return file.getFileName().toString().toLowerCase(Locale.ROOT);
  }
}
