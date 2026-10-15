package org.openpatch.scratch4j.core.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EditorHistoryTest {

  @TempDir Path project;

  private static EditorHistory.Steps steps(String... undo) {
    return new EditorHistory.Steps(List.of(undo).stream().map(String::getBytes).toList(),
        List.of("redo".getBytes()), "layers".getBytes());
  }

  private static List<String> text(List<byte[]> steps) {
    return steps.stream().map(String::new).toList();
  }

  @Test
  void keepsTheStepsInTheHiddenFolderWhileTheFileIsUnchanged() throws Exception {
    Path file = project.resolve("assets/images/cat.png");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "saved");

    EditorHistory.write(project, file, "image", steps("b", "a"));
    assertThat(project.resolve(".scratch4j/editor/assets/images/cat.png.history")).exists();
    var read = EditorHistory.read(project, file, "image");
    assertThat(text(read.undo())).containsExactly("b", "a");
    assertThat(text(read.redo())).containsExactly("redo");
    assertThat(new String(read.current())).isEqualTo("layers");

    // another editor's steps, or the file changed elsewhere: none
    assertThat(EditorHistory.read(project, file, "sound").undo()).isEmpty();
    Files.writeString(file, "painted elsewhere");
    assertThat(EditorHistory.read(project, file, "image")).isEqualTo(EditorHistory.Steps.NONE);
  }

  @Test
  void noStepsKeepNothing() throws Exception {
    Path file = project.resolve("a.wav");
    Files.writeString(file, "x");
    EditorHistory.write(project, file, "sound", steps("a"));
    EditorHistory.write(project, file, "sound", EditorHistory.Steps.NONE);
    assertThat(project.resolve(".scratch4j/editor/a.wav.history")).doesNotExist();
  }

  @Test
  void theOldestStepsGoWhenTheyDoNotFit() throws Exception {
    Path file = project.resolve("big.wav");
    Files.writeString(file, "x");
    byte[] step = new byte[(int) (EditorHistory.MAX_BYTES / 3)];
    EditorHistory.write(project, file, "sound",
        new EditorHistory.Steps(List.of(step, step, step, step), List.of(), null));
    assertThat(EditorHistory.read(project, file, "sound").undo()).hasSize(3);
  }

  @Test
  void followsAFileOrFolderThatMoves() throws Exception {
    Path file = project.resolve("assets/maps/level.tmx");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "<map/>");
    EditorHistory.write(project, file, "map", steps("a"));

    Path renamed = project.resolve("assets/maps/start.tmx");
    Files.move(file, renamed);
    EditorHistory.move(project, file, renamed);
    assertThat(text(EditorHistory.read(project, renamed, "map").undo())).containsExactly("a");

    Path folder = project.resolve("assets/levels");
    Files.move(project.resolve("assets/maps"), folder);
    EditorHistory.move(project, project.resolve("assets/maps"), folder);
    assertThat(text(EditorHistory.read(project, folder.resolve("start.tmx"), "map").undo()))
        .containsExactly("a");
  }

  @Test
  void aDamagedHistoryIsNone() throws Exception {
    Path file = project.resolve("a.png");
    Files.writeString(file, "x");
    Path history = project.resolve(".scratch4j/editor/a.png.history");
    Files.createDirectories(history.getParent());
    Files.writeString(history, "not a history");
    assertThat(EditorHistory.read(project, file, "image")).isEqualTo(EditorHistory.Steps.NONE);
  }
}
