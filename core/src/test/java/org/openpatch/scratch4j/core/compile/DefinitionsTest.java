package org.openpatch.scratch4j.core.compile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefinitionsTest {

  @TempDir
  Path tmp;

  private static final String STAGE = """
      import org.openpatch.scratch.Stage;

      public class MyStage extends Stage {
        Player player;
        int lives = 3;

        public MyStage() {
          player = new Player();
          this.add(player);
          player.jump(lives);
        }
      }
      """;

  private static final String PLAYER = """
      import org.openpatch.scratch.Sprite;

      public class Player extends Sprite {
        public Player() {
          this.addCostume("bunny1_stand");
        }

        void jump(int height) {
          this.changeY(height);
        }
      }
      """;

  private Definitions.Target find(String text, String marker, int delta) throws Exception {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    Path stage = tmp.resolve("MyStage.java");
    Path player = tmp.resolve("Player.java");
    Files.writeString(stage, STAGE);
    Files.writeString(player, PLAYER);
    int offset = text.indexOf(marker) + delta;
    return Definitions.find(stage, text, offset, List.of(stage, player), List.of(jar))
        .orElseThrow();
  }

  @Test
  void methodInAnotherFile() throws Exception {
    Definitions.Target target = find(STAGE, "jump(lives)", 1);
    assertThat(target.location()).hasValueSatisfying(l -> {
      assertThat(l.file().getFileName().toString()).isEqualTo("Player.java");
      assertThat(l.line()).isEqualTo(8);
      assertThat(l.column()).isEqualTo(8);
    });
  }

  @Test
  void typeFieldAndConstructor() throws Exception {
    assertThat(find(STAGE, "Player player;", 2).location().orElseThrow().line()).isEqualTo(3);
    assertThat(find(STAGE, "player.jump", 2).location().orElseThrow())
        .satisfies(l -> assertThat(l.line()).isEqualTo(4));
    assertThat(find(STAGE, "new Player()", 6).location().orElseThrow())
        .satisfies(l -> {
          assertThat(l.file().getFileName().toString()).isEqualTo("Player.java");
          assertThat(l.line()).isEqualTo(4);
        });
    assertThat(find(STAGE, "(lives)", 2).location().orElseThrow().line()).isEqualTo(5);
  }

  @Test
  void libraryMethodResolvesToItsOwner() throws Exception {
    Definitions.Target target = find(STAGE, "add(player)", 1);
    assertThat(target.location()).isEmpty();
    assertThat(target.libraryType()).isEqualTo("org.openpatch.scratch.Stage");
    assertThat(target.libraryMember()).isEqualTo("add");
  }

  @Test
  void unsavedTextAndErrorsElsewhereStillResolve() throws Exception {
    String edited = STAGE.replace("this.add(player);", "this.add(player);\n    oops(;");
    assertThat(find(edited, "jump(lives)", 1).location()).isPresent();
  }
}
