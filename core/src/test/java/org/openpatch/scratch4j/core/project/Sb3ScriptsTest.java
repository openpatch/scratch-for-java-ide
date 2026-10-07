package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.compile.CompilerService;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class Sb3ScriptsTest {

  @TempDir
  Path tmp;

  /** A cat that moves every half second, scores with space, jumps, and a coin. */
  private static final String PROJECT = """
      {"targets": [
        {"isStage": true, "name": "Stage", "currentCostume": 0,
         "variables": {"vScore": ["score", 0], "vName": ["player name", "Ada"]},
         "lists": {"lItems": ["items", ["apple", 3]]},
         "costumes": [{"name": "bg", "md5ext": "bg.png", "dataFormat": "png",
           "bitmapResolution": 1, "rotationCenterX": 0, "rotationCenterY": 0}],
         "sounds": [],
         "blocks": {
           "s1": {"opcode": "event_whenflagclicked", "next": "s2", "topLevel": true,
                  "inputs": {}, "fields": {}},
           "s2": {"opcode": "data_addtolist", "next": "s3", "inputs": {"ITEM": [1, [10, "pear"]]},
                  "fields": {"LIST": ["items", "lItems"]}},
           "s3": {"opcode": "data_setvariableto", "next": null,
                  "inputs": {"VALUE": [1, [10, "Grace"]]},
                  "fields": {"VARIABLE": ["player name", "vName"]}}
         }},
        {"isStage": false, "name": "Cat", "layerOrder": 1, "x": 0, "y": 0, "direction": 90,
         "size": 100, "visible": true, "rotationStyle": "all around", "currentCostume": 0,
         "variables": {"vSpeed": ["speed", 10]}, "lists": {},
         "costumes": [{"name": "cat", "md5ext": "cat.png", "dataFormat": "png",
           "bitmapResolution": 1, "rotationCenterX": 5, "rotationCenterY": 5}],
         "sounds": [],
         "blocks": {
           "f1": {"opcode": "event_whenflagclicked", "next": "f2", "topLevel": true,
                  "inputs": {}, "fields": {}},
           "f2": {"opcode": "motion_gotoxy", "next": "f3",
                  "inputs": {"X": [1, [4, "-100"]], "Y": [1, [4, "20"]]}, "fields": {}},
           "f3": {"opcode": "looks_sayforsecs", "next": "f4",
                  "inputs": {"MESSAGE": [1, [10, "Hello!"]], "SECS": [1, [4, "2"]]},
                  "fields": {}},
           "f4": {"opcode": "control_forever", "next": null,
                  "inputs": {"SUBSTACK": [2, "f5"]}, "fields": {}},
           "f5": {"opcode": "motion_movesteps", "next": "f6",
                  "inputs": {"STEPS": [3, "f9", [4, "10"]]}, "fields": {}},
           "f9": {"opcode": "data_variable", "next": null, "inputs": {},
                  "fields": {"VARIABLE": ["speed", "vSpeed"]}},
           "f6": {"opcode": "control_if", "next": "f8",
                  "inputs": {"CONDITION": [2, "f7"], "SUBSTACK": [2, "f10"]}, "fields": {}},
           "f7": {"opcode": "sensing_touchingobject", "next": null,
                  "inputs": {"TOUCHINGOBJECTMENU": [1, "f7m"]}, "fields": {}},
           "f7m": {"opcode": "sensing_touchingobjectmenu", "shadow": true, "next": null,
                   "inputs": {}, "fields": {"TOUCHINGOBJECTMENU": ["Coin", null]}},
           "f10": {"opcode": "data_changevariableby", "next": "f11",
                   "inputs": {"VALUE": [1, [4, "1"]]}, "fields": {"VARIABLE": ["score", "vScore"]}},
           "f11": {"opcode": "motion_turnright", "next": null,
                   "inputs": {"DEGREES": [1, [4, "15"]]}, "fields": {}},
           "f8": {"opcode": "control_wait", "next": null,
                  "inputs": {"DURATION": [1, [5, "0.5"]]}, "fields": {}},

           "g1": {"opcode": "event_whenflagclicked", "next": "g2", "topLevel": true,
                  "inputs": {}, "fields": {}},
           "g2": {"opcode": "control_stop", "next": null, "inputs": {},
                  "fields": {"STOP_OPTION": ["this script", null]}},

           "k1": {"opcode": "event_whenkeypressed", "next": "k2", "topLevel": true,
                  "inputs": {}, "fields": {"KEY_OPTION": ["space", null]}},
           "k2": {"opcode": "looks_say", "next": "k4",
                  "inputs": {"MESSAGE": [3, "k3", [10, ""]]}, "fields": {}},
           "k3": {"opcode": "operator_join", "next": null,
                  "inputs": {"STRING1": [1, [10, "Score: "]], "STRING2": [3, "k5", [10, ""]]},
                  "fields": {}},
           "k5": {"opcode": "data_variable", "next": null, "inputs": {},
                  "fields": {"VARIABLE": ["score", "vScore"]}},
           "k4": {"opcode": "procedures_call", "next": null,
                  "inputs": {"argH": [1, [4, "30"]]}, "fields": {},
                  "mutation": {"proccode": "jump %n", "argumentids": "[\\"argH\\"]"}},

           "r1": {"opcode": "event_whenbroadcastreceived", "next": "r2", "topLevel": true,
                  "inputs": {}, "fields": {"BROADCAST_OPTION": ["game over", "b1"]}},
           "r2": {"opcode": "looks_hide", "next": "r3", "inputs": {}, "fields": {}},
           "r3": {"opcode": "control_create_clone_of", "next": null, "inputs": {}, "fields": {}},

           "p1": {"opcode": "procedures_definition", "next": "p3", "topLevel": true,
                  "inputs": {"custom_block": [1, "p2"]}, "fields": {}},
           "p2": {"opcode": "procedures_prototype", "shadow": true, "next": null, "inputs": {},
                  "fields": {}, "mutation": {"proccode": "jump %n",
                  "argumentnames": "[\\"height\\"]", "argumentids": "[\\"argH\\"]"}},
           "p3": {"opcode": "motion_changeyby", "next": null,
                  "inputs": {"DY": [3, "p4", [4, "10"]]}, "fields": {}},
           "p4": {"opcode": "argument_reporter_string_number", "next": null, "inputs": {},
                  "fields": {"VALUE": ["height", null]}}
         }},
        {"isStage": false, "name": "Coin", "layerOrder": 2, "x": 50, "y": 0, "direction": 90,
         "size": 100, "visible": true, "rotationStyle": "all around", "currentCostume": 0,
         "variables": {}, "lists": {}, "blocks": {}, "sounds": [],
         "costumes": [{"name": "coin", "md5ext": "cat.png", "dataFormat": "png",
           "bitmapResolution": 1, "rotationCenterX": 5, "rotationCenterY": 5}]}
      ]}
      """;

  private Path sb3() throws Exception {
    return sb3(PROJECT);
  }

  private Path sb3(String projectJson) throws Exception {
    Path file = tmp.resolve("game.sb3");
    var image = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", png);
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
      for (String name : List.of("project.json", "bg.png", "cat.png")) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(name.equals("project.json") ? projectJson.getBytes(StandardCharsets.UTF_8)
            : png.toByteArray());
        zip.closeEntry();
      }
    }
    return file;
  }

  @Test
  void migrationTasksPreserveBlocksArchiveAndTrackEditedLines() throws Exception {
    Path archive = sb3();
    var imported = Sb3Importer.importProject(archive, tmp, "migration", null, null);
    assertThat(Files.readAllBytes(imported.root().resolve(".scratch4j/original.sb3")))
        .isEqualTo(Files.readAllBytes(archive));
    var tasks = ScratchMigration.load(imported.root());
    var timed = tasks.stream().filter(t -> t.blockId().equals("f3")).findFirst().orElseThrow();
    assertThat(timed.opcode()).isEqualTo("looks_sayforsecs");
    assertThat(timed.originalBlock()).contains("Hello!", "SECS");
    assertThat(timed.lessonUrl()).endsWith("#timing");
    assertThat(tasks).anyMatch(t -> t.blockId().equals("r3") && t.lessonUrl().endsWith("#unsupported-blocks"));
    assertThat(tasks).anyMatch(t -> t.blockId().equals("g1") && t.lessonUrl().endsWith("#shared-state"));
    Path source = imported.root().resolve(timed.javaFile());
    String text = Files.readString(source);
    assertThat(text.lines().toList().get(timed.line() - 1)).contains(timed.markerId());
    Files.writeString(source, "\n\n" + text);
    assertThat(ScratchMigration.load(imported.root()).stream().filter(t -> t.id().equals(timed.id())).findFirst().orElseThrow().line())
        .isEqualTo(timed.line() + 2);
  }

  @Test
  void scriptsBecomeEventMethodsRunAndTimersAndCompile() throws Exception {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    var result = Sb3Importer.importProject(sb3(), tmp, "game", jar, null);
    Path root = result.root();
    String cat = Files.readString(root.resolve("Cat.java"));
    String stage = Files.readString(root.resolve("MyStage.java"));

    assertThat(cat)
        .contains("double speed = 10;")
        // two green-flag scripts: one method each, "stop this script" ends only its own
        .contains("this.whenGreenFlag1();").contains("this.whenGreenFlag2();")
        .contains("private void whenGreenFlag2() {\n    return;\n  }")
        .contains("this.setPosition(-100, 20);")
        .contains("this.say(\"Hello!\", (int) (2 * 1000)); // TODO Scratch: this block waited")
        .contains("if (this.getTimer(\"scratch1\").everyMillis((int) (0.5 * 1000))) {")
        .contains("this.move(speed);")
        .contains("if (this.isTouchingSprite(Coin.class)) {")
        .contains("MyStage.score += 1;")
        .contains("public void whenKeyPressed(KeyCode keyCode) {")
        .contains("if (keyCode == KeyCode.SPACE) {")
        .contains("this.say(\"Score: \" + ScratchValues.str(MyStage.score));")
        .contains("this.jump(30);")
        .contains("void jump(double height) {")
        .contains("this.changeY(height);").doesNotContain("ScratchValues.num(height)")
        .contains("public void whenIReceive(String message) {")
        .contains("if (message.equals(\"game over\")) {")
        .contains("// TODO Scratch: create clone of");
    assertThat(stage)
        .contains("static double score = 0;")
        .contains("static String playerName = \"Ada\";")
        .contains("static ArrayList<Object> items = new ArrayList<>(List.of(\"apple\", 3));")
        .contains("items.add(\"pear\");")
        .contains("playerName = \"Grace\";");
    assertThat(root.resolve("ScratchValues.java")).isRegularFile();
    assertThat(result.notes()).anyMatch(n -> n.startsWith("Cat: 5 script(s) converted"));

    // the designer still reads the stage, and the whole project compiles
    org.openpatch.scratch4j.core.region.StageDocument.read(stage);
    var compiled = new CompilerService().compile(ScratchProject.open(root).javaSources(),
        List.of(jar), tmp.resolve("out"));
    assertThat(compiled.errors()).isEmpty();
  }

  /** A star that clones itself every second; each clone flies off and is deleted. */
  private static final String CLONES = """
      {"targets": [
        {"isStage": true, "name": "Stage", "currentCostume": 0, "variables": {}, "lists": {},
         "costumes": [{"name": "bg", "md5ext": "bg.png", "dataFormat": "png",
           "bitmapResolution": 1, "rotationCenterX": 0, "rotationCenterY": 0}],
         "sounds": [], "blocks": {}},
        {"isStage": false, "name": "Star", "layerOrder": 1, "x": 0, "y": 0, "direction": 90,
         "size": 100, "visible": true, "rotationStyle": "all around", "currentCostume": 0,
         "variables": {}, "lists": {},
         "costumes": [{"name": "star", "md5ext": "cat.png", "dataFormat": "png",
           "bitmapResolution": 1, "rotationCenterX": 5, "rotationCenterY": 5}],
         "sounds": [],
         "blocks": {
           "f1": {"opcode": "event_whenflagclicked", "next": "f2", "topLevel": true,
                  "inputs": {}, "fields": {}},
           "f2": {"opcode": "control_forever", "next": null,
                  "inputs": {"SUBSTACK": [2, "f3"]}, "fields": {}},
           "f3": {"opcode": "control_create_clone_of", "next": "f5",
                  "inputs": {"CLONE_OPTION": [1, "f4"]}, "fields": {}},
           "f4": {"opcode": "control_create_clone_of_menu", "shadow": true, "next": null,
                  "inputs": {}, "fields": {"CLONE_OPTION": ["_myself_", null]}},
           "f5": {"opcode": "control_wait", "next": null,
                  "inputs": {"DURATION": [1, [5, "1"]]}, "fields": {}},
           "c1": {"opcode": "control_start_as_clone", "next": "c2", "topLevel": true,
                  "inputs": {}, "fields": {}},
           "c2": {"opcode": "motion_turnright", "next": "c3",
                  "inputs": {"DEGREES": [1, [4, "15"]]}, "fields": {}},
           "c3": {"opcode": "control_forever", "next": null,
                  "inputs": {"SUBSTACK": [2, "c4"]}, "fields": {}},
           "c4": {"opcode": "motion_movesteps", "next": "c5",
                  "inputs": {"STEPS": [1, [4, "5"]]}, "fields": {}},
           "c5": {"opcode": "control_if", "next": null,
                  "inputs": {"CONDITION": [2, "c6"], "SUBSTACK": [2, "c8"]}, "fields": {}},
           "c6": {"opcode": "sensing_touchingobject", "next": null,
                  "inputs": {"TOUCHINGOBJECTMENU": [1, "c7"]}, "fields": {}},
           "c7": {"opcode": "sensing_touchingobjectmenu", "shadow": true, "next": null,
                  "inputs": {}, "fields": {"TOUCHINGOBJECTMENU": ["_edge_", null]}},
           "c8": {"opcode": "control_delete_this_clone", "next": null, "inputs": {}, "fields": {}}
         }}
      ]}
      """;

  @Test
  void clonesBecomeTheLibrarysClonesWhenItHasThem() throws Exception {
    Path sb3 = sb3(CLONES);
    var result = Sb3Importer.importProject(sb3, tmp, "stars", library(true), null);
    String star = Files.readString(result.root().resolve("Star.java"));
    assertThat(star).contains("public void whenStartsAsClone() {\n    this.turnRight(15);\n  }")
        .doesNotContain("TODO Scratch");
    // run() is called for the original and every clone: each part only where Scratch ran it
    String run = star.substring(star.indexOf("public void run()"));
    assertThat(run.indexOf("if (!this.isClone()) {")).isLessThan(run.indexOf("this.clone();"));
    assertThat(run.indexOf("if (this.isClone()) {")).isLessThan(run.indexOf("this.move(5);"));
    assertThat(run).contains("this.deleteThisClone();");
  }

  @Test
  void withoutClonesInTheLibraryTheyStayTodos() throws Exception {
    var result = Sb3Importer.importProject(sb3(CLONES), tmp, "stars", library(false), null);
    String star = Files.readString(result.root().resolve("Star.java"));
    assertThat(star).contains("TODO Scratch").doesNotContain("whenStartsAsClone");
  }

  /**
   * Just enough of a library (5.7.0 and later have clones, older ones not): a
   * Sprite with or without whenStartsAsClone().
   */
  private Path library(boolean clones) throws Exception {
    Path src = Files.createDirectories(tmp.resolve("lib-src/org/openpatch/scratch"));
    Files.writeString(src.resolve("Sprite.java"), """
        package org.openpatch.scratch;
        public class Sprite {
          public void run() {}
          %s
        }
        """.formatted(clones ? "public void whenStartsAsClone() {}" : ""));
    Files.writeString(src.resolve("Stage.java"), """
        package org.openpatch.scratch;
        public class Stage {
          public void run() {}
        }
        """);
    Path classes = tmp.resolve("lib-classes");
    assertThat(new CompilerService().compile(List.of(src.resolve("Sprite.java"),
        src.resolve("Stage.java")), List.of(), classes).success()).isTrue();
    Path jar = tmp.resolve("scratch-9.9.9-all.jar");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
      for (String name : List.of("Sprite", "Stage")) {
        zip.putNextEntry(new ZipEntry("org/openpatch/scratch/" + name + ".class"));
        zip.write(Files.readAllBytes(classes.resolve("org/openpatch/scratch/" + name + ".class")));
        zip.closeEntry();
      }
    }
    return jar;
  }
}
