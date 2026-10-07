package org.openpatch.scratch4j.core.compile;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.regex.Pattern;
import org.openpatch.scratch4j.core.project.NewProject;

/** Browser annotations are adapted in memory; the student's original source stays intact. */
public final class TeachingTests {
  private TeachingTests() {}
  private static final Pattern CLASS_TEST = Pattern.compile("@Test\\s*(?=(?:public\\s+)?class\\b)");
  public static boolean hasTests(String source) { return Pattern.compile("@Test\\b").matcher(source).find(); }
  public static String adapt(String source) {
    var match = CLASS_TEST.matcher(source);
    if (!match.find()) return source;
    source = match.replaceAll(result -> result.group().replaceAll("[^\\r\\n]", " "));
    String imports = "import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; ";
    var pkg = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;").matcher(source);
    if (pkg.find()) return source.substring(0, pkg.end()) + " " + imports + source.substring(pkg.end());
    return imports + source;
  }
  public static Path junitJar() throws IOException {
    return NewProject.classpathJar(org.junit.platform.console.ConsoleLauncher.class);
  }
}
