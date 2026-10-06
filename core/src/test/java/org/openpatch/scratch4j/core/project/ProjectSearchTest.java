package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectSearchTest {

  @TempDir Path root;

  @Test
  void findsEveryLineInJavaFirstThenOtherTextFiles() throws IOException {
    Files.writeString(root.resolve("Player.java"), """
        class Player {
            void jump() { jump(); }
        }
        """);
    Files.createDirectories(root.resolve("assets/data"));
    Files.writeString(root.resolve("assets/data/notes.txt"), "Jump high\n");
    Files.createDirectories(root.resolve(".scratch4j"));
    Files.writeString(root.resolve(".scratch4j/history.txt"), "jump");
    Files.write(root.resolve("assets/data/bytes.txt"), new byte[] {(byte) 0xff, (byte) 0xfe});
    ScratchProject project = ScratchProject.open(root);

    List<ProjectSearch.Hit> hits = ProjectSearch.search(project, "jump", false);
    assertThat(hits).extracting(hit -> hit.file().getFileName().toString())
        .containsExactly("Player.java", "Player.java", "notes.txt");
    ProjectSearch.Hit second = hits.get(1);
    assertThat(second.line()).isEqualTo(2);
    assertThat(second.lineText()).isEqualTo("void jump() { jump(); }");
    assertThat(second.lineText().substring(second.from(), second.to())).isEqualTo("jump");
    assertThat(second.from()).isEqualTo(14);

    assertThat(ProjectSearch.search(project, "jump", true)).hasSize(2);
    assertThat(ProjectSearch.search(project, "", false)).isEmpty();
  }
}
