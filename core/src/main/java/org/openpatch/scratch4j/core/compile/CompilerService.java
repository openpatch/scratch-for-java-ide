package org.openpatch.scratch4j.core.compile;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * In-process javac (the bundled {@code jdk.compiler}), used for compile-as-you-type
 * diagnostics and for the build before Run. Compiles UTF-8, writes class files
 * to one output directory.
 */
public final class CompilerService {

  /** Compiles {@code sources} against {@code classpath} into {@code outDir}; English messages. */
  public CompileResult compile(List<Path> sources, List<Path> classpath, Path outDir)
      throws IOException {
    return compile(sources, classpath, outDir, java.util.Locale.ROOT);
  }

  /** As above, with javac's messages in {@code messageLocale} (ROOT = English). */
  public CompileResult compile(List<Path> sources, List<Path> classpath, Path outDir,
      java.util.Locale messageLocale) throws IOException {
    if (sources.isEmpty()) {
      return new CompileResult(true, List.of());
    }
    Files.createDirectories(outDir);
    List<javax.tools.JavaFileObject> adapted = new ArrayList<>();
    List<Path> actualClasspath = new ArrayList<>(classpath);
    boolean teachingTests = false;
    for (Path source : sources) {
      String contents = Files.readString(source);
      teachingTests |= TeachingTests.hasTests(contents);
      String code = TeachingTests.adapt(contents);
      adapted.add(new javax.tools.SimpleJavaFileObject(source.toAbsolutePath().toUri(),
          javax.tools.JavaFileObject.Kind.SOURCE) {
        @Override public CharSequence getCharContent(boolean ignoreErrors) { return code; }
      });
    }
    if (teachingTests) actualClasspath.add(TeachingTests.junitJar());
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("No system java compiler; is jdk.compiler present?");
    }
    DiagnosticCollector<javax.tools.FileObject> diagnostics = new DiagnosticCollector<>();
    List<CompilerDiagnostic> mapped = new ArrayList<>();
    boolean success;
    try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null,
        java.nio.charset.StandardCharsets.UTF_8)) {
      if (!actualClasspath.isEmpty()) {
        files.setLocation(StandardLocation.CLASS_PATH,
            actualClasspath.stream().map(Path::toFile).toList());
      }
      success = compiler.getTask(null, files, diagnostics,
          // -g: line numbers and local variable names for the debugger
          List.of("-g", "-encoding", "UTF-8", "-d", outDir.toString()),
          null, adapted).call();
    }
    for (javax.tools.Diagnostic<? extends javax.tools.FileObject> d : diagnostics.getDiagnostics()) {
      mapped.add(CompilerDiagnostic.of(d, messageLocale));
    }
    return new CompileResult(success, List.copyOf(mapped));
  }
}
