package org.openpatch.scratch4j.export;

import java.nio.file.Path;
import org.openpatch.scratch4j.core.project.PortableProject;
import org.openpatch.scratch4j.core.project.ScratchProject;

/** Local adapters used by course-pack generation and cross-runtime transfer checks. */
public final class ProjectTransferCli {
  private ProjectTransferCli() {}
  public static void main(String[] args) throws Exception {
    if (args.length == 5 && args[0].equals("course")) {
      var imported = CoursePack.importZip(Path.of(args[1]), Path.of(args[2]), java.util.Map.of(
          "standard", Path.of(args[3]), "nrw", Path.of(args[4])));
      for (Path directory : imported.projects()) {
        ScratchProject project = ScratchProject.open(directory);
        var compiled = new org.openpatch.scratch4j.core.compile.CompilerService().compile(
            project.javaSources(), project.libs(), directory.resolve(".scratch4j/build/course-check"));
        if (!compiled.success()) throw new java.io.IOException(directory + ": " + compiled.errors());
        if (java.nio.file.Files.isRegularFile(directory.resolve(".scratch4j/checks.json"))) {
          var handle = new org.openpatch.scratch4j.runner.TeachingTestRunner().run(project,
              new org.openpatch.scratch4j.runner.RunListener() {
                public void onStdout(String line) { System.out.println(line); }
                public void onStderr(String line) { System.err.println(line); }
              });
          try {
            if (handle.exitFuture().get(30, java.util.concurrent.TimeUnit.SECONDS) != 0)
              throw new java.io.IOException("Behavior check failed: " + directory);
          } finally { handle.stop(); }
        }
        System.out.println("Course project compiled offline: " + directory.getFileName());
      }
      System.out.println("Course pack imported and checked: " + imported.projects().size() + " projects");
      return;
    }
    if (args.length != 3 || !(args[0].equals("import") || args[0].equals("export"))) {
      throw new IllegalArgumentException("Usage: ProjectTransferCli import <project.zip|workspace.json> <parent> | export <project-folder> <project.zip> | course <pack.zip> <parent> <standard.jar> <nrw.jar>");
    }
    Path input = Path.of(args[1]);
    Path output = Path.of(args[2]);
    if (args[0].equals("export")) System.out.println(ProjectFormats.exportBrowserZip(ScratchProject.open(input), output));
    else System.out.println(input.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json")
        ? PortableProject.importWorkspace(input, output) : ProjectFormats.importZip(input, output));
  }
}
