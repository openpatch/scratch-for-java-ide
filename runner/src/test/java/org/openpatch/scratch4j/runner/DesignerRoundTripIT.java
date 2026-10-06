package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ProjectTemplate;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.region.StageDocument;
import org.openpatch.scratch4j.core.region.StageModel;
import org.openpatch.scratch4j.core.region.SpriteRef;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Runtime check: a stage the designer model describes is
 * written through the region round-trip, runs as a real program, and the
 * sprite sits exactly where the designer put it (Scratch coordinates,
 * rotation-centre semantics). Needs a display - run under {@code xvfb-run -a}
 * with {@code -Dscratch4j.gltest=true}.
 */
@EnabledIfSystemProperty(named = "scratch4j.gltest", matches = "true")
class DesignerRoundTripIT {

  @TempDir
  Path tmp;

  @Test
  void designedPositionsMatchRuntimePositions() throws Exception {
    Path allJar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    NewProject.create(ProjectTemplate.CLASSES_FIRST, tmp, "design", allJar);
    Path root = tmp.resolve("design");

    // the designer edits the model and writes it back through the regions
    String stage = Files.readString(root.resolve("MyStage.java"), StandardCharsets.UTF_8);
    StageDocument doc = StageDocument.read(stage);
    StageModel model = doc.model();
    SpriteRef player = model.sprites().byName("player");
    player.setPosition(-120, 40);
    player.direction(90);
    player.size(80);
    stage = doc.write(model);
    // teacher code outside the regions prints the live position every frame
    stage = stage.replace("public void run() {\n  }", """
        public void run() {
          System.out.println("POS " + player.getX() + "," + player.getY());
        }""");
    Files.writeString(root.resolve("MyStage.java"), stage, StandardCharsets.UTF_8);

    List<String> positions = new CopyOnWriteArrayList<>();
    RunHandle handle = new ProjectRunner().run(ScratchProject.open(root),
        RunConfig.of("MyStage").withExitAfter(3),
        new RunListener() {
          @Override public void onStdout(String line) {
            if (line.startsWith("POS ")) {
              positions.add(line.substring(4));
            }
          }
        });
    int code = handle.exitFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(code).isZero();
    assertThat(positions).isNotEmpty();
    String last = positions.get(positions.size() - 1);
    double x = Double.parseDouble(last.split(",")[0]);
    double y = Double.parseDouble(last.split(",")[1]);
    assertThat(x).isCloseTo(-120, within(0.001));
    assertThat(y).isCloseTo(40, within(0.001));
  }
}
