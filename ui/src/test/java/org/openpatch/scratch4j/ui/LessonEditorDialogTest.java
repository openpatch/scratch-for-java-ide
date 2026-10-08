package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openpatch.scratch4j.core.lesson.Lesson;

/** The editor's form keeps every step it shows exactly as it was. */
class LessonEditorDialogTest {

  private static final List<String> EN = List.of("en");

  @Test
  void everyBundledStepRoundTripsThroughTheForm() throws Exception {
    Lesson lesson = Lesson.bundled("first-steps");
    for (Lesson.Step step : lesson.steps()) {
      LessonEditorDialog.Draft draft = LessonEditorDialog.Draft.of(step);
      assertThat(draft.kind).as(step.id()).isNotEqualTo(LessonEditorDialog.Kind.ADVANCED);
      assertThat(draft.toStep(lesson.languages())).as(step.id()).isEqualTo(step);
    }
  }

  @Test
  void missingPiecesAreNamedNotSaved() {
    var draft = new LessonEditorDialog.Draft();
    draft.id = "s";
    assertThat(draft.toStep(EN)).isEqualTo("lessoneditor.error.title.en");
    draft.title.put("en", "Say hello");
    draft.kind = LessonEditorDialog.Kind.CONTAINS;
    assertThat(draft.toStep(EN)).isEqualTo("lessoneditor.error.contains");
    draft.file = "Bunny.java";
    draft.pieces = "this.say(\n\n  this.think(  ";
    var step = (Lesson.Step) draft.toStep(EN);
    assertThat(step.check()).isEqualTo(new Lesson.All(List.of(
        new Lesson.Contains("Bunny.java", "this.say(", true),
        new Lesson.Contains("Bunny.java", "this.think(", true))));
    draft.regex = true;
    assertThat(draft.toStep(EN)).isEqualTo("lessoneditor.error.regex");
    draft.kind = LessonEditorDialog.Kind.CHANGED;
    draft.changedPattern = "move\\(\\d+\\)";
    draft.from = "4";
    assertThat(draft.toStep(EN)).as("no group").isEqualTo("lessoneditor.error.group");
    draft.changedPattern = "move\\((\\d+)\\)";
    assertThat(((Lesson.Step) draft.toStep(EN)).check())
        .isEqualTo(new Lesson.Changed("Bunny.java", "move\\((\\d+)\\)", "4"));
  }

  @Test
  void theNewConditionsRoundTripAndNameWhatIsMissing() {
    for (Lesson.Check check : List.<Lesson.Check>of(
        new Lesson.ClassExists("", "Sprite", 2),
        new Lesson.ClassExists("Coin", "", 1),
        new Lesson.MethodExists("whenClicked", ""),
        new Lesson.MethodExists("whenClicked", "Bunny.java"),
        new Lesson.LineChanged("Bunny.java", "this.move(4);"))) {
      var step = new Lesson.Step("s", Map.of("de", "Titel"), Map.of("de", "Text"), null, check);
      assertThat(LessonEditorDialog.Draft.of(step).toStep(List.of("de"))).as(check.toString())
          .isEqualTo(step);
    }
    var draft = new LessonEditorDialog.Draft();
    draft.id = "s";
    draft.title.put("de", "Titel");
    draft.kind = LessonEditorDialog.Kind.CLASS;
    assertThat(draft.toStep(List.of("de"))).isEqualTo("lessoneditor.error.class");
    draft.kind = LessonEditorDialog.Kind.METHOD;
    draft.methodName = "when clicked";
    assertThat(draft.toStep(List.of("de"))).isEqualTo("lessoneditor.error.method");
    draft.kind = LessonEditorDialog.Kind.LINE;
    assertThat(draft.toStep(List.of("de"))).isEqualTo("lessoneditor.error.line");
  }

  @Test
  void aGermanOnlyLessonNeedsGermanTitlesAndDropsTheEnglishTexts() {
    var draft = new LessonEditorDialog.Draft();
    draft.id = "s";
    draft.title.put("en", "Only English");
    assertThat(draft.toStep(List.of("de"))).isEqualTo("lessoneditor.error.title.de");
    draft.title.put("de", "Auf Deutsch");
    var step = (Lesson.Step) draft.toStep(List.of("de"));
    assertThat(step.title()).containsOnlyKeys("de");
  }

  @Test
  void aHandWrittenCheckStaysAsItIs() {
    Lesson.Check mixed = new Lesson.All(List.of(new Lesson.Ran(),
        new Lesson.Contains("A.java", "x", true)));
    var draft = LessonEditorDialog.Draft.of(new Lesson.Step("s", Map.of("en", "S"),
        Map.of("en", "T"), null, mixed));
    assertThat(draft.kind).isEqualTo(LessonEditorDialog.Kind.ADVANCED);
    assertThat(((Lesson.Step) draft.toStep(EN)).check()).isEqualTo(mixed);
  }
}
