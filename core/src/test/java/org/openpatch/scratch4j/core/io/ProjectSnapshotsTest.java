package org.openpatch.scratch4j.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectSnapshotsTest {

  @TempDir
  Path root;

  @Test
  void keepsWorkingVersionsAndRestoresThemUndoably() throws Exception {
    Files.writeString(root.resolve("MyStage.java"), "class MyStage {\n  int speed = 5;\n}\n");
    Files.createDirectories(root.resolve("assets/maps"));
    Files.writeString(root.resolve("assets/maps/a.tmx"), "<map/>");
    Files.createDirectories(root.resolve("+libs"));
    Files.writeString(root.resolve("+libs/x.java"), "ignored");
    var worked = ProjectSnapshots.keep(root, ProjectSnapshots.Kind.RAN);
    assertThat(worked.files()).containsOnlyKeys("MyStage.java", "assets/maps/a.tmx");
    assertThat(ProjectSnapshots.keep(root, ProjectSnapshots.Kind.RAN)).as("unchanged").isNull();

    // the student breaks things and adds a class
    Files.writeString(root.resolve("MyStage.java"), "class MyStage {\n  int speed = ;\n}\n");
    Files.writeString(root.resolve("Broken.java"), "class Broken {");
    var changes = ProjectSnapshots.changes(root, worked);
    assertThat(changes).containsOnlyKeys("Broken.java", "MyStage.java");
    assertThat(LineDiff.diff(changes.get("MyStage.java")[0], changes.get("MyStage.java")[1]))
        .extracting(LineDiff.Line::kind, LineDiff.Line::text)
        .containsSubsequence(org.assertj.core.groups.Tuple.tuple('-', "  int speed = 5;"),
            org.assertj.core.groups.Tuple.tuple('+', "  int speed = ;"))
        .contains(org.assertj.core.groups.Tuple.tuple('-', "  int speed = 5;"),
            org.assertj.core.groups.Tuple.tuple('+', "  int speed = ;"),
            org.assertj.core.groups.Tuple.tuple(' ', "class MyStage {"));

    ProjectSnapshots.restore(root, ProjectSnapshots.lastWorking(root));
    assertThat(Files.readString(root.resolve("MyStage.java"))).contains("speed = 5");
    assertThat(root.resolve("Broken.java")).doesNotExist();
    // and back again: the broken state was kept before restoring
    var before = ProjectSnapshots.list(root).get(0);
    assertThat(before.kind()).isEqualTo(ProjectSnapshots.Kind.BEFORE_RESTORE);
    ProjectSnapshots.restore(root, before);
    assertThat(root.resolve("Broken.java")).exists();
  }
}
