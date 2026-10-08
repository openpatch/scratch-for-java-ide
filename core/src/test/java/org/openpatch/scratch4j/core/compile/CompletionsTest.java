package org.openpatch.scratch4j.core.compile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionsTest {

  @TempDir
  Path tmp;

  private static final String PLAYER = """
      import org.openpatch.scratch.Sprite;

      public class Player extends Sprite {
        int lives = 3;
        static int highscore;

        public Player() {
          this.addCostume("bunny1_stand");
        }

        void jump(int height) {
          this.changeY(height);
        }
      }
      """;

  private Completions.Result complete(String stageText, String marker) throws Exception {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    Path stage = tmp.resolve("MyStage.java");
    Path player = tmp.resolve("Player.java");
    Files.writeString(player, PLAYER);
    Files.writeString(stage, stageText);
    String text = stageText.replace(marker, marker.replace("|", ""));
    int offset = stageText.indexOf(marker) + marker.indexOf('|');
    return Completions.complete(stage, text, offset, List.of(stage, player), List.of(jar));
  }

  private static List<String> names(Completions.Result result) {
    return result.items().stream().map(Completions.Item::name).toList();
  }

  @Test
  void membersOfAProjectSpriteIncludeInheritedLibraryMethods() throws Exception {
    var result = complete("""
        import org.openpatch.scratch.Stage;

        public class MyStage extends Stage {
          Player player = new Player();
          public void run() {
            player.ju|
          }
        }
        """, "player.ju|");
    assertThat(result.memberAccess()).isTrue();
    assertThat(result.prefix()).isEqualTo("ju");
    assertThat(names(result)).containsExactly("jump");
    assertThat(result.items().get(0).signature()).isEqualTo("jump(int height)");

    var all = complete("""
        import org.openpatch.scratch.Stage;

        public class MyStage extends Stage {
          Player player = new Player();
          public void run() {
            player.|
          }
        }
        """, "player.|");
    assertThat(names(all)).contains("jump", "lives", "move", "say", "ifOnEdgeBounce")
        .doesNotContain("MyStage", "highscore", "run2");
    // Object's plumbing comes last
    assertThat(names(all).indexOf("move")).isLessThan(names(all).indexOf("hashCode"));
  }

  @Test
  void staticMembersAfterATypeName() throws Exception {
    var result = complete("""
        import org.openpatch.scratch.*;

        public class MyStage extends Stage {
          public void run() {
            Player.|
            if (this.isKeyPressed(KeyCode.SPACE)) { }
          }
        }
        """, "Player.|");
    assertThat(names(result)).contains("highscore").doesNotContain("lives", "jump");

    var keys = complete("""
        import org.openpatch.scratch.*;

        public class MyStage extends Stage {
          public void run() {
            if (this.isKeyPressed(KeyCode.SP|)) { }
          }
        }
        """, "KeyCode.SP|");
    assertThat(names(keys)).contains("SPACE");
  }

  @Test
  void namesInScopeInsideASpriteSeeInheritedMethodsLocalsAndClasses() throws Exception {
    var result = complete("""
        import org.openpatch.scratch.Stage;

        public class MyStage extends Stage {
          int score;
          public void run() {
            int speed = 4;
            s|
          }
        }
        """, "    s|");
    // Stage's own inherited methods (setColor, stopAllSounds, ...) are in scope, Sprite's are not
    assertThat(names(result)).contains("speed", "score", "setColor", "stopAllSounds", "Sprite",
        "String").doesNotContain("say");
    assertThat(names(result).indexOf("speed")).isLessThan(names(result).indexOf("String"));
    assertThat(result.items().stream().filter(i -> i.name().equals("speed")).findFirst()
        .orElseThrow().kind()).isEqualTo(Completions.Kind.VARIABLE);
  }

  @Test
  void receiverWithMethodCallChain() throws Exception {
    var result = complete("""
        import org.openpatch.scratch.*;

        public class MyStage extends Stage {
          public void run() {
            Window.getInstance().|
          }
        }
        """, "getInstance().|");
    assertThat(names(result)).contains("setStage", "transitionToStage");
  }

  @Test
  void binaryMethodsWithoutParameterNamesShowOnlyTypes() throws Exception {
    var result = complete("""
        public class MyStage {
          public void run() {
            String value = "";
            value.subst|
          }
        }
        """, "value.subst|");
    assertThat(result.items()).extracting(Completions.Item::signature)
        .containsExactly("substring(int)", "substring(int, int)");
  }

  @Test
  void sourceParametersNamedArg0KeepTheirDeclaredName() throws Exception {
    var result = complete("""
        public class MyStage {
          void custom(int arg0) {}
          public void run() {
            this.cust|
          }
        }
        """, "this.cust|");
    assertThat(result.items()).extracting(Completions.Item::signature)
        .containsExactly("custom(int arg0)");
  }
}
