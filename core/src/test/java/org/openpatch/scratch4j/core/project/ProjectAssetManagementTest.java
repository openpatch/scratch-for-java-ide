package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectAssetManagementTest {

  @TempDir Path root;

  @Test
  void renamesJavaLiteralsAndSplashWithoutChangingComments() throws IOException {
    Path asset = asset("assets/images/cat.png");
    Path code = root.resolve("Player.java");
    Files.writeString(code, """
        class Player {
          void setup() {
            addCostume("cat", "assets/images/cat.png");
            String another = "assets/images/cat.png";
          }
        }
        """);
    ScratchProject project = ScratchProject.open(root);
    project.settings().splashLogo = "assets/images/cat.png";
    project.settings().save(root);

    Path renamed = ProjectAssetManagement.rename(project, asset, "new cat");

    assertThat(renamed).hasFileName("new cat.png");
    assertThat(asset).doesNotExist();
    assertThat(Files.readString(code)).contains("assets/images/new cat.png")
        .doesNotContain("assets/images/cat.png");
    assertThat(project.settings().splashLogo).isEqualTo("assets/images/new cat.png");
    assertThat(ProjectSettings.load(root).splashLogo).isEqualTo("assets/images/new cat.png");
  }

  @Test
  void refusesUnknownReferencesAndReferencedDelete() throws IOException {
    Path asset = asset("assets/sounds/jump.wav");
    Path code = root.resolve("Player.java");
    Files.writeString(code, "class Player { String sound = \"assets/sounds/jump.wav\"; }");
    ScratchProject project = ScratchProject.open(root);
    assertThatThrownBy(() -> ProjectAssetManagement.delete(project, asset))
        .isInstanceOf(IOException.class).hasMessageContaining("referenced");
    Files.writeString(code, "class Player { // assets/sounds/jump.wav\n }");
    assertThatThrownBy(() -> ProjectAssetManagement.rename(project, asset, "bounce"))
        .isInstanceOf(IOException.class).hasMessageContaining("Check references");
    assertThat(asset).exists();
  }

  @Test
  void deletesUnreferencedAssetRecoverablyAndRejectsPathsOutsideAssets() throws IOException {
    Path asset = asset("assets/images/unused.png");
    ScratchProject project = ScratchProject.open(root);

    Path trash = ProjectAssetManagement.delete(project, asset);

    assertThat(asset).doesNotExist();
    assertThat(trash).exists().startsWith(root.resolve(".scratch4j/trash"));
    Path source = root.resolve("Player.java");
    Files.writeString(source, "class Player {}");
    assertThatThrownBy(() -> ProjectAssetManagement.delete(project, source))
        .isInstanceOf(IOException.class);
  }

  @Test
  void flagsReferencesInOtherTextAssetsBeforeRenaming() throws IOException {
    Path asset = asset("assets/images/hero.png");
    Path shader = asset("assets/shaders/hero.frag");
    Files.writeString(shader, "// assets/images/hero.png");
    ScratchProject project = ScratchProject.open(root);

    assertThatThrownBy(() -> ProjectAssetManagement.rename(project, asset, "player"))
        .isInstanceOf(IOException.class).hasMessageContaining("hero.frag");
    assertThat(asset).exists();
  }

  private Path asset(String relative) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "data");
    return file;
  }
}
