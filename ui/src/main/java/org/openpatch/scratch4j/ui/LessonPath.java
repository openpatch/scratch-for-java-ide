package org.openpatch.scratch4j.ui;

import org.openpatch.scratch4j.core.lesson.Lesson;
import org.openpatch.scratch4j.core.lesson.LessonProgress;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The bundled lessons as one path: their number on it, which ones this
 * computer's recent projects finished, and the lesson after one.
 */
final class LessonPath {

  private LessonPath() {}

  static List<String> ids() {
    return Lesson.bundledIds();
  }

  /** 1 for the first lesson on the path; 0 for a lesson that is not on it. */
  static int number(String id) {
    return ids().indexOf(id) + 1;
  }

  /** The lesson after {@code id}, or null at the end (or off the path). */
  static String next(String id) {
    int at = ids().indexOf(id);
    return at < 0 || at + 1 >= ids().size() ? null : ids().get(at + 1);
  }

  /** Lessons a recent project finished. */
  static Set<String> finished() {
    Set<String> done = new LinkedHashSet<>();
    for (Path project : Prefs.recentProjects()) {
      try {
        Lesson lesson = Lesson.load(project);
        if (lesson != null && LessonProgress.load(lesson, project).finished()) {
          done.add(lesson.id());
        }
      } catch (java.io.IOException | RuntimeException e) {
        // an unreadable lesson does not count
      }
    }
    return done;
  }
}
