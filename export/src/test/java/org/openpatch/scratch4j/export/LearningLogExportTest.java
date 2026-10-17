package org.openpatch.scratch4j.export;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.ScratchProject;
import static org.assertj.core.api.Assertions.assertThat;

class LearningLogExportTest {
  @TempDir Path tmp;
  @ParameterizedTest @ValueSource(strings = {"share", "browser", "bluej", "vscode"})
  void everyExchangeFormatExcludesCurrentRotatedAndNestedLogs(String format) throws Exception {
    Path root = Files.createDirectories(tmp.resolve("game"));
    Files.writeString(root.resolve("MyStage.java"), "class MyStage extends org.openpatch.scratch.Stage {}");
    var project = ScratchProject.open(root);
    project.settings().researchLog = true; project.settings().hintsOnly = true; project.settings().save(root);
    for (String path : new String[]{".scratch4j/log/events.jsonl", ".scratch4j/log/events.jsonl.1", "projects/one/.scratch4j/log/events.jsonl"}) {
      Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, "private events");
    }
    Path destination = tmp.resolve(format + ".zip");
    Path output = switch (format) {
      case "share" -> ProjectFormats.exportShareZip(project, destination);
      case "browser" -> ProjectFormats.exportBrowserZip(project, destination);
      case "bluej" -> ProjectFormats.exportBlueJZip(project, destination);
      default -> ProjectFormats.exportVsCodeZip(project, destination);
    };
    try (var zip = new java.util.zip.ZipFile(output.toFile())) {
      assertThat(zip.stream().map(java.util.zip.ZipEntry::getName).toList()).noneMatch(n -> n.contains(".scratch4j/log/"));
    }
    assertThat(Zip.readEntry(output, "game/.scratch4j/project.json")).contains("\"researchLog\" : true", "\"hintsOnly\" : true");
  }
}
