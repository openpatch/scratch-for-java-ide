package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.assets.BuiltinAssetIndex;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.ScratchProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssetCallsTest {

  @TempDir
  Path tmp;

  @Test
  void tiledPlayerAnimationsInColumns() throws Exception {
    Path root = BundledTemplates.create("demo-tiled", tmp, "tiled", null);
    String player = Files.readString(root.resolve("Player.java"));
    var animations = SpriteAssets.list(player).stream()
        .filter(e -> e.kind() == SpriteAssets.Kind.ANIMATION).toList();
    assertThat(animations).extracting(SpriteAssets.Entry::name)
        .containsExactly("walk-down", "walk-up", "walk-left", "walk-right");
    assertThat(animations.get(2)).isEqualTo(new SpriteAssets.Entry(SpriteAssets.Kind.ANIMATION,
        "walk-left", "assets/Skeleton.png", 4, true, 32, 32, 2, true));
    assertThat(AssetCalls.firstLook(player)).isEqualTo("assets/Skeleton.png#0,0,32,32");
  }

  @Test
  void overloadsFormattingAndThis() {
    String source = """
        public class Hero extends AnimatedSprite {
          public Hero() {
            addCostume("a",
                "assets/a.png", 0, 16, 16, 16);
            this.addAnimation("run", "assets/run.png", 6, 24, 24, 2);
            other.addCostume("not mine");
            this.addAnimation("blink", "eye%d.png", 3);
          }

          public void run() {
            this.addCostume("later");
          }
        }
        """;
    assertThat(AssetCalls.of(source)).extracting(AssetCalls.Call::method)
        .containsExactly("addCostume", "addAnimation", "addAnimation");
    assertThat(AssetCalls.firstLook(source)).isEqualTo("assets/a.png#0,16,16,16");
    assertThat(AssetCalls.firstLook(source.replace("addCostume(\"a\",\n"
        + "        \"assets/a.png\", 0, 16, 16, 16);", ""))).isEqualTo("assets/run.png#0,48,24,24");
  }

  @Test
  void everySpriteOfTheTutorialsAndDemosHasALook() throws Exception {
    BuiltinAssetIndex builtins = BuiltinAssetIndex.get();
    List<String> missing = new ArrayList<>();
    for (var template : BundledTemplates.list()) {
      Path root = BundledTemplates.create(template.id(), tmp, template.id(), null);
      ScratchProject project = ScratchProject.open(root);
      for (String sprite : project.spriteClasses()) {
        Path file = project.sourceOf(sprite);
        String source = file == null ? "" : Files.readString(file);
        boolean created = false;
        for (Path other : project.javaSources()) {
          String text = Files.readString(other);
          created |= text.contains("new " + sprite + "(");
        }
        boolean hasAssets = file != null
            && AssetCalls.of(source).stream().anyMatch(c -> !c.method().equals("addSound"));
        boolean inheritsAssets = source.matches("(?s).*class\\s+" + sprite
            + "\\s+extends\\s+(?!Sprite\\b|AnimatedSprite\\b|UISprite\\b)\\w+.*");
        if (!hasAssets && !inheritsAssets) {
          continue; // the stage gives this sprite its costume
        }
        if (!created && !source.contains(sprite + "()") && !inheritsAssets) {
          continue; // a base class that is never made itself (Figure)
        }
        String look = SpriteLook.of(root, sprite);
        String image = look == null ? null : look.replaceFirst("#.*$", "");
        boolean found = image != null && (Files.isRegularFile(root.resolve(image))
            || builtins.image(image).isPresent());
        if (!found) missing.add(template.id() + "/" + sprite + ": " + look);
      }
    }
    assertThat(missing).isEmpty();
    // through super(...): Knight passes its folder to Figure; Racer gets "bee" from the stage
    Path stress = tmp.resolve("demo-stressTest");
    assertThat(SpriteLook.of(stress, "Knight")).isEqualTo("assets/knight/Idle (1).png");
    assertThat(SpriteLook.of(tmp.resolve("red-light-green-light-100"), "Racer"))
        .isEqualTo("bee");
  }

  @Test
  void builtInCostumeBecomesAFileInPlace() {
    String sprite = """
        public class Cat extends Sprite {
          public Cat() {
            // scratch4j:begin setup
            this.addCostume("cat_a");
            this.addCostume("cat_b");
            // scratch4j:end setup
          }
        }
        """;
    var first = SpriteAssets.list(sprite).get(0);
    String updated = SpriteAssets.useFile(sprite, first, "assets/images/cat_a.png");
    assertThat(updated).contains("this.addCostume(\"cat_a\", \"assets/images/cat_a.png\");\n"
        + "    this.addCostume(\"cat_b\");");
  }

  @Test
  void sheetCellsAreEntriesAndReplaceInPlace() {
    String sprite = """
        public class Hero extends AnimatedSprite {
          public Hero() {
            // scratch4j:begin setup
            this.addCostume("idle", "assets/hero.png", 32, 0, 32, 32);
            this.addAnimation("walk", "assets/hero.png", 4, 32, 32, 1);
            this.addSound("jump");
            // scratch4j:end setup
          }
        }
        """;
    var entries = SpriteAssets.list(sprite);
    var idle = entries.get(0);
    assertThat(idle.isSheetCostume()).isTrue();
    assertThat(idle.cropX()).isEqualTo(32);
    assertThat(idle.tileWidth()).isEqualTo(32);
    var walk = entries.get(1);
    String updated = SpriteAssets.replace(sprite, walk, java.util.List.of(
        "this.addAnimation(\"walk\", \"assets/hero.png\", 3, 32, 32, 2, true);"));
    assertThat(updated).contains("    this.addCostume(\"idle\", \"assets/hero.png\", 32, 0, 32, 32);\n"
        + "    this.addAnimation(\"walk\", \"assets/hero.png\", 3, 32, 32, 2, true);\n"
        + "    this.addSound(\"jump\");");
    String cells = SpriteAssets.replace(sprite, idle, java.util.List.of(
        "this.addCostume(\"idle\", \"assets/hero.png\", 0, 0, 32, 32);",
        "this.addCostume(\"idle2\", \"assets/hero.png\", 32, 0, 32, 32);"));
    assertThat(SpriteAssets.list(cells)).extracting(SpriteAssets.Entry::name)
        .containsExactly("idle", "idle2", "walk", "jump");
  }
}
