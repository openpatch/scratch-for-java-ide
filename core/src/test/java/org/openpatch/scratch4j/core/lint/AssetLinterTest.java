package org.openpatch.scratch4j.core.lint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssetLinterTest {

  @TempDir
  Path tmp;

  private final AssetLinter linter = new AssetLinter();

  private List<AssetLinter.Finding> lint(String source) {
    try {
      Path file = tmp.resolve("Player.java");
      Files.writeString(file, source);
      return linter.lint(tmp, file, source);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void knownBuiltinNamesPass() {
    assertThat(lint("""
        public class Player extends Sprite {
          public Player() {
            this.addCostume("bunny1_stand");
            this.addAnimation("idle", "bunny1_walk%d", 2);
          }
        }
        """)).isEmpty();
    assertThat(lint("""
        public class Stage1 extends Stage {
          public Stage1() {
            this.addBackdrop("background");
            this.addSound("jingles_STEEL16");
          }
        }
        """)).isEmpty();
  }

  @Test
  void unknownBuiltinImageGetsDidYouMean() {
    var findings = lint("""
        public class Player extends Sprite {
          public Player() {
            this.addCostume("buny1_stand");
          }
        }
        """);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).message()).contains("buny1_stand");
    assertThat(findings.get(0).suggestions())
        .anySatisfy(s -> assertThat(s).containsIgnoringCase("bunny1_stand"));
    assertThat(findings.get(0).line()).isEqualTo(3);
  }

  @Test
  void unknownBuiltinSoundGetsDidYouMean() {
    var findings = lint("""
        public class Stage1 extends Stage {
          public Stage1() {
            this.addSound("jingle_STEEL16");
          }
        }
        """);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).suggestions()).isNotEmpty();
  }

  @Test
  void lastStringArgumentIsTheAssetReference() {
    // ("music", "jingles_STEEL16"): the label is fine, the reference is checked
    assertThat(lint("""
        public class Stage1 extends Stage {
          public Stage1() {
            this.addSound("music", "jingles_STEEL16");
          }
        }
        """)).isEmpty();
    assertThat(lint("""
        public class Stage1 extends Stage {
          public Stage1() {
            this.addSound("music", "jingles_STEEL16x");
          }
        }
        """)).hasSize(1);
  }

  @Test
  void missingProjectFileIsFlagged() {
    var findings = lint("""
        public class Player extends Sprite {
          public Player() {
            this.addCostume("sit", "assets/cat.png");
          }
        }
        """);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).message()).contains("not found");
  }

  @Test
  void existingProjectFilePasses() throws IOException {
    Files.createDirectories(tmp.resolve("assets"));
    Files.writeString(tmp.resolve("assets/cat.png"), "not really a png but exists");
    assertThat(lint("""
        public class Player extends Sprite {
          public Player() {
            this.addCostume("sit", "assets/cat.png");
          }
        }
        """)).isEmpty();
  }

  @Test
  void mp3AndUnsupportedFormatsAreFlagged() {
    var mp3 = lint("""
        public class Stage1 extends Stage {
          public Stage1() {
            this.addSound("music", "assets/song.mp3");
          }
        }
        """);
    assertThat(mp3).hasSize(1);
    assertThat(mp3.get(0).message()).containsIgnoringCase("MP3");

    var bmp = lint("""
        public class Player extends Sprite {
          public Player() {
            this.addCostume("sit", "assets/cat.bmp");
          }
        }
        """);
    assertThat(bmp).hasSize(1);
    assertThat(bmp.get(0).message()).contains("Unsupported format");
  }

  @Test
  void animationPatternWithMissingFramesIsFlagged() {
    var findings = lint("""
        public class Player extends AnimatedSprite {
          public Player() {
            this.addAnimation("idle", "buny1_walk%d", 2);
          }
        }
        """);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).message()).contains("buny1_walk1");
    assertThat(findings.get(0).suggestions()).isNotEmpty();
  }

  @Test
  void nonLiteralArgumentsAreIgnored() {
    assertThat(lint("""
        public class Player extends Sprite {
          public Player(String costume) {
            this.addCostume(costume);
          }
        }
        """)).isEmpty();
  }

  @Test
  void unrelatedMethodsWithTheSameArgumentCountAreIgnored() {
    assertThat(lint("""
        public class Other {
          public void go(String a, String b) {
            System.out.println(a + b);
          }
        }
        """)).isEmpty();
  }
}
