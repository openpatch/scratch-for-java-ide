package org.openpatch.scratch4j.core.compile;

import java.util.List;

/** The outcome of a compilation: success plus all diagnostics. */
public record CompileResult(boolean success, List<CompilerDiagnostic> diagnostics) {

  public List<CompilerDiagnostic> errors() {
    return diagnostics.stream().filter(CompilerDiagnostic::isError).toList();
  }

  public List<CompilerDiagnostic> warnings() {
    return diagnostics.stream().filter(d -> d.kind() == CompilerDiagnostic.Kind.WARNING).toList();
  }
}
