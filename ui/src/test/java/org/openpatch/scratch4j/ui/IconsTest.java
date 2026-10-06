package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.feather.Feather;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every Feather icon literal in the UI sources exists (a typo would crash a view). */
class IconsTest {

  @Test
  void everyIconLiteralInTheSourcesExists() throws Exception {
    Pattern literal = Pattern.compile("\"(fth-[a-z0-9-]+)\"");
    List<String> invalid = new ArrayList<>();
    try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        Matcher m = literal.matcher(Files.readString(file));
        while (m.find()) {
          try {
            Feather.findByDescription(m.group(1));
          } catch (IllegalArgumentException e) {
            invalid.add(file.getFileName() + ": " + m.group(1));
          }
        }
      }
    }
    assertThat(invalid).isEmpty();
  }
}
