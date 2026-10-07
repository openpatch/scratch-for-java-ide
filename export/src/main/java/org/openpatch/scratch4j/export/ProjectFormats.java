package org.openpatch.scratch4j.export;

import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.core.project.PortableProject;
import org.openpatch.scratch4j.core.project.ProjectSettings;
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

  /** Source and real assets, with optional metadata understood by the browser. */
  public static Path exportBrowserZip(ScratchProject project, Path zipFile) throws IOException {
    ProjectSettings settings = project.settings();
    settings.portableVersion = 1;
    settings.startStage = project.startStage();
    Path source = project.sourceOf(settings.startStage);
    if (source != null) settings.startFile = project.root().relativize(source).toString().replace('\\', '/');
    if (settings.libraryVersion.isBlank()) settings.libraryVersion = settings.libraryPin.isBlank()
        ? org.openpatch.scratch4j.runner.LibraryJarSource.SCRATCH_VERSION : settings.libraryPin.replaceFirst("-nrw$", "");
    settings.sourceEnvironment = "studio";
    if ("nrw".equals(settings.flavour)) {
      java.util.Set<String> courseClasses = org.openpatch.scratch4j.core.project.Abiturklassen.present(project);
      settings.desktopFiles = project.javaSources().stream()
          .filter(path -> courseClasses.contains(path.getFileName().toString().replaceFirst("\\.java$", "")))
          .map(path -> project.root().relativize(path).toString().replace('\\', '/')).toList();
    }
    settings.externalDependencies = project.libs().stream().map(path -> path.getFileName().toString())
        .filter(name -> !name.startsWith("scratch-")).toList();
    settings.save(project.root());
    return Zip.zipDirectoryAs(project.root(), project.name() + "/", zipFile,
        path -> generated(path) || path.getName(0).toString().equals("+libs")
            || path.getName(0).toString().equals(".git") || path.toString().endsWith(".jar"));
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
        PortableProject.path(name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
        int slash = name.indexOf('/');
        if (slash < 0) {
          flat = true;
        } else {
          tops.add(name.substring(0, slash));
        }
      }
    }
    String base = PortableProject.path(zipFile.getFileName().toString().replaceFirst("(?i)\\.zip$", ""));
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
    Files.createDirectories(parentDir);
    Path staging = Files.createTempDirectory(parentDir, ".scratch4j-zip-");
    try {
      java.util.Set<String> names = new java.util.HashSet<>();
      long total = 0;
      int count = 0;
      try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
        ZipEntry entry;
        byte[] buffer = new byte[8192];
        while ((entry = zip.getNextEntry()) != null) {
          String name = entry.getName();
          if (name.startsWith(MAC_METADATA + "/")) continue;
          String normalized = PortableProject.path(name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
          if (!names.add(normalized.toLowerCase(java.util.Locale.ROOT))) throw new IOException("Conflicting project filename: " + name);
          Path file = staging.resolve(normalized);
          if (entry.isDirectory()) { Files.createDirectories(file); continue; }
          if (++count > 2000) throw new IOException("Archive has more than 2,000 files");
          Files.createDirectories(file.getParent());
          long size = 0;
          try (OutputStream output = Files.newOutputStream(file)) {
            int read;
            while ((read = zip.read(buffer)) != -1) {
              size += read;
              total += read;
              if (size > 128L * 1024 * 1024 || total > 256L * 1024 * 1024) throw new IOException("Project archive exceeds size limits");
              output.write(buffer, 0, read);
            }
          }
        }
      }
      if (count == 0) throw new IOException("Project archive is empty");
      Path imported = !flat && tops.size() == 1 ? staging.resolve(tops.iterator().next()) : staging;
      Path metadataFile = imported.resolve(".scratch4j/project.json");
      if (Files.isRegularFile(metadataFile)) {
        ProjectSettings settings;
        try { settings = tools.jackson.databind.json.JsonMapper.builder().build().readValue(metadataFile.toFile(), ProjectSettings.class); }
        catch (RuntimeException e) { throw new IOException("Invalid project metadata", e); }
        if (settings == null || settings.version != 1 || settings.portableVersion != 1
            || !java.util.List.of("standard", "nrw").contains(settings.flavour)) throw new IOException("Unsupported project metadata");
        if ("browser".equals(settings.sourceEnvironment)) {
          for (Path source : ScratchProject.open(imported).javaSources()) {
            String name = imported.relativize(source).toString().replace('\\', '/');
            if (settings.desktopFiles != null && settings.desktopFiles.contains(name)) continue;
            Files.writeString(source, PortableProject.implicitImports(Files.readString(source), settings.flavour));
          }
          settings.sourceEnvironment = "studio";
          settings.save(imported);
        }
      }
      Files.move(imported, root);
      return root;
    } finally { if (Files.exists(staging)) PortableProject.deleteTree(staging); }
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
