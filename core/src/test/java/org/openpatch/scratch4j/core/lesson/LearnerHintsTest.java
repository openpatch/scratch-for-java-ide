package org.openpatch.scratch4j.core.lesson;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class LearnerHintsTest {
  @TempDir Path tmp;
  @Test void localizedHintsRoundTripAndChecksExplainOnlyWhatTheyKnow() throws Exception {
    var step = new Lesson.Step("s", Map.of("en", "Click"), Map.of("en", "Add a method"), null,
        new Lesson.MethodExists("whenClicked", ""), Map.of("en", List.of("A callback", "Inside the class", "void whenClicked() {}"), "de", List.of("Ein Ereignis")));
    var lesson = new Lesson("l", "", Map.of("en", "Lesson"), List.of(step));
    assertThat(Lesson.parse(lesson.toJson())).isEqualTo(lesson);
    assertThat(step.hints(Locale.GERMAN)).containsExactly("Ein Ereignis");
    assertThat(step.hints(Locale.FRENCH)).hasSize(3);
    var facts = new Lesson.Facts(Map.of(), true, false);
    assertThat(Lesson.missing(step.check(), facts, Locale.ENGLISH)).containsExactly("No method whenClicked yet.");
    assertThat(Lesson.missing(new Lesson.ClassExists("", "Sprite", 1), facts, Locale.ENGLISH)).containsExactly("No class extends Sprite yet.");
    assertThat(Lesson.missing(new Lesson.Contains("Player.java", "complicated.*regex", false), facts, Locale.ENGLISH)).isEmpty();
    var all = new Lesson.All(List.of(new Lesson.ClassExists("", "Sprite", 1), step.check()));
    assertThat(Lesson.missing(all, new Lesson.Facts(Map.of("Player.java", "class Player extends Sprite {}"), true, false), Locale.ENGLISH))
        .containsExactly("No method whenClicked yet.");
    assertThatIOException().isThrownBy(() -> Lesson.parse(lesson.toJson().replace("\"Ein Ereignis\"", "\"a\",\"b\",\"c\",\"d\"")));
  }
  @Test void progressEmitsStartedAndCompletedOncePerTransition() throws Exception {
    var lesson = new Lesson("l", "", Map.of("en", "Lesson"), List.of(
        new Lesson.Step("class", Map.of(), Map.of(), null, new Lesson.ClassExists("", "Sprite", 1)),
        new Lesson.Step("method", Map.of(), Map.of(), null, new Lesson.MethodExists("whenClicked", ""))));
    var progress = LessonProgress.load(lesson, tmp);
    var events = new ArrayList<String>();
    progress.setOnStep((id, state) -> events.add(id + ":" + state));
    progress.update(Map.of(), true);
    progress.update(Map.of("Player.java", "class Player extends Sprite {}"), true);
    progress.update(Map.of("Player.java", "class Player extends Sprite {}"), true);
    assertThat(events).containsExactly("class:started", "class:completed", "method:started");
    assertThat(progress.missing(Locale.ENGLISH)).containsExactly("No method whenClicked yet.");
    progress.update(Map.of("Player.java", "class Player extends Sprite { void whenClicked() {} }"), true);
    assertThat(events).endsWith("method:completed");
    assertThat(progress.finished()).isTrue();
  }
}
