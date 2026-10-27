package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UndoHistoryTest {

  @Test
  void undoesAndRedoesStatesInOrder() {
    UndoHistory<String> history = new UndoHistory<>(10);
    assertThat(history.canUndo()).isFalse();
    assertThat(history.undo("a")).isNull();
    assertThat(history.canRedo()).isFalse(); // nothing to undo keeps nothing for redo
    history.record("a");
    history.record("b");
    assertThat(history.undo("c")).isEqualTo("b");
    assertThat(history.undo("b")).isEqualTo("a");
    assertThat(history.canUndo()).isFalse();
    assertThat(history.redo("a")).isEqualTo("b");
    assertThat(history.redo("b")).isEqualTo("c");
    assertThat(history.redo("c")).isNull();
  }

  @Test
  void aNewChangeDropsTheRedoSteps() {
    UndoHistory<String> history = new UndoHistory<>(10);
    history.record("a");
    history.undo("b");
    assertThat(history.canRedo()).isTrue();
    history.record("a");
    assertThat(history.canRedo()).isFalse();
  }

  @Test
  void keepsTheNewestStepsUpToTheLimit() {
    UndoHistory<Integer> history = new UndoHistory<>(3);
    for (int i = 1; i <= 5; i++) history.record(i);
    assertThat(history.undoSteps()).containsExactly(5, 4, 3);
  }

  @Test
  void loadsStepsKeptWithTheFileNewestFirst() {
    UndoHistory<String> history = new UndoHistory<>(10);
    history.record("old");
    history.load(List.of("b", "a"), List.of("c"));
    assertThat(history.undoSteps()).containsExactly("b", "a");
    assertThat(history.redoSteps()).containsExactly("c");
    assertThat(history.undo("now")).isEqualTo("b");
    assertThat(history.redoSteps()).containsExactly("now", "c");
  }
}
