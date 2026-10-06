package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.region.StageDocument;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class Sb3ImporterTest {

  @TempDir
  Path tmp;

  private static byte[] png(int w, int h) throws Exception {
    var image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  private Path sb3() throws Exception {
    String json = """
        {"targets": [
          {"isStage": true, "name": "Stage", "currentCostume": 0,
           "variables": {"v1": ["score", 0]}, "lists": {}, "blocks": {},
           "costumes": [{"name": "backdrop1", "md5ext": "bg.png", "dataFormat": "png",
             "bitmapResolution": 2, "rotationCenterX": 240, "rotationCenterY": 180}],
           "sounds": [{"name": "pop", "md5ext": "pop.wav", "dataFormat": "wav"}]},
          {"isStage": false, "name": "Cat 2", "layerOrder": 2, "x": -120, "y": 40.5,
           "direction": 75, "size": 150, "visible": false, "rotationStyle": "left-right",
           "currentCostume": 1, "variables": {}, "lists": {},
           "blocks": {"a": {"topLevel": true}, "b": {"topLevel": false}},
           "costumes": [
             {"name": "cat-a", "md5ext": "cat.svg", "dataFormat": "svg",
              "rotationCenterX": 48, "rotationCenterY": 50},
             {"name": "cat \\"b\\"", "md5ext": "catb.png", "dataFormat": "png",
              "bitmapResolution": 2, "rotationCenterX": 10, "rotationCenterY": 20}],
           "sounds": [{"name": "Meow", "md5ext": "meow.mp3", "dataFormat": "mp3"}]},
          {"isStage": false, "name": "class", "layerOrder": 1, "x": 0, "y": 0,
           "direction": 90, "size": 100, "visible": true, "rotationStyle": "all around",
           "currentCostume": 0, "variables": {}, "lists": {}, "blocks": {},
           "costumes": [{"name": "ball", "md5ext": "ball.png", "dataFormat": "png",
             "bitmapResolution": 1, "rotationCenterX": 5, "rotationCenterY": 5}],
           "sounds": []}
        ]}
        """;
    Path file = tmp.resolve("game.sb3");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
      put(zip, "project.json", json.getBytes(StandardCharsets.UTF_8));
      put(zip, "bg.png", png(960, 720));
      put(zip, "pop.wav", new byte[] {1, 2, 3});
      put(zip, "cat.svg", ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"96\" height=\"100\">"
          + "<circle cx=\"48\" cy=\"50\" r=\"40\" fill=\"#ff8c1a\"/></svg>")
          .getBytes(StandardCharsets.UTF_8));
      put(zip, "catb.png", png(40, 40));
      put(zip, "meow.mp3", new byte[] {9});
      put(zip, "ball.png", png(10, 10));
    }
    return file;
  }

  private static void put(ZipOutputStream zip, String name, byte[] data) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(data);
    zip.closeEntry();
  }

  @Test
  void importsSpritesBackdropsSoundsAndPlacementsAsDesignerRegions() throws Exception {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    var result = Sb3Importer.importProject(sb3(), tmp, "game", jar, mp3 -> {
      Path wav = mp3.resolveSibling(mp3.getFileName().toString().replace(".mp3", ".wav"));
      try {
        Files.write(wav, new byte[] {4});
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
      return wav;
    });
    Path root = result.root();
    // layer order: "class" (1) before "Cat 2" (2); names become Java identifiers
    assertThat(result.spriteClasses()).containsExactly("ClassSprite", "Cat2");
    assertThat(root.resolve("assets/images/Stage-backdrop1.png")).isRegularFile();
    assertThat(root.resolve("assets/images/Cat2-cat-a.png")).isRegularFile();
    assertThat(root.resolve("assets/images/svg/Cat2-cat-a.svg")).isRegularFile();
    var placeholder = javax.imageio.ImageIO.read(root.resolve("assets/images/Cat2-cat-a.png")
        .toFile());
    // the vector costume is drawn at 2x, like Scratch's bitmaps
    assertThat(placeholder.getWidth()).isEqualTo(192);
    assertThat(placeholder.getRGB(96, 100)).as("the orange circle is drawn").isEqualTo(0xffff8c1a);
    assertThat(root.resolve("assets/sounds/Cat2-Meow.wav")).isRegularFile();
    assertThat(root.resolve("assets/sounds/Cat2-Meow.mp3")).doesNotExist();

    String cat = Files.readString(root.resolve("Cat2.java"));
    assertThat(cat).contains("public class Cat2 extends Sprite")
        .contains("this.addCostume(\"cat-a\", \"assets/images/Cat2-cat-a.png\");")
        .contains("this.addCostume(\"cat 'b'\", \"assets/images/Cat2-cat_b.png\");")
        .contains("this.setRotationCenter(96, 100);")
        .contains("this.addSound(\"Meow\", \"assets/sounds/Cat2-Meow.wav\");")
        .contains("look for \"TODO Scratch\"");

    String stage = Files.readString(root.resolve("MyStage.java"));
    var model = StageDocument.read(stage).model();
    assertThat(model.backdrops()).hasSize(1);
    assertThat(model.sounds()).hasSize(1);
    var catRef = model.sprites().byName("cat2");
    assertThat(catRef.x()).isEqualTo(-120);
    assertThat(catRef.y()).isEqualTo(40.5);
    assertThat(catRef.direction()).isEqualTo(75);
    // the current costume is a 2x bitmap: Scratch's 150 % is 75 % of its pixels
    assertThat(catRef.size()).isEqualTo(75);
    assertThat(catRef.isVisible()).isFalse();
    assertThat(catRef.currentCostume()).isEqualTo("cat 'b'");
    assertThat(catRef.rotationStyle()).endsWith("LEFT_RIGHT");
    assertThat(stage).contains("static double score = 0;");
    assertThat(ScratchProject.open(root).startStage()).isEqualTo("MyStage");
    assertThat(result.notes()).noneMatch(n -> n.contains("could not be drawn"));

    var compiled = new CompilerService().compile(ScratchProject.open(root).javaSources(),
        List.of(jar), tmp.resolve("out"));
    assertThat(compiled.errors()).isEmpty();
  }
}
