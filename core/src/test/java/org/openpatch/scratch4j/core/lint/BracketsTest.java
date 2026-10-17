package org.openpatch.scratch4j.core.lint;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BracketsTest {
  @Test void bracketLikeTextInsideLiteralsAndCommentsDoesNotAffectTheOpeningLine() {
    var mismatch = Brackets.mismatch("""
        // ) ] }
        class Example {
          String text = ")"; /* ] } */
          void go() {
            int[] scores = new int[
              5);
          }
        }
        """);
    assertThat(mismatch).isEqualTo(new Brackets.Mismatch("[", 5, "]", ")", 6));
    assertThat(Brackets.mismatch("class Example { String text = \"\"\"\n)]}\n\"\"\"; char c = ')'; }"))
        .isNull();
    assertThat(Brackets.mismatch("class Example { String text = \"unfinished ) }"))
        .as("the string diagnostic owns an unclosed literal").isNull();
  }
}
