package org.openpatch.scratch4j.runner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProgramStateTest {

  @Test
  void readsTheReportOfARunningProgram() {
    var state = ProgramState.parse("""
        {"frame":120,"paused":true,
         "stage":{"id":"stage","class":"MyStage","props":[],"fields":[["level","2"]]},
         "total":3,
         "sprites":[{"id":"1a2b","class":"Cat","props":[["@x","12.50"],["@y","-40"]],
                     "fields":[["name","\\"Tom\\""],["target","@ref:3c4d:Mouse"]]}],
         "statics":[{"id":"static:Cat","class":"Cat","props":[],"fields":[["cats","1"]]}]}
        """);
    assertThat(state.frame()).isEqualTo(120);
    assertThat(state.paused()).isTrue();
    assertThat(state.stage().fields()).containsExactly(new ProgramState.Value("level", "2"));
    assertThat(state.total()).isEqualTo(3);
    var cat = state.sprites().get(0);
    assertThat(cat.id()).isEqualTo("1a2b");
    assertThat(cat.props()).containsExactly(new ProgramState.Value("@x", "12.50"),
        new ProgramState.Value("@y", "-40"));
    assertThat(cat.fields()).containsExactly(new ProgramState.Value("name", "\"Tom\""),
        new ProgramState.Value("target", "@ref:3c4d:Mouse"));
    assertThat(state.statics().get(0).id()).isEqualTo("static:Cat");
  }

  @Test
  void aProgramWithoutAStageYetHasNoEntries() {
    var state = ProgramState.parse("{\"frame\":-1,\"paused\":false,\"statics\":[]}");
    assertThat(state.stage()).isNull();
    assertThat(state.sprites()).isEmpty();
    assertThat(state.total()).isZero();
  }
}
