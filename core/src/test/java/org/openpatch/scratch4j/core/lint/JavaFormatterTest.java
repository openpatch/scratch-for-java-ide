package org.openpatch.scratch4j.core.lint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaFormatterTest {

  @TempDir
  Path tmp;

  @Test
  void spacesBracesAndIndentationLikeTheDocs() {
    String messy = """
        public class Cat extends Sprite{
        int x=3,y = -1;
        List<String> names=new ArrayList<>();
            public void run(){
          if(this.isKeyPressed(KeyCode.SPACE)){
        this.move(x+2);x++;
          }else{
        for(int i=0;i<10;i++){ this.turnRight( -i ); }
          }
        String s="a,b=c";   // keep, this=comment
        switch(x){
        case 1:
        this.say("one");
        break;
        default:
        y=x>2?1:-1;
        }
            }
        }
        """;
    String expected = """
        public class Cat extends Sprite {
          int x = 3, y = -1;
          List<String> names = new ArrayList<>();
          public void run() {
            if (this.isKeyPressed(KeyCode.SPACE)) {
              this.move(x + 2); x++;
            } else {
              for (int i = 0; i < 10; i++) { this.turnRight(-i); }
            }
            String s = "a,b=c"; // keep, this=comment
            switch (x) {
              case 1:
                this.say("one");
                break;
              default:
                y = x > 2 ? 1 : -1;
            }
          }
        }
        """;
    assertThat(JavaFormatter.format(messy)).isEqualTo(expected);
    assertThat(JavaFormatter.format(expected)).isEqualTo(expected);
  }

  @Test
  void textBlocksCommentsAndContinuations() {
    String source = """
        class A {
          /**
               * Docs.
           */
          String t = \"""
              keep   this
                exactly
              \""".strip();
          void f() {
            int sum = 1
                + 2;
            list.stream()
                .map(x -> x * 2)
                .toList();
          }
        }
        """;
    String formatted = JavaFormatter.format(source);
    assertThat(formatted).contains("      keep   this\n        exactly\n      \"\"\".strip();")
        .contains("  /**\n   * Docs.\n   */")
        .contains("    int sum = 1\n        + 2;")
        .contains("        .map(x -> x * 2)");
  }

  /** The invariant: only whitespace changes, over every bundled tutorial and demo. */
  @Test
  void onlyWhitespaceChangesInEveryBundledProject() throws Exception {
    List<String> broken = new ArrayList<>();
    for (var template : BundledTemplates.list()) {
      Path root = BundledTemplates.create(template.id(), tmp, template.id(), null);
      try (var files = Files.walk(root)) {
        for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
          String source = Files.readString(file);
          String formatted = JavaFormatter.format(source);
          if (!source.replaceAll("\\s+", "").equals(formatted.replaceAll("\\s+", ""))) {
            broken.add(file.toString());
          }
          if (!JavaFormatter.format(formatted).equals(formatted)) {
            broken.add("not stable: " + file);
          }
        }
      }
    }
    assertThat(broken).isEmpty();
  }
}
