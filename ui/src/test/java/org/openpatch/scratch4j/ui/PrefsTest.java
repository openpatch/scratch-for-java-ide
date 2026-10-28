package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PrefsTest {

  @TempDir
  Path install;

  @Test
  void portableMarkerInTheInstallFolderMovesSettingsThere() throws Exception {
    Path appDir = Files.createDirectories(install.resolve("lib/app"));
    String previous = System.getProperty("scratch4j.appDir");
    // the build points tests at a settings folder of their own
    String settings = System.getProperty("scratch4j.settingsDir");
    try {
      System.clearProperty("scratch4j.settingsDir");
      System.setProperty("scratch4j.appDir", appDir.toString());
      assertThat(Prefs.portableDir()).isNull();
      Files.writeString(install.resolve("portable"), "");
      assertThat(Prefs.portableDir()).isEqualTo(install.normalize());
    } finally {
      if (settings != null) System.setProperty("scratch4j.settingsDir", settings);
      if (previous == null) {
        System.clearProperty("scratch4j.appDir");
      } else {
        System.setProperty("scratch4j.appDir", previous);
      }
    }
  }
}
