package org.openpatch.scratch4j.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.openpatch.scratch4j.core.project.BundledTemplates;
import org.openpatch.scratch4j.core.project.NewProject;
import org.openpatch.scratch4j.core.project.ScratchProject;
import org.openpatch.scratch4j.runner.LibraryJarSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Cross-OS exports from admin-seeded runtimes (offline) and the tar.gz format. */
class CrossPlatformExportTest {

  @TempDir
  Path tmp;

  private static final String FEATURE = String.valueOf(Runtime.version().feature());

  private ScratchProject catDemo() throws Exception {
    Path jar = NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class);
    return ScratchProject.open(BundledTemplates.create("demo-cat", tmp, "cat", jar));
  }

  @Test
  void cliNamesDemoExportsWithoutTheTemplateIdPrefix() throws Exception {
    Path cache = Files.createDirectories(tmp.resolve("cache"));
    Files.copy(NewProject.classpathJar(org.openpatch.scratch.internal.BuiltinAssets.class),
        cache.resolve("scratch-" + LibraryJarSource.SCRATCH_VERSION + "-all.jar"));
    seedWindowsJre(cache);

    Path out = tmp.resolve("out");
    ExportCli.main(new String[] {"template:demo-cat", out.toString(), cache.toString(),
        "windows-x64"});

    assertThat(out.resolve("cat-windows-x64.zip")).isRegularFile();
    assertThat(Zip.readEntry(out.resolve("cat-windows-x64.zip"),
        "cat-windows-x64/run.bat")).isNotNull();
  }

  @Test
  void windowsExportFromASeededJre() throws Exception {
    Path cache = Files.createDirectories(tmp.resolve("cache"));
    seedWindowsJre(cache);
    var result = new StudentExport().export(catDemo(),
        JmodsFetcher.WINDOWS_X64, tmp.resolve("out"), cache);
    Path app = result.appDir();
    assertThat(app.getFileName().toString()).isEqualTo("cat-windows-x64");
    assertThat(app.resolve("runtime/bin/java.exe")).isRegularFile();
    assertThat(app.resolve("run.bat")).content().contains("java.exe")
        .contains("Scratch4JLauncher CatSketch");
    // a demo keeps its costume in sprites/, not assets/: it is exported too
    assertThat(app.resolve("app/sprites/cat.png")).isRegularFile();
    assertThat(app.resolve("app/CatSketch.java")).doesNotExist();
    assertThat(app.resolve("app/classes/CatSketch.class")).isRegularFile();
    assertThat(result.archive().getFileName().toString()).isEqualTo("cat-windows-x64.zip");
    assertThat(Zip.readEntry(result.archive(), "cat-windows-x64/run.bat")).isNotNull();
    assertThat(app.resolve("app/launch.args")).content()
        .contains("-cp \"classes;.;+libs/*\"").contains("Scratch4JLauncher\nCatSketch");
    assertThat(app.resolve("README.txt")).content().contains("More info").contains("Run anyway");
    assertThat(app.resolve("VERSION.txt")).content().isEqualTo("cat 1.0\n");
    byte[] ico = Files.readAllBytes(app.resolve("icon.ico"));
    assertThat(ico[2]).as("ICO type").isEqualTo((byte) 1);
  }

  private void seedWindowsJre(Path cache) throws Exception {
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(
        cache.resolve("OpenJDK" + FEATURE + "U-jre_x64_windows_hotspot_seeded.zip")))) {
      for (String name : new String[] {"jdk-jre/bin/java.exe", "jdk-jre/lib/modules"}) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(name.getBytes());
        zip.closeEntry();
      }
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void macExportFromASeededBundleKeepsLaunchersExecutable() throws Exception {
    Path cache = Files.createDirectories(tmp.resolve("cache"));
    Path fake = Files.createDirectories(tmp.resolve("fake/jdk-jre/Contents/Home/bin"));
    Files.writeString(fake.resolve("java"), "#!/bin/sh\n");
    fake.resolve("java").toFile().setExecutable(true);
    Tar.gzipDirectory(tmp.resolve("fake/jdk-jre"), "jdk-jre",
        cache.resolve("OpenJDK" + FEATURE + "U-jre_aarch64_mac_hotspot_seeded.tar.gz"));

    var result = new StudentExport().export(catDemo(),
        JmodsFetcher.MACOS_ARM64, tmp.resolve("out"), cache);
    Path app = result.appDir();
    assertThat(app.resolve("runtime/bin/java")).isRegularFile();
    assertThat(app.resolve("cat.app/Contents/MacOS/cat")).content()
        .contains("/../../..").contains("$DIR/runtime/bin/java");
    assertThat(result.archive().getFileName().toString()).isEqualTo("cat-mac-aarch64.tar.gz");
    assertThat(app.resolve("README.txt")).content().contains("Gatekeeper")
        .contains("xattr -dr com.apple.quarantine");
    assertThat(app.resolve("cat.app/Contents/Info.plist")).content()
        .contains("<key>CFBundleShortVersionString</key>").contains("<string>icon</string>");
    byte[] icns = Files.readAllBytes(app.resolve("cat.app/Contents/Resources/icon.icns"));
    assertThat(new String(icns, 0, 4)).isEqualTo("icns");
    assertThat(java.nio.ByteBuffer.wrap(icns, 4, 4).getInt()).isEqualTo(icns.length);

    Path unpacked = Files.createDirectories(tmp.resolve("unpacked"));
    Process tar = new ProcessBuilder("tar", "-xzf", result.archive().toString(),
        "-C", unpacked.toString()).inheritIO().start();
    assertThat(tar.waitFor()).isZero();
    Path root = unpacked.resolve("cat-mac-aarch64");
    assertThat(Files.isExecutable(root.resolve("run.command"))).isTrue();
    assertThat(Files.isExecutable(root.resolve("cat.app/Contents/MacOS/cat"))).isTrue();
    assertThat(Files.isExecutable(root.resolve("runtime/bin/java"))).isTrue();
    assertThat(Files.isExecutable(root.resolve("app/sprites/cat.png"))).isFalse();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void tarKeepsLongNames() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("long/" + "a".repeat(60) + "/" + "b".repeat(60)));
    Files.writeString(dir.resolve("c".repeat(80) + ".txt"), "hello");
    Path archive = Tar.gzipDirectory(tmp.resolve("long"), "long", tmp.resolve("long.tar.gz"));
    Path out = Files.createDirectories(tmp.resolve("x"));
    assertThat(new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", out.toString())
        .inheritIO().start().waitFor()).isZero();
    assertThat(out.resolve("long/" + "a".repeat(60) + "/" + "b".repeat(60) + "/"
        + "c".repeat(80) + ".txt")).content().isEqualTo("hello");
  }
}
