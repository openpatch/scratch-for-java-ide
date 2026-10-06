package org.openpatch.scratch4j.core.region;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegionIssueTest {

  private static final String STAGE = """
      public class Pond extends Stage {
        // scratch4j:begin fields (managed by the stage designer)
        Frog frog;
        // scratch4j:end fields

        public Pond() {
          super(480, 360);
          // scratch4j:begin setup (managed by the stage designer)
          this.addBackdrop("pond");
          this.setTint(60);
          frog = new Frog();
          this.add(frog);
          // scratch4j:end setup
        }
      }
      """;

  @Test
  void foreignStatementIsFoundAndMovedBelowTheRegion() {
    var issue = StageDocument.regionIssue(STAGE);
    assertThat(issue).isNotNull();
    assertThat(issue.line()).isEqualTo(10);
    assertThat(issue.statement()).isEqualTo("this.setTint(60);");
    String fixed = StageDocument.moveOutOfRegion(STAGE, issue.line());
    assertThat(StageDocument.regionIssue(fixed)).isNull();
    assertThat(fixed).contains("    this.add(frog);\n    // scratch4j:end setup\n"
        + "    this.setTint(60);\n  }");
  }

  @Test
  void undeclaredSpriteAndReadableRegions() {
    assertThat(StageDocument.regionIssue(STAGE.replace("    this.setTint(60);\n", "")))
        .isNull();
    String unadded = STAGE.replace("    this.setTint(60);\n", "")
        .replace("    this.add(frog);\n", "");
    var issue = StageDocument.regionIssue(unadded);
    assertThat(issue.line()).isEqualTo(3);
    assertThat(issue.statement()).isEqualTo("Frog frog;");
  }

  @Test
  void privateDesignerFieldsStayPrivate() {
    String stage = STAGE.replace("    this.setTint(60);\n", "")
        .replace("  Frog frog;", "  private Frog frog;");
    var doc = StageDocument.read(stage);
    assertThat(doc.model().sprites().byName("frog").visibility()).isEqualTo("private");
    assertThat(doc.write(doc.model())).isEqualTo(stage);
    // a package-private field stays package-private, a new one is private
    String plain = STAGE.replace("    this.setTint(60);\n", "");
    var plainDoc = StageDocument.read(plain);
    assertThat(plainDoc.write(plainDoc.model())).isEqualTo(plain);
    var model = plainDoc.model();
    SpriteRef toad = new SpriteRef("toad", "Toad");
    toad.added(true);
    toad.instantiated(true);
    model.sprites().add(toad);
    assertThat(plainDoc.write(model)).contains("  private Toad toad;");
  }
}
