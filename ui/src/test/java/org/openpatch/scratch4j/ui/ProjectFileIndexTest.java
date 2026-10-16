package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectFileIndexTest {

  @TempDir
  Path root;

  @Test
  void listsTheProjectsImagesAndSoundsButNotItsBuildsAndIdeData() throws Exception {
    for (String file : List.of("assets/images/cat.png", "sprites/dog.PNG", "music/theme.mp3",
        "assets/sounds/meow.wav", "Cat.java", "maps/level1.tmx",
        ".scratch4j/trash/old.png", "target/classes/copy.png", "export/web/cat.png",
        "+libs/inside.png", ".hidden/secret.png")) {
      Path path = root.resolve(file);
      Files.createDirectories(path.getParent());
      Files.writeString(path, "");
    }
    assertThat(ProjectFileIndex.files(root, ProjectFileIndex.IMAGES))
        .containsExactly("assets/images/cat.png", "sprites/dog.PNG");
    assertThat(ProjectFileIndex.files(root, ProjectFileIndex.SOUNDS))
        .containsExactly("assets/sounds/meow.wav", "music/theme.mp3");
    assertThat(ProjectFileIndex.files(root, ProjectFileIndex.MAPS))
        .containsExactly("maps/level1.tmx");
  }

  @Test
  void aFileNameThatStartsWithTheTypedTextComesFirst() {
    List<String> paths = List.of("assets/cats/run.png", "assets/images/bat.png",
        "assets/images/cat.png", "cat-sheet/a.png");
    assertThat(CodeEditor.matching(paths, "cat")).containsExactly(
        "assets/images/cat.png", "cat-sheet/a.png", "assets/cats/run.png");
    assertThat(CodeEditor.matching(paths, "")).hasSize(4);
  }

  @Test
  void aBigProjectListsAtMostTwoHundredMatches() throws Exception {
    for (int i = 0; i < 1500; i++) {
      Path path = root.resolve("assets/tiles/set" + (i % 30) + "/tile" + i + ".png");
      Files.createDirectories(path.getParent());
      Files.writeString(path, "");
    }
    List<String> all = ProjectFileIndex.files(root, ProjectFileIndex.IMAGES);
    assertThat(all).hasSize(1500);
    assertThat(CodeEditor.matching(all, "tile1")).hasSize(200);
  }
}
