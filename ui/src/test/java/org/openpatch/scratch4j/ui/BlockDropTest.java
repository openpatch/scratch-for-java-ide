package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.openpatch.scratch4j.ui.BlockDrop.Problem;
import org.openpatch.scratch4j.ui.BlockDrop.Slot;

class BlockDropTest {

  private static final String CALL = "this.turnRight(15);\n";
  private static final String EVENT = "public void whenClicked() {\n  \n}\n";

  private static final String CAT = """
      import org.openpatch.scratch.*;

      public class Cat extends Sprite {
        private int speed = 2;

        public Cat() {
          this.addCostume("cat", "cat.png");
        }

        public void run() {
          this.move(speed);
          if (this.isTouchingEdge()) {
            this.turnLeft(180);
          } else {
            this.say("ok");
          }
        }
      }
      """;

  /** The (0-based) line that starts with {@code start}, after its indent. */
  private static int line(String text, String start) {
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].strip().startsWith(start)) return i;
    }
    throw new AssertionError("no line " + start);
  }

  private static Problem before(String text, String start, String snippet) {
    return BlockDrop.at(text, new Slot(line(text, start), false), snippet).problem();
  }

  private static String apply(String text, BlockDrop.Target target) {
    var insertion = target.insertion();
    return text.substring(0, insertion.start()) + insertion.text()
        + text.substring(insertion.end());
  }

  @Test
  void callsGoBetweenStatementsOfAMethodOrConstructor() {
    assertThat(before(CAT, "this.addCostume", CALL)).isNull();
    assertThat(before(CAT, "this.move(speed)", CALL)).isNull();
    assertThat(before(CAT, "if (this.isTouchingEdge", CALL)).isNull();
    assertThat(before(CAT, "this.turnLeft", CALL)).isNull();
    // the end of the if's block and of the method body
    assertThat(before(CAT, "} else {", CALL)).isNull();
    assertThat(BlockDrop.at(CAT, new Slot(line(CAT, "public void run") + 7, false), CALL)
        .problem()).isNull();
  }

  @Test
  void callsStayOutOfTheClassBodyAndOutOfTheFile() {
    assertThat(before(CAT, "private int speed", CALL)).isEqualTo(Problem.BODY);
    assertThat(before(CAT, "public Cat()", CALL)).isEqualTo(Problem.BODY);
    assertThat(before(CAT, "public void run()", CALL)).isEqualTo(Problem.BODY);
    assertThat(before(CAT, "public class Cat", CALL)).isEqualTo(Problem.BODY);
    assertThat(before(CAT, "import", CALL)).isEqualTo(Problem.BODY);
    // the blank line between two methods, the last line of the file
    assertThat(BlockDrop.at(CAT, new Slot(line(CAT, "public void run()") - 1, true), CALL)
        .problem()).isEqualTo(Problem.BODY);
    int last = CAT.split("\n", -1).length - 1;
    assertThat(BlockDrop.at(CAT, new Slot(last, true), CALL).problem())
        .isEqualTo(Problem.BODY);
  }

  @Test
  void aCallKeepsTheIndentOfTheStatementsAround() {
    var target = BlockDrop.at(CAT, new Slot(line(CAT, "if (this.isTouchingEdge"), false), CALL);
    assertThat(apply(CAT, target)).contains("""
            this.move(speed);
            this.turnRight(15);
            if (this.isTouchingEdge()) {
        """);
    assertThat(target.insertion().addedRows()).isEqualTo(1);
    assertThat(target.insertion().caret())
        .isEqualTo(target.insertion().start() + "    this.turnRight(15);".length());
    // the end of an if's block: one step in from its closing brace
    var end = BlockDrop.at(CAT, new Slot(line(CAT, "} else {"), false), CALL);
    assertThat(apply(CAT, end)).contains("""
              this.turnLeft(180);
              this.turnRight(15);
            } else {
        """);
  }

  @Test
  void hoveringAMethodsFirstLineDropsIntoTheMethod() {
    // the half above it is the class body: the block goes below
    var target = BlockDrop.onLine(CAT, line(CAT, "public void run()"), true, CALL);
    assertThat(target.valid()).isTrue();
    assertThat(target.slot()).isEqualTo(new Slot(line(CAT, "public void run()") + 1, false));
    // the lower half of the closing brace: the end of the method
    int close = line(CAT, "public void run()") + 7;
    var last = BlockDrop.onLine(CAT, close, false, CALL);
    assertThat(last.slot()).isEqualTo(new Slot(close, false));
    // the import: nowhere
    assertThat(BlockDrop.onLine(CAT, 0, false, CALL).problem()).isEqualTo(Problem.BODY);
  }

  @Test
  void aStatementIsNotSplit() {
    String text = """
        public class Cat extends Sprite {
          public void run() {
            this.say("a",
                "b");
            if (this.isTouchingEdge()) {
              this.move(1);
            }
            else {
              this.move(2);
            }
            do {
              this.move(3);
            }
            while (this.isTouchingEdge());
            /* a comment
               over two lines */
            String s = \"""
                text
                \""";
            this.move(4);
          }
        }
        """;
    assertThat(before(text, "\"b\");", CALL)).isEqualTo(Problem.STATEMENT);
    assertThat(before(text, "else {", CALL)).isEqualTo(Problem.STATEMENT);
    assertThat(before(text, "while (this", CALL)).isEqualTo(Problem.STATEMENT);
    assertThat(before(text, "over two lines", CALL)).isEqualTo(Problem.TEXT);
    assertThat(before(text, "text", CALL)).isEqualTo(Problem.TEXT);
    assertThat(before(text, "String s", CALL)).isNull();
    assertThat(before(text, "this.move(4)", CALL)).isNull();
  }

  @Test
  void lambdasAreBodiesAnonymousClassesAreClassBodies() {
    String text = """
        public class Cat extends Sprite {
          private Runnable later = () -> {
            this.move(1);
          };

          public void run() {
            Runnable r = new Runnable() {
              public void run() {
                System.out.println();
              }
            };
            this.move(2);
          }
        }
        """;
    assertThat(before(text, "this.move(1)", CALL)).isNull();
    assertThat(before(text, "System.out", CALL)).isNull();
    // the anonymous class's body, around its method
    int inner = line(text, "System.out") - 1;
    assertThat(BlockDrop.at(text, new Slot(inner, false), CALL).problem())
        .isEqualTo(Problem.BODY);
    assertThat(BlockDrop.at(text, new Slot(inner, false), EVENT).problem()).isNull();
    assertThat(before(text, "this.move(2)", CALL)).isNull();
  }

  @Test
  void switchesTakeStatementsAfterACaseColon() {
    String text = """
        public class Cat extends Sprite {
          public void run() {
            switch (this.getCostumeIndex()) {
              case 1:
                this.move(1);
                break;
              default:
                break;
            }
            int step = switch (this.getCostumeIndex()) {
              case 1 -> 2;
              default -> 3;
            };
          }
        }
        """;
    assertThat(before(text, "case 1:", CALL)).isEqualTo(Problem.STATEMENT);
    assertThat(before(text, "this.move(1)", CALL)).isNull();
    assertThat(before(text, "case 1 ->", CALL)).isEqualTo(Problem.STATEMENT);
    assertThat(before(text, "default -> 3", CALL)).isEqualTo(Problem.STATEMENT);
    assertThat(before(text, "int step", CALL)).isNull();
  }

  @Test
  void eventMethodsGoBetweenMembersOfTheClass() {
    assertThat(before(CAT, "private int speed", EVENT)).isNull();
    assertThat(before(CAT, "public Cat()", EVENT)).isNull();
    assertThat(before(CAT, "public void run()", EVENT)).isNull();
    int classEnd = CAT.split("\n", -1).length - 2;
    assertThat(BlockDrop.at(CAT, new Slot(classEnd, false), EVENT).problem()).isNull();

    assertThat(before(CAT, "this.move(speed)", EVENT)).isEqualTo(Problem.CLASS_BODY);
    assertThat(before(CAT, "this.addCostume", EVENT)).isEqualTo(Problem.CLASS_BODY);
    assertThat(before(CAT, "public class Cat", EVENT)).isEqualTo(Problem.CLASS_BODY);
    assertThat(BlockDrop.at(CAT, new Slot(1, true), EVENT).problem())
        .isEqualTo(Problem.CLASS_BODY);
  }

  @Test
  void anEventMethodIsNotPutInsideAMember() {
    String text = """
        public class Cat extends Sprite {
          private int speed =
              2;

          @Override
          public void run() {
          }
        }

        enum Direction {
          UP,
          DOWN;

          int dx() {
            return 0;
          }
        }
        """;
    assertThat(before(text, "2;", EVENT)).isEqualTo(Problem.MEMBER);
    assertThat(before(text, "public void run()", EVENT)).isEqualTo(Problem.MEMBER);
    assertThat(before(text, "@Override", EVENT)).isNull();
    assertThat(before(text, "DOWN;", EVENT)).isEqualTo(Problem.MEMBER);
    assertThat(before(text, "int dx()", EVENT)).isNull();
  }

  @Test
  void anEventTheClassHasIsNotAddedTwice() {
    String text = CAT.replace("  public void run() {",
        "  public void whenClicked() {\n  }\n\n  public void run() {");
    var target = BlockDrop.at(text, new Slot(line(text, "public Cat()"), false), EVENT);
    assertThat(target.problem()).isEqualTo(Problem.EXISTS);
    assertThat(target.existingLine()).isEqualTo(line(text, "public void whenClicked") + 1);
  }

  @Test
  void anEventMethodKeepsABlankLineToItsNeighbours() {
    // the blank line between the constructor and run()
    int blank = line(CAT, "public void run()") - 1;
    var target = BlockDrop.at(CAT, new Slot(blank, true), EVENT);
    assertThat(apply(CAT, target)).contains("""
            this.addCostume("cat", "cat.png");
          }

          public void whenClicked() {
           \s
          }

          public void run() {
        """);
    assertThat(target.insertion().blankBefore()).isEqualTo(1);
    assertThat(target.insertion().addedRows()).isEqualTo(4);
    // the caret waits in the empty body
    String after = apply(CAT, target);
    assertThat(after.substring(0, target.insertion().caret()))
        .endsWith("public void whenClicked() {\n    ");

    // before the class's closing brace: a blank line above only
    int classEnd = CAT.split("\n", -1).length - 2;
    var end = BlockDrop.at(CAT, new Slot(classEnd, false), EVENT);
    assertThat(apply(CAT, end)).endsWith("""
            }
          }

          public void whenClicked() {
           \s
          }
        }
        """);
    assertThat(end.insertion().addedRows()).isEqualTo(4);
  }

  @Test
  void aFourSpaceFileGetsFourSpaces() {
    String text = """
        public class Cat extends Sprite {
            public void run() {
                this.move(1);
            }
        }
        """;
    var call = BlockDrop.at(text, new Slot(line(text, "}"), false), CALL);
    assertThat(apply(text, call)).contains("        this.move(1);\n        this.turnRight(15);\n");
    var event = BlockDrop.at(text, new Slot(4, false), EVENT);
    assertThat(apply(text, event)).contains(
        "    public void whenClicked() {\n        \n    }\n}");
  }

  @Test
  void reportersGoInlineInsideABody() {
    String value = "this.getX()";
    int argument = CAT.indexOf("this.move(") + "this.move(".length();
    var target = BlockDrop.inline(CAT, argument, value);
    assertThat(target.valid()).isTrue();
    assertThat(apply(CAT, target)).contains("this.move(this.getX()speed);");
    assertThat(target.insertion().caret()).isEqualTo(argument + value.length());

    assertThat(BlockDrop.inline(CAT, argument + 2, value).problem()).isEqualTo(Problem.TEXT);
    assertThat(BlockDrop.inline(CAT, CAT.indexOf("cat.png"), value).problem())
        .isEqualTo(Problem.TEXT);
    assertThat(BlockDrop.inline(CAT, CAT.indexOf("= 2;") + 2, value).problem())
        .isEqualTo(Problem.BODY);
    String commented = CAT.replace("this.move(speed);", "this.move(speed); // go");
    assertThat(BlockDrop.inline(commented, commented.indexOf("// go") + 4, value).problem())
        .isEqualTo(Problem.TEXT);
  }

  @Test
  void kinds() {
    assertThat(BlockDrop.kindOf(CALL)).isEqualTo(BlockDrop.Kind.STATEMENT);
    assertThat(BlockDrop.kindOf(EVENT)).isEqualTo(BlockDrop.Kind.MEMBER);
    assertThat(BlockDrop.kindOf("this.getX()")).isEqualTo(BlockDrop.Kind.EXPRESSION);
    assertThat(BlockDrop.eventMethod(EVENT)).isEqualTo("whenClicked");
  }
}
