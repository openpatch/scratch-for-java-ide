package org.openpatch.scratch4j.export;

import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.runner.LibraryJarSource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exports from the command line, for CI's cross-OS matrix (export everything
 * on Linux, run each export on its own OS):
 *
 * <pre>
 * ExportCli &lt;project folder | template:id&gt; &lt;out dir&gt; &lt;cache dir&gt; target...
 *   target = linux-x64 | windows-x64 | mac-aarch64 | mac-x64 | windows-aarch64 | linux-aarch64
 * </pre>
 */
public final class ExportCli {

  private ExportCli() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 4) {
      System.err.println("Usage: ExportCli <project|template:id> <out> <cache> <target>...");
      System.exit(2);
    }
    Path out = Path.of(args[1]);
    Path cache = Path.of(args[2]);
    Path root;
    if (args[0].startsWith("template:")) {
      Path work = Files.createDirectories(out.resolve("projects"));
      String id = args[0].substring("template:".length());
      String name = id.startsWith("demo-") ? id.substring("demo-".length()) : id;
      root = work.resolve(name);
      if (!Files.exists(root)) {
        BundledTemplates.create(id, work, name, LibraryJarSource.allJar(cache));
      }
    } else {
      root = Path.of(args[0]);
    }
    ScratchProject project = ScratchProject.open(root);
    for (int i = 3; i < args.length; i++) {
      String[] target = args[i].split("-");
      var result = new StudentExport().export(project,
          new JmodsFetcher.Target(target[0], target[1]), out, cache);
      System.out.println(args[i] + " -> " + result.archive());
    }
  }
}
