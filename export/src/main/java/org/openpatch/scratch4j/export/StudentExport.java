package org.openpatch.scratch4j.export;

import org.openpatch.scratch4j.core.compile.CompileResult;
import org.openpatch.scratch4j.core.compile.CompilerService;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.runner.LauncherSource;
import org.openpatch.scratch4j.runner.LibraryJarSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Exports a student project as a double-clickable app folder: a jlink'd
 * runtime, the project's classes and assets, the library jar and a launcher
 * script per OS (a runnable folder is honest cross-OS packaging — jpackage
 * cannot cross-build, and these folders run everywhere without admin rights).
 */
public final class StudentExport {

  /** The operating systems an export can target. */
  public enum Os { LINUX, WINDOWS, MACOS }

  /** What the export produced. */
  public record Result(Path appDir, Path archive, String startStage) {}

  private final CompilerService compiler = new CompilerService();

  /**
   * Exports the project for the current computer: a jlink'd runtime when the
   * JDK ships jmods, otherwise the bundled-runtime fallback (Temurin 25 has
   * no jmods - JEP 493).
   */
  public Result export(ScratchProject project, Os os, Path outParent)
      throws IOException {
    Path jmods = RuntimeBuilder.currentJmods();
    return export(project, os, jmods, outParent);
  }

  /**
   * Exports for any platform from this computer. For this computer's own
   * platform it links/bundles the local runtime; for another one it jlinks
   * against the target JDK's jmods when Adoptium ships them, otherwise it
   * bundles the target's Temurin JRE (JEP 493 fallback). Downloads are
   * verified and cached in {@code cacheDir}; school admins can pre-seed it.
   */
  public Result export(ScratchProject project, JmodsFetcher.Target target, Path outParent,
      Path cacheDir) throws IOException {
    Os os = switch (target.os()) {
      case "windows" -> Os.WINDOWS;
      case "mac" -> Os.MACOS;
      default -> Os.LINUX;
    };
    if (target.equals(JmodsFetcher.Target.current())) {
      return export(project, os, target.os() + "-" + target.arch(),
          localRuntimeSource(project, RuntimeBuilder.currentJmods()), outParent);
    }
    String feature = String.valueOf(Runtime.version().feature());
    RuntimeSource source;
    Path cachedJre = JmodsFetcher.cachedJre(target, feature, cacheDir);
    if (cachedJre != null || !RuntimeBuilder.canLink()) {
      // already downloaded or seeded by an admin (no network needed), or no
      // jlink to use target jmods with (the installed IDE's runtime has none)
      Path jre = cachedJre != null ? cachedJre : JmodsFetcher.jre(target, feature, cacheDir);
      source = runtime -> RuntimeBuilder.copyRuntime(jre, runtime);
    } else {
      try {
        Path jmods = JmodsFetcher.jmods(target, feature, cacheDir);
        source = runtime -> RuntimeBuilder.link(jmods, runtime,
            RuntimeBuilder.modulesFor(libraryJar(project)).toArray(new String[0]));
      } catch (IOException noJmods) {
        Path jre = JmodsFetcher.jre(target, feature, cacheDir);
        source = runtime -> RuntimeBuilder.copyRuntime(jre, runtime);
      }
    }
    return export(project, os, target.os() + "-" + target.arch(), source, outParent);
  }

  /** Puts a Java runtime into the export's {@code runtime/} folder. */
  interface RuntimeSource {
    void into(Path runtime) throws IOException;
  }

  /**
   * Exports the project for the given OS. The runtime is linked from
   * {@code jmodsDir} (null = bundle the current JDK's runtime - the
   * no-jmods/JEP 493 fallback).
   */
  public Result export(ScratchProject project, Os os, Path jmodsDir, Path outParent)
      throws IOException {
    return export(project, os, os.name().toLowerCase(java.util.Locale.ROOT),
        localRuntimeSource(project, jmodsDir), outParent);
  }

  private RuntimeSource localRuntimeSource(ScratchProject project, Path jmodsDir) {
    return jmodsDir != null
        ? runtime -> RuntimeBuilder.link(jmodsDir, runtime,
            RuntimeBuilder.modulesFor(libraryJar(project)).toArray(new String[0]))
        : RuntimeBuilder::copyRuntime;
  }

