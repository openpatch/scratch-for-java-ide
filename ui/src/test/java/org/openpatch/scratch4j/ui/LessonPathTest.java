package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LessonPathTest {

  @Test
  void numbersTheLessonsAndKnowsWhatComesNext() {
    assertThat(LessonPath.number("first-steps")).isEqualTo(1);
    assertThat(LessonPath.number("not-a-lesson")).isZero();
    assertThat(LessonPath.next("make-it-walk")).isEqualTo("speed-variable");
    assertThat(LessonPath.next("your-game")).as("the end of the path").isNull();
    assertThat(LessonPath.next("not-a-lesson")).isNull();
  }
}
