package org.openpatch.scratch4j.core.lint;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class StuckRuleTest {
  @Test void onlyTheThirdSurvivingRunFiresAndProgressResetsTheRule() {
    var rule = new StuckRule();
    assertThat(rule.run(Set.of("error"), "s1").errors()).isEmpty();
    assertThat(rule.run(Set.of("error"), "s1").step()).isFalse();
    var third = rule.run(Set.of("error"), "s1");
    assertThat(third.errors()).containsExactly("error"); assertThat(third.step()).isTrue();
    assertThat(rule.run(Set.of("error"), "s1").errors()).isEmpty();
    assertThat(rule.run(Set.of(), "s2").step()).isFalse();
    assertThat(rule.run(Set.of("error"), "s2").errors()).isEmpty();
    rule.clear();
    assertThat(rule.run(Set.of("error"), "").step()).isFalse();
  }
}