  private Result export(ScratchProject project, Os os, String platform, RuntimeSource runtimeSource,
      Path outParent) throws IOException {
    String startStage = project.startStage();
    if (startStage.isEmpty()) {
      throw new IOException("The project has no Stage class to start");
    }
    Path libraryJar = libraryJar(project);
    Path appDir = outParent.resolve(project.name() + "-" + platform);
    if (Files.exists(appDir)) {
      deleteRecursively(appDir);
    }
    Path runtime = appDir.resolve("runtime");
    Path classes = appDir.resolve("app/classes");

    Files.createDirectories(classes.getParent());
    List<Path> sources = new ArrayList<>(project.javaSources());
    Path launcher = appDir.resolve("app/launcher/Scratch4JLauncher.java");
    Files.createDirectories(launcher.getParent());
    Files.writeString(launcher, LauncherSource.generate(project.settings()), StandardCharsets.UTF_8);
    sources.add(launcher);
    CompileResult result = compiler.compile(sources,
        List.copyOf(project.libs()), classes);
    if (!result.success()) {
      throw new IOException("The project does not compile: "
          + result.errors().get(0));
    }

    runtimeSource.into(runtime);

    Path libs = appDir.resolve("app/+libs");
    Files.createDirectories(libs);
    Files.copy(libraryJar, libs.resolve(libraryJar.getFileName()),
        StandardCopyOption.REPLACE_EXISTING);
    copyProjectFiles(project, appDir.resolve("app"));
    // one argument file for every launcher (java @argfile; paths relative to app/)
    Files.writeString(appDir.resolve("app/launch.args"), "--enable-native-access=ALL-UNNAMED\n"
        + "-cp \"classes" + (os == Os.WINDOWS ? ";" : ":") + "." + (os == Os.WINDOWS ? ";" : ":")
        + "+libs/*\"\nScratch4JLauncher\n" + startStage + "\n", StandardCharsets.UTF_8);
    Path icon = iconSource(project);
    String version = project.settings().appVersion == null ? "1.0" : project.settings().appVersion;
    Files.writeString(appDir.resolve("VERSION.txt"), project.name() + " " + version + "\n",
        StandardCharsets.UTF_8);
    Files.writeString(appDir.resolve("README.txt"), readme(project.name(), os),
        StandardCharsets.UTF_8);

    if (os == Os.LINUX) {
      Path run = appDir.resolve("run.sh");
      Files.writeString(run, """
          #!/bin/sh
          DIR=$(cd "$(dirname "$0")" && pwd)
          cd "$DIR/app"
          exec "$DIR/runtime/bin/java" --enable-native-access=ALL-UNNAMED \\
            -cp "$DIR/app/classes:$DIR/app:$DIR/app/+libs/*" \\
            Scratch4JLauncher %s
          """.formatted(startStage), StandardCharsets.UTF_8);
      run.toFile().setExecutable(true);
      if (icon != null) {
        AppIcon.writePng(icon, 256, appDir.resolve("icon.png"));
      }
      writeDesktopEntry(appDir, project, version, icon != null);
    } else if (os == Os.WINDOWS) {
      Path run = appDir.resolve("run.bat");
      Files.writeString(run, """
          @echo off
          set DIR=%~dp0
          cd /d "%DIR%app"
          "%DIR%runtime\\bin\\java.exe" --enable-native-access=ALL-UNNAMED -cp "%DIR%app\\classes;%DIR%app;%DIR%app\\+libs\\*" Scratch4JLauncher START_CLASS
          """.replace("START_CLASS", startStage), StandardCharsets.UTF_8); // batch % is not a format
      if (icon != null) {
        AppIcon.writeIco(icon, appDir.resolve("icon.ico"));
      }
      // the double-clickable launcher (built by CI with mingw-w64; run.bat always works)
      try (var exe = StudentExport.class.getResourceAsStream("launcher/windows-x64.exe")) {
        if (exe != null) {
          Files.copy(exe, appDir.resolve(project.name() + ".exe"),
              StandardCopyOption.REPLACE_EXISTING);
        }
      }
    } else {
      Path run = appDir.resolve("run.command");
      Files.writeString(run, """
          #!/bin/sh
          DIR=$(cd "$(dirname "$0")" && pwd)
          cd "$DIR/app"
          exec "$DIR/runtime/bin/java" --enable-native-access=ALL-UNNAMED \\
            -cp "$DIR/app/classes:$DIR/app:$DIR/app/+libs/*" \\
            Scratch4JLauncher %s
          """.formatted(startStage), StandardCharsets.UTF_8);
      run.toFile().setExecutable(true);
      writeAppBundle(appDir, project.name(), startStage, version, icon);
    }

    // Linux and macOS keep the execute bits of the launchers in a tar.gz
    Path archive = os == Os.WINDOWS
        ? Zip.zipDirectoryAs(appDir, appDir.getFileName() + "/",
            outParent.resolve(appDir.getFileName() + ".zip"))
        : Tar.gzipDirectory(appDir, appDir.getFileName().toString(),
            outParent.resolve(appDir.getFileName() + ".tar.gz"));
    return new Result(appDir, archive, startStage);
  }

