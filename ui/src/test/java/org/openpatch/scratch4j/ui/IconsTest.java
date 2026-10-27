package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.IkonHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every Feather and Material Design icon literal in the UI sources exists. */
class IconsTest {

  @Test
  void everyIconLiteralInTheSourcesExists() throws Exception {
    Pattern literal = Pattern.compile("\"((?:fth|mdi2[a-z])-[a-z0-9-]+)\"");
    List<String> invalid = new ArrayList<>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        Matcher m = literal.matcher(Files.readString(file));
        while (m.find()) {
          if (!exists(m.group(1))) {
            invalid.add(file.getFileName() + ": " + m.group(1));
          }
        }
      }
    }
    assertThat(invalid).isEmpty();
  }

  /** Resolves the literal the way FontIcon does: through the installed icon packs. */
  private static boolean exists(String literal) {
    for (IkonHandler handler : ServiceLoader.load(IkonHandler.class)) {
      if (handler.supports(literal)) {
        try {
          return handler.resolve(literal) != null;
        } catch (IllegalArgumentException e) {
          return false;
        }
      }
    }
    return false;
  }
}
