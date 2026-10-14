package org.openpatch.scratch4j.core.project;

/**
 * How far an import has come, told from the thread doing it, so the IDE can
 * show a progress bar instead of seeming to hang on a large project.
 */
@FunctionalInterface
public interface ImportProgress {

  /** For callers that do not show progress. */
  ImportProgress NONE = (item, done, total) -> { };

  /**
   * {@code done} of {@code total} steps are finished; {@code item} is what the
   * import works on now (a file, a costume), as named in the project.
   */
  void step(String item, int done, int total);
}