  private Path libraryJar(ScratchProject project) throws IOException {
    List<Path> libs = project.libs();
    if (!libs.isEmpty()) {
      return libs.get(0);
    }
    throw new IOException("The project has no library jar in +libs (expected "
        + "scratch-" + LibraryJarSource.SCRATCH_VERSION + "-all.jar)");
  }

  /** Folders and files that are IDE/build output, not part of the program. */
  static final java.util.Set<String> SKIPPED_DIRS = java.util.Set.of(".scratch4j",
      "+libs", "build", "target", "export", "screenshots", ".git", ".vscode", ".idea", "out");

  /**
   * Every file the program may load at run time — images, sounds, fonts,
   * maps, shaders, data — wherever it lives in the project (demos keep them
   * in sprites/, backdrops/ or next to the code), but no sources or IDE files.
   */
  private void copyProjectFiles(ScratchProject project, Path appDir) throws IOException {
    Path root = project.root();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        Path relative = root.relativize(file);
        if (SKIPPED_DIRS.contains(relative.getName(0).toString())) {
          continue;
        }
        String name = file.getFileName().toString();
        if (name.endsWith(".java") || name.endsWith(".class") || name.endsWith(".ctxt")
            || name.equals("package.bluej") || name.startsWith(".")) {
          continue;
        }
        Path target = appDir.resolve(relative.toString());
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  /**
   * Linux menu entry: a .desktop file needs absolute paths, so install.sh
   * writes it into ~/.local/share/applications for wherever the app was
   * unpacked; the file next to run.sh is the template.
   */
  private void writeDesktopEntry(Path appDir, ScratchProject project, String version,
      boolean icon) throws IOException {
    String id = project.name().replaceAll("[^A-Za-z0-9_-]", "");
    Files.writeString(appDir.resolve(project.name() + ".desktop"), """
        [Desktop Entry]
        Type=Application
        Name=%s
        Comment=Made with Scratch for Java
        Exec=DIR/run.sh
        Path=DIR/app
        %sTerminal=false
        Categories=Game;Education;
        X-AppVersion=%s
        """.formatted(project.name(), icon ? "Icon=DIR/icon.png\n" : "", version),
        StandardCharsets.UTF_8);
    Path install = appDir.resolve("install.sh");
    Files.writeString(install, """
        #!/bin/sh
        # Adds the program to the application menu (no admin rights needed).
        DIR=$(cd "$(dirname "$0")" && pwd)
        mkdir -p "$HOME/.local/share/applications"
        sed "s|DIR|$DIR|g" "$DIR/%s.desktop" > "$HOME/.local/share/applications/%s.desktop"
        echo "Added %s to the application menu."
        """.formatted(project.name(), id, project.name()), StandardCharsets.UTF_8);
    install.toFile().setExecutable(true);
  }

  /** The icon: the project's choice, else the splash logo, else the first costume image. */
  private static Path iconSource(ScratchProject project) throws IOException {
    for (String candidate : new String[] {project.settings().appIcon,
        project.settings().splashLogo}) {
      if (candidate != null && !candidate.isBlank()
          && Files.isRegularFile(project.root().resolve(candidate))) {
        return project.root().resolve(candidate);
      }
    }
    // the first image of the project (assets/images first, demos keep theirs elsewhere)
    try (Stream<Path> files = Files.walk(project.root())) {
      return files.filter(f -> f.getFileName().toString().matches("(?i).*\\.(png|jpg|jpeg)$"))
          .filter(f -> !SKIPPED_DIRS.contains(project.root().relativize(f).getName(0).toString()))
          .min(java.util.Comparator.comparing((Path f) -> !f.startsWith(
              project.root().resolve("assets/images"))).thenComparing(Path::toString))
          .orElse(null);
    }
  }

  /** How to start the app on each OS, including the unsigned-app warnings. */
  static String readme(String name, Os os) {
    return switch (os) {
      case WINDOWS -> """
          %1$s - made with Scratch for Java
          ==================================

          Start: double-click %1$s.exe (or run.bat).
          Keep the runtime and app folders next to it.

          Windows may say "Windows protected your PC" (SmartScreen), because the
          program is not signed by a company. Click "More info", then "Run anyway".
          This happens only the first time.
          """.formatted(name);
      case MACOS -> """
          %1$s - made with Scratch for Java
          ==================================

          Start: double-click %1$s.app (or run.command).
          Keep the runtime and app folders next to it.

          macOS blocks programs that are not signed by an Apple developer
          (Gatekeeper). The first time:
            1. Right-click (or Ctrl+click) %1$s.app and choose "Open".
            2. Click "Open" in the warning.
          If macOS only offers "Move to Trash": open System Settings >
          Privacy & Security and click "Open Anyway" next to the message about
          %1$s, or run in Terminal inside this folder:
            xattr -dr com.apple.quarantine .
          """.formatted(name);
      case LINUX -> """
          %1$s - made with Scratch for Java
          ==================================

          Start: ./run.sh  (or double-click it in your file manager)
          Menu entry: ./install.sh adds the program to your application menu.

          If run.sh does not start, make it executable: chmod +x run.sh
          """.formatted(name);
    };
  }

  private static String xml(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static void deleteRecursively(Path dir) throws IOException {
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(file);
      }
    }
  }

