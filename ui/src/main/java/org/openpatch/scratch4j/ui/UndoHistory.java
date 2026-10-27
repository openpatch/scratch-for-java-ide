package org.openpatch.scratch4j.ui;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The undo and redo steps of a visual editor: whole states (a picture's
 * layers, a map, a sound, a stage's source, a diagram's positions), newest
 * first. The editor records the state before each change and puts back what
 * {@link #undo} or {@link #redo} hands it.
 */
final class UndoHistory<S> {

  private final int limit;
  private final Deque<S> undo = new ArrayDeque<>();
  private final Deque<S> redo = new ArrayDeque<>();

  /** Keeps at most {@code limit} undo steps; the oldest go first. */
  UndoHistory(int limit) {
    this.limit = limit;
  }

  /** The state before a change: one more undo step, and the redo steps are gone. */
  void record(S before) {
    undo.push(before);
    if (undo.size() > limit) {
      undo.removeLast();
    }
    redo.clear();
  }

  /** The state to put back, with {@code current} kept for redo; null when there is none. */
  S undo(S current) {
    return step(undo, redo, current);
  }

  /** The state to put back again, with {@code current} kept for undo; null when there is none. */
  S redo(S current) {
    return step(redo, undo, current);
  }

  private static <S> S step(Deque<S> from, Deque<S> to, S current) {
    if (from.isEmpty()) {
      return null;
    }
    to.push(current);
    return from.pop();
  }

  boolean canUndo() {
    return !undo.isEmpty();
  }

  boolean canRedo() {
    return !redo.isEmpty();
  }

  void clear() {
    undo.clear();
    redo.clear();
  }

  /** The undo steps, newest first (to keep them with the file). */
  List<S> undoSteps() {
    return List.copyOf(undo);
  }

  /** The redo steps, newest first. */
  List<S> redoSteps() {
    return List.copyOf(redo);
  }

  /** Replaces the steps with ones kept with the file, newest first. */
  void load(List<S> undoSteps, List<S> redoSteps) {
    clear();
    undo.addAll(undoSteps);
    redo.addAll(redoSteps);
  }
}
