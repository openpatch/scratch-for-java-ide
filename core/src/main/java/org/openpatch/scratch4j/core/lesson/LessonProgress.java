package org.openpatch.scratch4j.core.lesson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How far a student is in the project's lesson, kept in
 * {@code .scratch4j/lesson-progress.json}: the steps done, how many runs drew
 * a frame, and how many had when the current step began (a "run it" step
 * needs a run after that). Steps are done in order; when one is done the
 * next begins and is checked right away, so a student who is ahead of the
 * text skips the steps their code already shows. Thread-safe: the IDE
 * updates it from its check and from run events.
 */
public final class LessonProgress {

  public static final String FILE = ".scratch4j/lesson-progress.json";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Lesson lesson;
  private final Path file;
  private final Set<String> done = new LinkedHashSet<>();
  private int runs;
  private int runsAtStepStart;

  private LessonProgress(Lesson lesson, Path file) {
    this.lesson = lesson;
    this.file = file;
  }

  /** The progress of {@code lesson} in the project (empty when none was saved). */
  public static LessonProgress load(Lesson lesson, Path projectRoot) {
    LessonProgress progress = new LessonProgress(lesson, projectRoot.resolve(FILE));
    try {
      if (Files.isRegularFile(progress.file)) {
        JsonNode root = JSON.readTree(Files.readString(progress.file, StandardCharsets.UTF_8));
        if (lesson.id().equals(root.path("lesson").asString())) {
          for (JsonNode id : root.path("done")) {
            progress.done.add(id.asString());
          }
          progress.runs = root.path("runs").asInt(0);
          progress.runsAtStepStart = root.path("runsAtStepStart").asInt(0);
        }
      }
    } catch (IOException | RuntimeException e) {
      // a broken progress file starts the lesson again; the code is untouched
    }
    return progress;
  }

  public Lesson lesson() {
    return lesson;
  }

  /** Index of the step to do now; {@code steps().size()} when all are done. */
  public synchronized int current() {
    List<Lesson.Step> steps = lesson.steps();
    for (int i = 0; i < steps.size(); i++) {
      if (!done.contains(steps.get(i).id())) {
        return i;
      }
    }
    return steps.size();
  }

  public synchronized boolean isDone(Lesson.Step step) {
    return done.contains(step.id());
  }

  public synchronized boolean finished() {
    return current() == lesson.steps().size();
  }

  /** A run drew its first frame. Returns the steps this completed (maybe none). */
  public synchronized List<Lesson.Step> ran(Map<String, String> files, boolean compiles) throws IOException {
    runs++;
    return update(files, compiles);
  }

  /**
   * The project as it is now (file name to text, and whether it compiles).
   * Marks the current step done when its check is met, then the next, and so
   * on; returns the steps that were completed by this call, in order.
   */
  public synchronized List<Lesson.Step> update(Map<String, String> files, boolean compiles)
      throws IOException {
    List<Lesson.Step> completed = new ArrayList<>();
    while (!finished()) {
      Lesson.Step step = lesson.steps().get(current());
      Lesson.Facts facts = new Lesson.Facts(files, compiles, runs > runsAtStepStart);
      if (!Lesson.done(step, facts)) {
        break;
      }
      done.add(step.id());
      runsAtStepStart = runs;
      completed.add(step);
    }
    save();
    return completed;
  }

  /** Starts the lesson again from its first step (the code stays as it is). */
  public synchronized void restart() throws IOException {
    done.clear();
    runsAtStepStart = runs;
    save();
  }

  private void save() throws IOException {
    ObjectNode root = JSON.createObjectNode();
    root.put("lesson", lesson.id());
    var ids = root.putArray("done");
    done.forEach(ids::add);
    root.put("runs", runs);
    root.put("runsAtStepStart", runsAtStepStart);
    Files.createDirectories(file.getParent());
    Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root),
        StandardCharsets.UTF_8);
  }
}
