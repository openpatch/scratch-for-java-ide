package org.openpatch.scratch4j.ui;

import java.nio.file.Path;
import java.util.List;

/**
 * One problem in the problems pane (compiler diagnostic or asset lint).
 * {@code original} is javac's message under a friendly headline (null if
 * none); a {@code followUp} may go away once the error above it is fixed.
 */
record Problem(Path file, long line, long column, String message, String explanation,
    List<String> suggestions, boolean error, String fix, String original, boolean followUp,
    String fixData) {

  Problem(Path file, long line, long column, String message, String explanation,
      List<String> suggestions, boolean error, String fix, String original, boolean followUp) {
    this(file, line, column, message, explanation, suggestions, error, fix, original, followUp,
        null);
  }

  Problem(Path file, long line, long column, String message, String explanation,
      List<String> suggestions, boolean error, String fix) {
    this(file, line, column, message, explanation, suggestions, error, fix, null, false);
  }

  Problem(Path file, long line, long column, String message, String explanation,
      List<String> suggestions, boolean error) {
    this(file, line, column, message, explanation, suggestions, error, null);
  }

  /** A hint (not a problem): something that can be made better, with its fix. */
  boolean isHint() {
    return "region.promote".equals(fix);
  }

  /** The label of the fix button and light bulb ({@code null} without a fix). */
  String fixLabel() {
    if (fix == null) {
      return null;
    }
    String key = "problems.fix." + fix;
    return fixData == null ? I18n.t(key) : I18n.t(key, fixData);
  }

  String location() {
    return file == null ? "" : file.getFileName() + ":" + line;
  }

  String display() {
    StringBuilder sb = new StringBuilder();
    sb.append(file == null ? "" : location() + "  ");
    sb.append(message);
    if (explanation != null && !explanation.isBlank()) {
      sb.append("\n    ").append(explanation);
    }
    if (suggestions != null && !suggestions.isEmpty()) {
      sb.append("\n    ").append(I18n.t("problems.didyoumean")).append(' ')
          .append(String.join(", ", suggestions));
    }
    if (original != null) {
      sb.append("\n    ").append(I18n.t("problems.original", original.strip()));
    }
    return sb.toString();
  }

  CodeEditor.Diagnostic toDiagnostic() {
    String detail = explanation;
    if (suggestions != null && !suggestions.isEmpty()) {
      String hint = I18n.t("problems.didyoumean") + " " + String.join(", ", suggestions);
      detail = detail == null || detail.isBlank() ? hint : detail + "\n" + hint;
    }
    return new CodeEditor.Diagnostic(line, column, message, detail, error);
  }
}
