package org.openpatch.scratch4j.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openpatch.scratch4j.core.lesson.Lesson;

/** The editor's form keeps every step it shows exactly as it was. */
class LessonEditorDialogTest {

  @Test
  void everyBundledStepRoundTripsThroughTheForm() throws Exception {
    Lesson lesson = Lesson.bundled("first-steps");
    for (Lesson.Step step : lesson.steps()) {
      LessonEditorDialog.Draft draft = LessonEditorDialog.Draft.of(step);
      assertThat(draft.kind).as(step.id()).isNotEqualTo(LessonEditorDialog.Kind.ADVANCED);
      assertThat(draft.toStep()).as(step.id()).isEqualTo(step);
    }
  }

  @Test
  void missingPiecesAreNamedNotSaved() {
    var draft = new LessonEditorDialog.Draft();
    draft.id = "s";
    assertThat(draft.toStep()).isEqualTo("lessoneditor.error.title");
    draft.title.put("en", "Say hello");
    draft.kind = LessonEditorDialog.Kind.CONTAINS;
    assertThat(draft.toStep()).isEqualTo("lessoneditor.error.contains");
    draft.file = "Bunny.java";
    draft.pieces = "this.say(\n\n  this.think(  ";
    var step = (Lesson.Step) draft.toStep();
    assertThat(step.check()).isEqualTo(new Lesson.All(List.of(
        new Lesson.Contains("Bunny.java", "this.say(", true),
        new Lesson.Contains("Bunny.java", "this.think(", true))));
    draft.regex = true;
    assertThat(draft.toStep()).isEqualTo("lessoneditor.error.regex");
    draft.kind = LessonEditorDialog.Kind.CHANGED;
    draft.changedPattern = "move\\(\\d+\\)";
    draft.from = "4";
    assertThat(draft.toStep()).as("no group").isEqualTo("lessoneditor.error.group");
    draft.changedPattern = "move\\((\\d+)\\)";
    assertThat(((Lesson.Step) draft.toStep()).check())
        .isEqualTo(new Lesson.Changed("Bunny.java", "move\\((\\d+)\\)", "4"));
  }

  @Test
  void aHandWrittenCheckStaysAsItIs() {
    Lesson.Check mixed = new Lesson.All(List.of(new Lesson.Ran(),
        new Lesson.Contains("A.java", "x", true)));
    var draft = LessonEditorDialog.Draft.of(new Lesson.Step("s", Map.of("en", "S"),
        Map.of("en", "T"), null, mixed));
    assertThat(draft.kind).isEqualTo(LessonEditorDialog.Kind.ADVANCED);
    assertThat(((Lesson.Step) draft.toStep()).check()).isEqualTo(mixed);
  }
}
