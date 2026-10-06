package org.openpatch.scratch4j.core.compile;

import org.junit.jupiter.api.Test;
import org.openpatch.scratch4j.core.project.NewProject;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SymbolsTest {

  private static final Path STAGE = Path.of("/project/MyStage.java");
  private static final Path PLAYER = Path.of("/project/Player.java");
  private static final Path HERO = Path.of("/project/Hero.java");

  private static final String STAGE_TEXT = """
      import org.openpatch.scratch.Stage;

      public class MyStage extends Stage {
        Player player;
        int lives = 3;

        public MyStage() {
          player = new Player();
          this.add(player);
          player.jump(lives);
          Runnable r = player::land;
        }
      }
      """;

  private static final String PLAYER_TEXT = """
      import org.openpatch.scratch.Sprite;

      public class Player extends Sprite {
        public Player() {
          this.addCostume("bunny1_stand");
        }

        void jump(int height) {
          int doubled = height * 2;
          this.changeY(doubled);
        }

        void land() {
        }

        public void whenClicked() {
          jump(1);
        }
      }
      """;

  private static final String HERO_TEXT = """
      public class Hero extends Player {
        @Override
        void jump(int height) {
          super.jump(height + 1);
        }
      }
      """;

  private static Map<Path, String> sources() {
    return Map.of(STAGE, STAGE_TEXT, PLAYER, PLAYER_TEXT, HERO, HERO_TEXT);
  }

  private static List<Path> classpath() throws Exception {
    return List.of(NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class));
  }

  private static int at(String text, String marker, int delta) {
    return text.indexOf(marker) + delta;
  }

  @Test
  void usagesOfAMethodIncludeOverridesInOtherFiles() throws Exception {
    Symbols.Symbol symbol = Symbols.at(STAGE, at(STAGE_TEXT, "jump(lives)", 1), sources(),
        classpath()).orElseThrow();
    assertThat(symbol.name()).isEqualTo("jump");
    assertThat(symbol.inProject()).isTrue();
    assertThat(symbol.byFile().keySet()).containsExactlyInAnyOrder(STAGE, PLAYER, HERO);
    // declaration + whenClicked's call in Player, override + super call in Hero
    assertThat(symbol.byFile().get(PLAYER)).hasSize(2);
    assertThat(symbol.byFile().get(HERO)).hasSize(2);
    assertThat(symbol.occurrences()).filteredOn(Symbols.Occurrence::declaration).hasSize(2);
  }

  @Test
  void renameAFieldFromItsDeclaration() throws Exception {
    Map<Path, String> changed = Symbols.rename(STAGE, at(STAGE_TEXT, "Player player;", 8),
        "hero", sources(), classpath());
    assertThat(changed).containsOnlyKeys(STAGE);
    String text = changed.get(STAGE);
    assertThat(text).contains("Player hero;", "hero = new Player();", "this.add(hero);",
        "hero.jump(lives);", "hero::land");
    assertThat(text).doesNotContain("player");
  }

  @Test
  void renameAMethodChangesTheWholeOverrideChain() throws Exception {
    Map<Path, String> changed = Symbols.rename(HERO, at(HERO_TEXT, "void jump", 6), "hop",
        sources(), classpath());
    assertThat(changed).containsOnlyKeys(STAGE, PLAYER, HERO);
    assertThat(changed.get(PLAYER)).contains("void hop(int height)", "hop(1);");
    assertThat(changed.get(HERO)).contains("void hop(int height)", "super.hop(height + 1)");
    assertThat(changed.get(STAGE)).contains("player.hop(lives)");
  }

  @Test
  void renameALocalVariableAndAParameter() throws Exception {
    Map<Path, String> local = Symbols.rename(PLAYER, at(PLAYER_TEXT, "doubled)", 0), "twice",
        sources(), classpath());
    assertThat(local.get(PLAYER)).contains("int twice = height * 2;", "changeY(twice)");
    Map<Path, String> parameter = Symbols.rename(PLAYER, at(PLAYER_TEXT, "height * 2", 0),
        "amount", sources(), classpath());
    assertThat(parameter.get(PLAYER)).contains("void jump(int amount)", "amount * 2");
    // Hero's override keeps its own parameter name
    assertThat(parameter).doesNotContainKey(HERO);
  }

  @Test
  void renameRefusesLibraryMethodsAndOverridesOfThem() {
    assertThatThrownBy(() -> Symbols.rename(PLAYER, at(PLAYER_TEXT, "changeY", 1), "up",
        sources(), classpath())).hasMessageContaining("cannot be renamed");
    assertThatThrownBy(() -> Symbols.rename(PLAYER, at(PLAYER_TEXT, "whenClicked", 1),
        "clicked", sources(), classpath())).hasMessageContaining("Sprite.whenClicked");
  }

  @Test
  void renameRefusesClashes() {
    // a second field called "lives"
    assertThatThrownBy(() -> Symbols.rename(STAGE, at(STAGE_TEXT, "Player player;", 8),
        "lives", sources(), classpath())).hasMessageContaining("would break");
    // a local that would hide the parameter it is computed from
    assertThatThrownBy(() -> Symbols.rename(PLAYER, at(PLAYER_TEXT, "doubled =", 0),
        "height", sources(), classpath())).isInstanceOf(java.io.IOException.class);
  }

  @Test
  void renameNeedsCompilingCode() {
    Map<Path, String> broken = new java.util.HashMap<>(sources());
    broken.put(STAGE, STAGE_TEXT.replace("this.add(player);", "this.add(player)"));
    assertThatThrownBy(() -> Symbols.rename(STAGE, at(STAGE_TEXT, "lives = 3", 0), "hearts",
        broken, classpath())).hasMessageContaining("Fix the Java errors");
  }

  @Test
  void aClassNameIsATopLevelClass() throws Exception {
    Symbols.Symbol symbol = Symbols.at(STAGE, at(STAGE_TEXT, "new Player", 5), sources(),
        classpath()).orElseThrow();
    assertThat(symbol.topLevelClass()).isTrue();
    assertThat(symbol.name()).isEqualTo("Player");
    // field type, new, class declaration, constructor, "extends Player"
    assertThat(symbol.occurrences()).hasSize(5);
  }
}
