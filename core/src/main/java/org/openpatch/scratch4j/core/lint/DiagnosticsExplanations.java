package org.openpatch.scratch4j.core.lint;

import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;

/**
 * Beginner-friendly explanations for the javac error codes school students hit
 * most often, in English and German. The key is the javac diagnostic code
 * ({@link org.openpatch.scratch4j.core.compile.CompilerDiagnostic#code()},
 * for example {@code compiler.err.cant.resolve.symbol}).
 */
public final class DiagnosticsExplanations {

  public enum Language { EN, DE }

  private DiagnosticsExplanations() {}

  /** The explanation for a javac error code, if one is curated. */
  public static Optional<String> explain(String compilerCode, Language language) {
    if (compilerCode == null || !compilerCode.startsWith("compiler.err.")) {
      return Optional.empty();
    }
    Locale locale = language == Language.DE ? Locale.GERMAN : Locale.ENGLISH;
    ResourceBundle bundle = ResourceBundle.getBundle(
        "org.openpatch.scratch4j.core.lint.explanations",
        locale, DiagnosticsExplanations.class.getClassLoader());
    return bundle.containsKey(compilerCode)
        ? Optional.of(bundle.getString(compilerCode))
        : Optional.empty();
  }
}
