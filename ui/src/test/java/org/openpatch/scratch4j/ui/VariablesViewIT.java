package org.openpatch.scratch4j.ui;

import javafx.scene.Scene;
import javafx.scene.control.TreeItem;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openpatch.scratch4j.runner.ProgramState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Variables tab with a report from a running program (needs a display:
 * {@code xvfb-run -a} and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class VariablesViewIT {

  private static final String REPORT = """
      {"frame":%d,"paused":true,
       "stage":{"id":"stage","class":"MyStage","props":[],"fields":[["level","2"]]},
       "total":2,
       "sprites":[
         {"id":"a1","class":"Cat","props":[["@x","%d"],["@y","-40"],["@direction","90"],
           ["@size","100"],["@costume","cat1"],["@visible","true"]],
          "fields":[["score","%d"],["name","\\"Tom\\""],["chasing","@ref:b2:Mouse"]]},
         {"id":"b2","class":"Mouse","props":[["@x","100"],["@y","20"],["@direction","180"],
           ["@size","50"],["@costume","mouse"],["@visible","false"]],"fields":[]}],
       "statics":[{"id":"static:Cat","class":"Cat","props":[],"fields":[["cats","1"]]}]}
      """;

  @Test
  void showsTheProgramsVariablesAndPinsThem() throws Exception {
    List<String> pins = new ArrayList<>();
    I18n.Language language = I18n.current();
    java.util.Locale locale = java.util.Locale.getDefault();
    I18n.set(I18n.Language.EN);
    LayoutIT.startFx();
    try {
      LayoutIT.onFx(() -> {
        VariablesView view = new VariablesView((owner, field, label, on) ->
            pins.add((on ? "pin " : "unpin ") + owner + " " + field + " " + label));
        Stage window = new Stage();
        Scene scene = new Scene(view, 640, 420);
        Theme.apply(scene);
        window.setScene(scene);
        window.show();

        view.reset();
        view.show(ProgramState.parse(REPORT.formatted(120, 12, 7)));
        var top = view.rootForTests().getChildren();
        assertThat(top).extracting(i -> i.getValue().name.get())
            .containsExactly("Stage: MyStage", "Shared variables (static)", "Cat 1", "Mouse 1");
        TreeItem<VariablesView.Row> cat = top.get(2);
        assertThat(cat.getValue().value.get()).isEqualTo("x 12, y -40, 90°");
        assertThat(top.get(3).getValue().value.get()).endsWith("(hidden)");
        // the student's own variables first; other sprites by their name in the list
        assertThat(cat.getChildren()).extracting(i -> i.getValue().name.get() + "=" + i.getValue().value.get())
            .startsWith("score=7", "name=\"Tom\"", "chasing=Mouse 1", "x position=12");

        // the next report updates the same rows (selection and expansion stay)
        TreeItem<VariablesView.Row> score = cat.getChildren().get(0);
        view.show(ProgramState.parse(REPORT.formatted(121, 13, 8)));
        assertThat(view.rootForTests().getChildren().get(2).getChildren().get(0)).isSameAs(score);
        assertThat(score.getValue().value.get()).isEqualTo("8");

        view.pinForTests(score.getValue(), true);
        view.pinForTests(score.getValue(), false);
        UiSmokeIT.snapshot(view, Path.of("target/variables-view.png"));
        window.close();
      });
    } finally {
      I18n.set(language);
      java.util.Locale.setDefault(locale);
    }
    assertThat(pins).containsExactly("pin a1 score Cat 1: score", "unpin a1 score Cat 1: score");
  }
}
