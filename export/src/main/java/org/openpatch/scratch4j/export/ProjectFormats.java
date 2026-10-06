package org.openpatch.scratch4j.export;

import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.runner.LauncherSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The other export formats: a runnable single jar (project classes + assets +
 * the whole library jar, launched by the generated Scratch4JLauncher) and
 * BlueJ / VS Code project zips that teachers' tools open unchanged.
 */
public final class ProjectFormats {

  private ProjectFormats() {}

  /** Exports a double-clickable runnable jar of the project. */
  public static Path exportRunnableJar(ScratchProject project, Path jarFile)
      throws IOException {
    String startStage = project.startStage();
    if (startStage.isEmpty()) {
      throw new IOException("The project has no Stage class to start");
    }
    Path buildDir = project.root().resolve(".scratch4j/build/jar");
    Path classes = buildDir.resolve("classes");
    List<Path> sources = new ArrayList<>(project.javaSources());
    Path launcher = buildDir.resolve("launcher/Scratch4JLauncher.java");
    Files.createDirectories(launcher.getParent());
    Files.writeString(launcher, LauncherSource.generate(project.settings()), StandardCharsets.UTF_8);
    sources.add(launcher);
    var result = new CompilerService().compile(sources,
        List.copyOf(project.libs()), classes);
    if (!result.success()) {
      throw new IOException("The project does not compile: "
          + result.errors().get(0));
    }

    Files.createDirectories(jarFile.getParent());
    try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(jarFile))) {
      jar.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
      jar.write(("Manifest-Version: 1.0\r\nMain-Class: Scratch4JLauncher\r\n"
          + "Start-Class: " + startStage + "\r\n\r\n")
          .getBytes(StandardCharsets.UTF_8));
      jar.closeEntry();
      copyDir(classes, "", jar);
      copyProjectResources(project, jar);
      // the whole library jar, unpacked in
      for (Path lib : project.libs()) {
        unpackJar(lib, jar);
      }
    }
    return jarFile;
  }

  /** Build output, earlier exports and IDE caches never go into a shared project. */
  static boolean generated(Path relative) {
    String first = relative.getNameCount() == 0 ? "" : relative.getName(0).toString();
    return first.equals("export") || first.equals("target") || first.equals("build")
        || first.equals("out") || relative.toString().replace('\\', '/').startsWith(".scratch4j/build")
        || relative.toString().replace('\\', '/').startsWith(".scratch4j/history")
        || relative.toString().replace('\\', '/').startsWith(".scratch4j/trash")
        || relative.getFileName().toString().endsWith(".class")
        || relative.getFileName().toString().endsWith(".ctxt");
  }

  /** The whole project as a zip for sharing (opens in the IDE, BlueJ or VS Code). */
  public static Path exportShareZip(ScratchProject project, Path zipFile) throws IOException {
    return Zip.zipDirectoryAs(project.root(), project.name() + "/", zipFile,
        ProjectFormats::generated);
  }

  /**
   * Unpacks a shared project zip into {@code parentDir}: a zip with one top
   * folder becomes that folder, a flat zip gets a folder named after it.
   * Returns the project root.
   */
  public static Path importZip(Path zipFile, Path parentDir) throws IOException {
    java.util.Set<String> tops = new java.util.HashSet<>();
    boolean flat = false;
    try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (name.startsWith(MAC_METADATA + "/")) {
          continue; // Finder's resource forks, not part of the project
        }
        int slash = name.indexOf('/');
        if (slash < 0) {
          flat = true;
        } else {
          tops.add(name.substring(0, slash));
        }
      }
    }
    String base = zipFile.getFileName().toString().replaceFirst("(?i)\\.zip$", "");
    Path root;
    Path target;
    if (!flat && tops.size() == 1) {
      root = parentDir.resolve(tops.iterator().next());
      target = parentDir;
    } else {
      root = parentDir.resolve(base);
      target = root;
    }
    if (Files.exists(root)) {
      throw new IOException("Folder already exists: " + root);
    }
    Files.createDirectories(target);
    Path macMetadata = target.resolve(MAC_METADATA);
    boolean hadMacMetadata = Files.exists(macMetadata);
    Zip.unzip(zipFile, target);
    if (!hadMacMetadata && Files.isDirectory(macMetadata)) {
      try (var files = Files.walk(macMetadata)) {
        for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(file);
        }
      }
    }
    return root;
  }

  /** The folder macOS Finder adds to every zip it creates. */
  private static final String MAC_METADATA = "__MACOSX";

  /** Zips the project so BlueJ opens it unchanged ({@code package.bluej} ensured). */
  public static Path exportBlueJZip(ScratchProject project, Path zipFile)
      throws IOException {
    Path packageBluej = project.root().resolve("package.bluej");
    if (!Files.exists(packageBluej)) {
      Files.writeString(packageBluej, "#BlueJ package file\n");
    }
    return Zip.zipDirectoryAs(project.root(), project.name() + "/", zipFile,
        ProjectFormats::generated);
  }

  /** Zips the project with a VS Code settings file referencing {@code +libs/*.jar}. */
  public static Path exportVsCodeZip(ScratchProject project, Path zipFile)
      throws IOException {
    Path settings = project.root().resolve(".vscode/settings.json");
    Files.createDirectories(settings.getParent());
    Files.writeString(settings, "{\n"
        + "  \"java.project.referencedLibraries\": [\"+libs/*.jar\"]\n}\n",
        StandardCharsets.UTF_8);
    return Zip.zipDirectoryAs(project.root(), project.name() + "/", zipFile,
        ProjectFormats::generated);
  }

  /** Includes runtime files from anywhere in the project, including root-level shaders. */
  private static void copyProjectResources(ScratchProject project, ZipOutputStream jar)
      throws IOException {
    try (var files = Files.walk(project.root())) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        Path relative = project.root().relativize(file);
        String first = relative.getName(0).toString();
        if (StudentExport.SKIPPED_DIRS.contains(first)) {
          continue;
        }
        String name = file.getFileName().toString();
        if (name.endsWith(".java") || name.endsWith(".class") || name.endsWith(".ctxt")
            || name.equals("package.bluej") || name.startsWith(".")) {
          continue;
        }
        jar.putNextEntry(new ZipEntry(relative.toString().replace('\\', '/')));
        try (InputStream in = Files.newInputStream(file)) {
          in.transferTo(jar);
        }
        jar.closeEntry();
      }
    }
  }

  private static void copyDir(Path dir, String prefix, ZipOutputStream jar)
      throws IOException {
    try (var files = Files.walk(dir)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String name = prefix + dir.relativize(file).toString().replace('\\', '/');
        jar.putNextEntry(new ZipEntry(name));
        try (InputStream in = Files.newInputStream(file)) {
          in.transferTo(jar);
        }
        jar.closeEntry();
      }
    }
  }

  private static void unpackJar(Path lib, ZipOutputStream jar) throws IOException {
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(lib))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        String name = entry.getName();
        boolean metaInf = name.startsWith("META-INF/");
        boolean services = name.startsWith("META-INF/services/");
        // directories and META-INF (except services) are skipped; the
        // launcher's own MANIFEST.MF must not be overwritten either
        if (entry.isDirectory() || (metaInf && !services)
            || name.equals("META-INF/MANIFEST.MF")) {
          continue;
        }
        try {
          jar.putNextEntry(new ZipEntry(entry.getName()));
          in.transferTo(jar);
          jar.closeEntry();
        } catch (Exception ignored) {
          // duplicate entries from overlapping jars: first one wins
        }
      }
    }
  }
}
