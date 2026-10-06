package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.runner.LibraryJarSource;

/** Shader previews in the gutter and beside a .frag (OpenGL: xvfb-run, -Dscratch4j.gltest). */
@EnabledIfSystemProperty(named = "scratch4j.gltest", matches = "true")
class ShaderPreviewIT {

  @TempDir Path tmp;

  @Test
  void gutterThumbnailsAndPanelWithErrors() throws Exception {
    Path allJar = LibraryJarSource.allJar(StudioApp.userLibraryCache());
    Path root = BundledTemplates.create("demo-shader", tmp, "shader", allJar);
    Path sprite = root.resolve("MySprite.java");
    String source = Files.readString(sprite);
    LayoutIT.startFx();

    // the gutter: the pixelate line shows the cat pixelated (pixels = 20, 10)
    var holder = fx(() -> (javafx.scene.control.Label) ShaderPreviews.forLine(
        source.lines().toList().get(10), 10, root, sprite, () -> source, f -> { }));
    assertThat(holder).isNotNull();
    waitFor(() -> fx(() -> holder.getGraphic() instanceof javafx.scene.image.ImageView));
    assertThat(fx(() -> holder.getTooltip().getGraphic())).isNotNull();

    // in the editor: all three add lines get their thumbnail
    var code = fx(() -> {
      var c = new CodeEditor(sprite, source, org.openpatch.scratch4j.core.api.ApiIndex.load(),
          () -> root);
      var scene = new javafx.scene.Scene(c, 900, 260);
      Theme.apply(scene);
      return c;
    });
    waitFor(() -> fx(() -> {
      code.applyCss();
      code.layout();
      return code.lookupAll(".shader-preview").stream()
          .filter(n -> ((javafx.scene.control.Label) n).getGraphic()
              instanceof javafx.scene.image.ImageView).count() == 3;
    }));
    fx(() -> {
      UiSmokeIT.snapshot(code, Path.of("target/shader-gutter.png"));
      return null;
    });

    // halftone's pixelsPerRow is only set while the game runs: the preview says so
    var halftone = fx(() -> (javafx.scene.control.Label) ShaderPreviews.forLine(
        source.lines().toList().get(9), 9, root, sprite, () -> source, f -> { }));
    waitFor(() -> fx(() -> halftone.getGraphic() instanceof javafx.scene.image.ImageView));
    assertThat(fx(() -> halftone.getTooltip().getGraphic().lookupAll(".label").stream()
        .map(n -> ((javafx.scene.control.Label) n).getText()).toList()))
        .anyMatch(t -> t != null && t.contains("pixelsPerRow"));

    // the panel beside pixelate.frag: shown on MySprite, then a typo is marked
    Path frag = root.resolve("pixelate.frag");
    var editor = fx(() -> new CodeEditor(frag, Files.readString(frag),
        org.openpatch.scratch4j.core.api.ApiIndex.load(), () -> root));
    var panel = fx(() -> {
      var p = new ShaderPreviews.Panel(editor, frag, root);
      var scene = new javafx.scene.Scene(p, 320, 480);
      Theme.apply(scene);
      return p;
    });
    assertThat(fx(() -> panel.target().label())).isEqualTo("MySprite · pixelate");
    waitFor(() -> fx(() -> panel.image() != null));
    assertThat(fx(() -> {
      var image = panel.image();
      return image.getPixelReader().getArgb((int) image.getWidth() / 2,
          (int) image.getHeight() / 2) >>> 24;
    }))
        .as("shader preview contains visible pixels").isGreaterThan(0);
    fx(() -> {
      panel.applyCss();
      panel.layout();
      UiSmokeIT.snapshot(panel, Path.of("target/shader-panel.png"));
      return null;
    });
    fx(() -> {
      String text = editor.content().replace("p.y -= mod(p.y, 1.0 / pixels.y);",
          "p.y -= mod(p.y, 1.0 / pixel.y);");
      editor.area().replaceText(text);
      panel.refresh();
      return null;
    });
    waitFor(() -> fx(() -> panel.image() == null));
    assertThat(fx(panel::statusText)).contains("frag").contains("pixel");
    assertThat(fx(() -> editor.errorLineNumbers())).isNotEmpty();
    fx(() -> {
      UiSmokeIT.snapshot(panel, Path.of("target/shader-panel-error.png"));
      return null;
    });

    // values to try: blobby (time, rate, depth, resolution) on MyStage, rate from the code
    Path blobby = root.resolve("blobby.frag");
    var blobbyEditor = fx(() -> new CodeEditor(blobby, Files.readString(blobby),
        org.openpatch.scratch4j.core.api.ApiIndex.load(), () -> root));
    var valuesScene = new java.util.concurrent.atomic.AtomicReference<javafx.scene.Scene>();
    var values = fx(() -> {
      var p = new ShaderPreviews.Panel(blobbyEditor, blobby, root);
      var scroll = new javafx.scene.control.ScrollPane(p);
      scroll.setFitToWidth(true);
      var scene = new javafx.scene.Scene(scroll, 360, 760);
      Theme.apply(scene);
      valuesScene.set(scene);
      return p;
    });
    waitFor(() -> fx(() -> values.image() != null && values.field("rate") != null));
    assertThat(fx(() -> values.field("rate").getValue())).isEqualTo(1.5);
    assertThat(fx(() -> values.field("depth").getValue())).isEqualTo(1.5);
    assertThat(fx(() -> values.field("resolution[1]").getValue()))
        .isEqualTo(fx(() -> values.image().getHeight()));
    assertThat(fx(() -> values.field("time").isDisabled())).as("time animates").isTrue();
    // time runs on (not a loop of frames): new frames keep coming
    waitFor(() -> fx(() -> values.live() != null && values.live().shown() >= 5));
    assertThat(fx(() -> {
      var image = values.image();
      return image.getPixelReader().getArgb((int) image.getWidth() / 2,
          (int) image.getHeight() / 2) >>> 24;
    })).as("live frame contains visible pixels").isGreaterThan(0);
    var before = fx(values::image);
    fx(() -> {
      values.field("rate").getEditor().setText("4");
      return null;
    });
    waitFor(() -> fx(() -> values.image() != null && values.image() != before));
    assertThat(fx(() -> values.field("rate").getValue())).isEqualTo(4.0);
    // the shader file and the code stay as they are
    assertThat(fx(() -> blobbyEditor.content())).isEqualTo(Files.readString(blobby));
    assertThat(Files.readString(root.resolve("MyStage.java"))).contains("shader.set(\"rate\", 1.5);");
    fx(() -> {
      var sceneRoot = valuesScene.get().getRoot();
      sceneRoot.applyCss();
      sceneRoot.layout();
      UiSmokeIT.snapshot(sceneRoot, Path.of("target/shader-values.png"));
      return null;
    });
  }

  interface FxCall<T> {
    T call() throws Exception;
  }

  static <T> T fx(FxCall<T> call) throws Exception {
    CompletableFuture<T> f = new CompletableFuture<>();
    Platform.runLater(() -> {
      try {
        f.complete(call.call());
      } catch (Throwable t) {
        f.completeExceptionally(t);
      }
    });
    return f.get(30, TimeUnit.SECONDS);
  }

  static void waitFor(FxCall<Boolean> condition) throws Exception {
    long end = System.currentTimeMillis() + 60_000;
    while (!condition.call()) {
      assertThat(System.currentTimeMillis()).as("waited too long").isLessThan(end);
      Thread.sleep(100);
    }
  }
}
