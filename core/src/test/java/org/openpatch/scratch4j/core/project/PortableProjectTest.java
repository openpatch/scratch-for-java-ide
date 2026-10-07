package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.assertj.core.api.Assertions.*;

class PortableProjectTest {
  @TempDir Path tmp;

  @Test
  void workspaceImportPreservesImportsCompactMainMetadataAndBinaryAssets() throws Exception {
    Path input = tmp.resolve("game.json");
    Files.writeString(input, """
        {"name":"Game", "settings":{"language":"Java","libraries":["scratch"],
          "scratchProject":{"version":1,"lesson":"course","customSetting":{"kept":true}}},
         "modules":[
           {"id":1,"name":"assets","isFolder":true,"text":""},
           {"id":2,"name":"Main.java","text":"import java.time.Instant;\\nvoid main() { System.out.println(\\"ok\\"); }"},
           {"id":3,"parent_folder_id":1,"name":"image.png","text":"data:image/png;base64,AAECA/8="},
           {"id":4,"name":"identity.frag","text":"uniform sampler2D texture;"}]}
        """);
    Path root = PortableProject.importWorkspace(input, tmp.resolve("projects"));
    assertThat(Files.readAllBytes(root.resolve("assets/image.png"))).containsExactly(0, 1, 2, 3, (byte)255);
    assertThat(root.resolve("Main.java")).content().contains("import java.time.Instant;", "void main()", "import org.openpatch.scratch.*;");
    assertThat(root.resolve("identity.frag")).hasContent("uniform sampler2D texture;");
    ScratchProject project = ScratchProject.open(root);
    assertThat(project.startStage()).isEqualTo("Main");
    assertThat(project.settings().lesson).isEqualTo("course");
    project.settings().save(root);
    assertThat(root.resolve(".scratch4j/project.json")).content().contains("customSetting", "kept");
  }

  @Test
  void invalidAssetsAndPathsNeverPublishPartialProjects() throws Exception {
    for (String name : new String[] { "../escape.java", "CON.java", "C:/Main.java" }) {
      Path input = tmp.resolve("invalid.json");
      Files.writeString(input, """
          {"settings":{"language":"Java","libraries":["scratch"]},"modules":[{"name":"%s","text":"class Main {}"}]}
          """.formatted(name));
      assertThatIOException().isThrownBy(() -> PortableProject.importWorkspace(input, tmp.resolve("projects")));
      assertThat(tmp.resolve("projects/invalid")).doesNotExist();
      assertThat(tmp.resolve("escape.java")).doesNotExist();
    }
  }

  @Test
  void nrwImportsKeepCourseListSelectionAndRejectUnadaptedLibraries() throws Exception {
    Path input = tmp.resolve("nrw.json");
    Files.writeString(input, """
        {"settings":{"language":"Java","libraries":["scratch","nrw"]},"modules":[{"name":"Welt.java","text":"class Welt extends Stage {}"}]}
        """);
    Path root = PortableProject.importWorkspace(input, tmp.resolve("projects"));
    assertThat(ProjectSettings.load(root).flavour).isEqualTo("nrw");
    assertThat(root.resolve("Welt.java")).content().doesNotContain("import java.util.*;");
  }
}
