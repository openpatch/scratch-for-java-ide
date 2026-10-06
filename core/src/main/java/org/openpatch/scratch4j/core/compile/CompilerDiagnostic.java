package org.openpatch.scratch4j.core.compile;

import javax.tools.Diagnostic;
import javax.tools.FileObject;

/**
 * One compiler message. {@link #code()} is the javac error key (for example
 * {@code compiler.err.expected}) — the hook the M2 beginner explanations map on.
 * {@link #englishMessage()} is javac's English text whatever {@link #message()}'s
 * language: the friendly explanations read names and types out of it.
 */
public record CompilerDiagnostic(
    Kind kind,
    String path,
    long line,
    long column,
    String code,
    String message,
    String englishMessage) {

  public enum Kind { ERROR, WARNING, NOTE }

  static CompilerDiagnostic of(Diagnostic<? extends FileObject> d) {
    return of(d, java.util.Locale.ROOT);
  }

  /**
   * {@code messageLocale} picks javac's message language. English is
   * {@link java.util.Locale#ROOT}: asking for {@code Locale.ENGLISH} falls back
   * to the system locale's bundle on non-English systems.
   */
  static CompilerDiagnostic of(Diagnostic<? extends FileObject> d,
      java.util.Locale messageLocale) {
    Kind kind = switch (d.getKind()) {
      case ERROR -> Kind.ERROR;
      case WARNING, MANDATORY_WARNING -> Kind.WARNING;
      default -> Kind.NOTE;
    };
    String path = d.getSource() == null ? "" : d.getSource().getName();
    return new CompilerDiagnostic(kind, path, d.getLineNumber(), d.getColumnNumber(),
        d.getCode(), d.getMessage(messageLocale), d.getMessage(java.util.Locale.ROOT));
  }

  public boolean isError() {
    return kind == Kind.ERROR;
  }

  @Override
  public String toString() {
    if (path.isEmpty()) {
      return kind + ": " + message;
    }
    return path + ":" + line + ":" + column + " " + kind + ": " + message;
  }
}
