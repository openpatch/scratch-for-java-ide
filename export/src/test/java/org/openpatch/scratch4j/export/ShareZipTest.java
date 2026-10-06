package org.openpatch.scratch4j.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ShareZipTest {

  @TempDir
  Path tmp;

  @Test
  void sharedZipLeavesOutExportsAndBuildsAndImportsAgain() throws Exception {
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "game", null);
    Path root = tmp.resolve("game");
    Files.createDirectories(root.resolve("export"));
    Files.writeString(root.resolve("export/old.zip"), "previous export");
    Files.createDirectories(root.resolve(".scratch4j/build/check"));
    Files.writeString(root.resolve(".scratch4j/build/check/MyStage.class"), "x");
    // written into the project's own export folder, like the IDE does
    Path zip = ProjectFormats.exportShareZip(ScratchProject.open(root),
        root.resolve("export/game.zip"));
    assertThat(Zip.readEntry(zip, "game/MyStage.java")).isNotNull();
    assertThat(Zip.readEntry(zip, "game/.scratch4j/project.json")).isNotNull();
    assertThat(Zip.readEntry(zip, "game/export/old.zip")).isNull();
    assertThat(Zip.readEntry(zip, "game/export/game.zip")).isNull();
    assertThat(Zip.readEntry(zip, "game/.scratch4j/build/check/MyStage.class")).isNull();

    Path imported = ProjectFormats.importZip(zip, Files.createDirectories(tmp.resolve("other")));
    assertThat(imported).isEqualTo(tmp.resolve("other/game"));
    assertThat(ScratchProject.open(imported).firstStage()).isEqualTo("MyStage");
  }
}
