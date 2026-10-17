package org.openpatch.scratch4j.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;

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
    assertThat(root.resolve(".git/objects")).isDirectory();
    assertThat(root.resolve(".scratch4j/versions")).doesNotExist();
    assertThat(worked.files()).containsOnlyKeys("MyStage.java", "assets/maps/a.tmx");
    assertThat(ProjectSnapshots.keep(root, ProjectSnapshots.Kind.RAN)).as("unchanged").isNull();

    // the student breaks things and adds a class
    Files.writeString(root.resolve("MyStage.java"), "class MyStage {\n  int speed = ;\n}\n");
    Files.writeString(root.resolve("Broken.java"), "class Broken {");
    var changes = ProjectSnapshots.changes(root, worked);
    assertThat(changes).containsOnlyKeys("Broken.java", "MyStage.java");
    assertThat(LineDiff.diff(changes.get("MyStage.java").thenText(), changes.get("MyStage.java").nowText()))
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

  @Test
  void lookingForVersionsDoesNotCreateARepository() throws Exception {
    assertThat(ProjectSnapshots.list(root)).isEmpty();
    assertThat(ProjectSnapshots.lastWorking(root)).isNull();
    assertThat(root.resolve(".git")).doesNotExist();
  }

  @Test
  void leavesTheUsersBranchIndexAndConfigurationAlone() throws Exception {
    Path stage = root.resolve("MyStage.java");
    Files.writeString(stage, "class MyStage { int speed = 5; }");
    Files.writeString(root.resolve("README.md"), "My project");
    try (Git git = Git.init().setDirectory(root.toFile()).setInitialBranch("student-work").call()) {
      git.add().addFilepattern(".").call();
      var head = git.commit().setMessage("My own commit").setSign(false)
          .setAuthor("Student", "student@example.org")
          .setCommitter("Student", "student@example.org").call().getId();
      Files.writeString(stage, "class MyStage { int speed = 6; }");
      git.add().addFilepattern("MyStage.java").call();
      byte[] index = Files.readAllBytes(root.resolve(".git/index"));
      byte[] config = Files.readAllBytes(root.resolve(".git/config"));
      String branch = git.getRepository().getFullBranch();
      Files.writeString(stage, "class MyStage { int speed = 7; }");

      var kept = ProjectSnapshots.keep(root, ProjectSnapshots.Kind.RAN);
      assertThat(kept.files()).containsOnlyKeys("MyStage.java");
      assertThat(kept.files().get("MyStage.java")).contains("speed = 7");
      Files.writeString(stage, "broken");
      ProjectSnapshots.restore(root, kept);

      assertThat(Files.readString(stage)).contains("speed = 7");
      assertThat(Files.readString(root.resolve("README.md"))).isEqualTo("My project");
      assertThat(git.getRepository().resolve(Constants.HEAD)).isEqualTo(head);
      assertThat(git.getRepository().getFullBranch()).isEqualTo(branch);
      assertThat(Files.readAllBytes(root.resolve(".git/index"))).isEqualTo(index);
      assertThat(Files.readAllBytes(root.resolve(".git/config"))).isEqualTo(config);
    }
  }

  @Test
  void doesNotUseARepositoryInAParentDirectory() throws Exception {
    try (Git parent = Git.init().setDirectory(root.toFile()).call()) {
      Path project = Files.createDirectory(root.resolve("project"));
      Files.writeString(project.resolve("MyStage.java"), "class MyStage {}");
      var kept = ProjectSnapshots.keep(project, ProjectSnapshots.Kind.RAN);
      assertThat(kept.files()).containsOnlyKeys("MyStage.java");
      assertThat(project.resolve(".git/objects")).isDirectory();
      assertThat(parent.getRepository().getRefDatabase()
          .getRefsByPrefix("refs/scratch4j/")).isEmpty();
    }
  }

  @Test
  void migratesOldVersionsWithTheirTimesAndKindsAndCanResumeAnImport() throws Exception {
    Path ran = root.resolve(".scratch4j/versions/1000-ran");
    Path before = root.resolve(".scratch4j/versions/2000-before_restore");
    Files.createDirectories(ran.resolve("assets/maps"));
    Files.writeString(ran.resolve("MyStage.java"), "class MyStage {}");
    Files.writeString(ran.resolve("assets/maps/a.tmx"), "<map/>");
    Files.createDirectories(before);
    Files.writeString(before.resolve("MyStage.java"), "broken");
    Files.writeString(root.resolve("MyStage.java"), "today");

    var versions = ProjectSnapshots.list(root);
    assertThat(versions).extracting(ProjectSnapshots.Version::time)
        .containsExactly(Instant.ofEpochMilli(2000), Instant.ofEpochMilli(1000));
    assertThat(versions).extracting(ProjectSnapshots.Version::kind)
        .containsExactly(ProjectSnapshots.Kind.BEFORE_RESTORE, ProjectSnapshots.Kind.RAN);
    assertThat(versions.getLast().files()).containsOnlyKeys("MyStage.java", "assets/maps/a.tmx");
    assertThat(ran).doesNotExist();
    assertThat(before).doesNotExist();

    // A previous import wrote the Git ref, but was interrupted before removing the old copy.
    Files.createDirectories(before);
    Files.writeString(before.resolve("MyStage.java"), "broken");
    assertThat(ProjectSnapshots.list(root)).hasSize(2);
    assertThat(before).doesNotExist();

    ProjectSnapshots.restore(root, ProjectSnapshots.lastWorking(root));
    assertThat(Files.readString(root.resolve("MyStage.java"))).isEqualTo("class MyStage {}");
    assertThat(Files.readString(root.resolve("assets/maps/a.tmx"))).isEqualTo("<map/>");
    ProjectSnapshots.restore(root, ProjectSnapshots.list(root).getFirst());
    assertThat(Files.readString(root.resolve("MyStage.java"))).isEqualTo("today");
    assertThat(root.resolve("assets/maps/a.tmx")).doesNotExist();
  }

  @Test
  void keepsFortyVersionsAndCanRestoreTheOldestAtTheLimit() throws Exception {
    Path stage = root.resolve("MyStage.java");
    for (int i = 0; i < 45; i++) {
      Files.writeString(stage, "version " + i);
      ProjectSnapshots.keep(root, ProjectSnapshots.Kind.RAN);
    }
    var versions = ProjectSnapshots.list(root);
    assertThat(versions).hasSize(40);
    var oldest = versions.getLast();
    assertThat(oldest.files().get("MyStage.java")).isEqualTo("version 5");
    Files.writeString(stage, "unsaved as a project version");
    ProjectSnapshots.restore(root, oldest);
    assertThat(Files.readString(stage)).isEqualTo("version 5");
    var before = ProjectSnapshots.list(root).getFirst();
    assertThat(before.kind()).isEqualTo(ProjectSnapshots.Kind.BEFORE_RESTORE);
    ProjectSnapshots.restore(root, before);
    assertThat(Files.readString(stage)).isEqualTo("unsaved as a project version");
    assertThat(ProjectSnapshots.list(root)).hasSize(40);
    try (Git git = Git.open(root.toFile())) {
      assertThat(git.getRepository().exactRef(oldest.refName())).isNull();
      assertThat(git.getRepository().getRefDatabase()
          .getRefsByPrefix("refs/scratch4j/versions/")).hasSize(40);
    }
  }

  @Test
  void keepsEmptyVersionsAndUnicodeFilesAndLeavesMediaAndSettingsAlone() throws Exception {
    Files.writeString(root.resolve("image.png"), "picture");
    Files.createDirectories(root.resolve(".scratch4j"));
    Files.writeString(root.resolve(".scratch4j/project.json"), "{}");
    var empty = ProjectSnapshots.keep(root, ProjectSnapshots.Kind.RAN);
    assertThat(empty.files()).isEmpty();
    assertThat(ProjectSnapshots.keep(root, ProjectSnapshots.Kind.RAN)).isNull();
    Files.createDirectories(root.resolve("Bühne"));
    Files.writeString(root.resolve("Bühne/Äpfel.java"), "// Grüße 🍎\r\n");
    Files.writeString(root.resolve("effect.frag"), "shader");
    var kept = ProjectSnapshots.keep(root, ProjectSnapshots.Kind.RAN);
    assertThat(kept.files()).containsEntry("Bühne/Äpfel.java", "// Grüße 🍎\r\n")
        .containsEntry("effect.frag", "shader").hasSize(2);
    ProjectSnapshots.restore(root, empty);
    assertThat(root.resolve("Bühne/Äpfel.java")).doesNotExist();
    assertThat(root.resolve("effect.frag")).doesNotExist();
    assertThat(Files.readString(root.resolve("image.png"))).isEqualTo("picture");
    assertThat(Files.readString(root.resolve(".scratch4j/project.json"))).isEqualTo("{}");
  }

  @Test
  void namedVersionsIncludeAllProjectChangesAndRestoreBinaryAssetsAndSettings() throws Exception {
    Files.writeString(root.resolve("MyStage.java"), "class MyStage {}");
    Files.writeString(root.resolve("README.md"), "Instructions");
    Files.createDirectories(root.resolve("assets/images"));
    byte[] picture = {0, 1, 2, (byte) 255};
    Files.write(root.resolve("assets/images/player.png"), picture);
    Files.createDirectories(root.resolve("+libs"));
    Files.write(root.resolve("+libs/custom.jar"), new byte[] {0, 3, (byte) 254});
    Files.createDirectories(root.resolve(".scratch4j"));
    Files.writeString(root.resolve(".scratch4j/project.json"), "{\"appVersion\":\"1.0\"}");
    for (String dir : java.util.List.of("target", "build", "out", "export", ".scratch4j/log",
        ".scratch4j/history", ".scratch4j/editor", ".scratch4j/trash", ".scratch4j/build")) {
      Files.createDirectories(root.resolve(dir));
      Files.writeString(root.resolve(dir).resolve("ignored.txt"), "cache");
    }

    var named = ProjectSnapshots.keep(root, "First playable");
    assertThat(named.automatic()).isFalse();
    assertThat(named.title()).isEqualTo("First playable");
    assertThat(named.scope()).isEqualTo(ProjectSnapshots.Scope.PROJECT);
    assertThat(named.contents()).containsOnlyKeys("MyStage.java", "README.md",
        "assets/images/player.png", "+libs/custom.jar", ".scratch4j/project.json");
    assertThat(named.contents().get("assets/images/player.png")).isEqualTo(picture);
    try (Git git = Git.open(root.toFile()); var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
      assertThat(git.getRepository().getFullBranch()).isEqualTo("refs/heads/main");
      assertThat(walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(named.commitId()))
          .getShortMessage()).isEqualTo("First playable");
    }

    Files.write(root.resolve("assets/images/player.png"), new byte[] {0, 8, 9});
    Files.delete(root.resolve("README.md"));
    Files.writeString(root.resolve("new.txt"), "added later");
    Files.writeString(root.resolve(".scratch4j/project.json"), "{\"appVersion\":\"2.0\"}");
    var changes = ProjectSnapshots.changes(root, named);
    assertThat(changes).containsOnlyKeys("assets/images/player.png", "README.md", "new.txt",
        ".scratch4j/project.json");
    assertThat(changes.get("assets/images/player.png").binary()).isTrue();
    ProjectSnapshots.restore(root, named);
    assertThat(Files.readAllBytes(root.resolve("assets/images/player.png"))).isEqualTo(picture);
    assertThat(Files.readString(root.resolve("README.md"))).isEqualTo("Instructions");
    assertThat(root.resolve("new.txt")).doesNotExist();
    assertThat(Files.readString(root.resolve(".scratch4j/project.json"))).contains("1.0");
    assertThat(Files.readString(root.resolve(".scratch4j/log/ignored.txt"))).isEqualTo("cache");

    var before = ProjectSnapshots.list(root).getFirst();
    assertThat(before.title()).startsWith("[AUTO]");
    ProjectSnapshots.restore(root, before);
    assertThat(Files.readAllBytes(root.resolve("assets/images/player.png"))).containsExactly(0, 8, 9);
    assertThat(root.resolve("README.md")).doesNotExist();
    assertThat(Files.readString(root.resolve("new.txt"))).isEqualTo("added later");
    assertThat(Files.readString(root.resolve(".scratch4j/project.json"))).contains("2.0");
  }

  @Test
  void namedMilestonesAreKeptWhenAutomaticVersionsRotate() throws Exception {
    Files.writeString(root.resolve("MyStage.java"), "first");
    var named = ProjectSnapshots.keep(root, "My milestone");
    var sameFiles = ProjectSnapshots.keep(root, "Ready to share");
    assertThat(sameFiles).isNotNull();
    assertThat(sameFiles.refName()).isNotEqualTo(named.refName());
    for (int i = 0; i < 45; i++) {
      Files.writeString(root.resolve("MyStage.java"), "version " + i);
      var auto = ProjectSnapshots.keep(root, ProjectSnapshots.Kind.RAN);
      assertThat(auto.title()).startsWith("[AUTO]");
    }
    var versions = ProjectSnapshots.list(root);
    assertThat(versions).hasSize(42);
    assertThat(versions.stream().filter(v -> !v.automatic()).map(ProjectSnapshots.Version::title))
        .containsExactly("Ready to share", "My milestone");
    assertThat(versions.stream().filter(ProjectSnapshots.Version::automatic)).hasSize(40);
    assertThat(named.files().get("MyStage.java")).isEqualTo("first");
    try (Git git = Git.open(root.toFile()); var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
      var automatic = ProjectSnapshots.lastWorking(root);
      assertThat(walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(automatic.commitId()))
          .getShortMessage()).startsWith("[AUTO]");
    }
  }
}