  private void writeAppBundle(Path appDir, String name, String startStage, String version,
      Path icon) throws IOException {
    Path contents = appDir.resolve(name + ".app/Contents");
    Path macos = contents.resolve("MacOS");
    Files.createDirectories(macos);
    Path run = macos.resolve(name);
    // Contents/MacOS -> Contents -> Name.app -> the export folder with runtime/ and app/
    Files.writeString(run, "#!/bin/sh\nDIR=$(cd \"$(dirname \"$0\")/../../..\" && pwd)\n"
        + "cd \"$DIR/app\"\n"
        + "exec \"$DIR/runtime/bin/java\" --enable-native-access=ALL-UNNAMED -cp \"$DIR/app/classes:$DIR/app:$DIR/app/+libs/*\" "
        + "Scratch4JLauncher " + startStage + "\n", StandardCharsets.UTF_8);
    run.toFile().setExecutable(true);
    Files.writeString(contents.resolve("Info.plist"), """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
          <key>CFBundleExecutable</key>
          <string>%s</string>
          <key>CFBundleIdentifier</key>
          <string>org.openpatch.scratch4j.%s</string>
          <key>CFBundleName</key>
          <string>%s</string>
          <key>CFBundlePackageType</key>
          <string>APPL</string>
          <key>CFBundleShortVersionString</key>
          <string>%s</string>
          <key>CFBundleVersion</key>
          <string>%s</string>
          <key>CFBundleIconFile</key>
          <string>icon</string>
        </dict>
        </plist>
        """.formatted(name, name.replaceAll("\\W", ""), name, xml(version), xml(version)),
        StandardCharsets.UTF_8);
    if (icon != null) {
      AppIcon.writeIcns(icon, contents.resolve("Resources/icon.icns"));
    }
  }
}
