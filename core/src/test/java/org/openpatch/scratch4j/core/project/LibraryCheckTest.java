package org.openpatch.scratch4j.core.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryCheckTest {

  @TempDir
  Path root;

  private ScratchProject project(String... jars) throws Exception {
    Files.createDirectories(root.resolve("+libs"));
    for (String jar : jars) {
      Files.writeString(root.resolve("+libs").resolve(jar), jar);
    }
    Files.writeString(root.resolve("MyStage.java"), "class MyStage {}");
    return ScratchProject.open(root);
  }

  @Test
  void classifiesTheJarInLibs() throws Exception {
    assertThat(LibraryCheck.status(project(), "5.5.0").state())
        .isEqualTo(LibraryCheck.State.MISSING);
    assertThat(LibraryCheck.status(project("scratch-4.22.0-all.jar"), "5.5.0").state())
        .isEqualTo(LibraryCheck.State.OUTDATED);
    Files.delete(root.resolve("+libs/scratch-4.22.0-all.jar"));
    assertThat(LibraryCheck.status(project("scratch-5.10.0-all.jar"), "5.9.0").state())
        .isEqualTo(LibraryCheck.State.NEWER);
    Files.delete(root.resolve("+libs/scratch-5.10.0-all.jar"));
    var nrw = LibraryCheck.status(project("scratch-5.5.0-nrw-all.jar"), "5.5.0");
    assertThat(nrw.state()).isEqualTo(LibraryCheck.State.OTHER_FLAVOUR);
    assertThat(nrw.flavour()).isEqualTo(LibraryFlavour.NRW);
  }

  @Test
  void swapOnlyWithConsentAndAPinStopsTheOffer() throws Exception {
    ScratchProject project = project("scratch-4.22.0-all.jar");
    var status = LibraryCheck.status(project, "5.5.0");
    assertThat(status.offerSwap(project.settings())).isTrue();
    LibraryCheck.pin(project, status);
    ScratchProject reopened = ScratchProject.open(root);
    assertThat(reopened.settings().libraryPin).isEqualTo("4.22.0");
    assertThat(LibraryCheck.status(reopened, "5.5.0").offerSwap(reopened.settings())).isFalse();

    Path bundled = Files.writeString(root.resolve("scratch-5.5.0-all.jar"), "new");
    LibraryCheck.install(reopened, bundled);
    assertThat(root.resolve("+libs/scratch-5.5.0-all.jar")).isRegularFile();
    assertThat(root.resolve("+libs/scratch-4.22.0-all.jar")).doesNotExist();
    assertThat(Files.list(root.resolve(".scratch4j/trash"))).hasSize(1);
    assertThat(LibraryCheck.status(ScratchProject.open(root), "5.5.0").state())
        .isEqualTo(LibraryCheck.State.CURRENT);
  }

  @Test
  void nrwProjectsNeedListJavaFromTheAbiturklassen() throws Exception {
    ScratchProject project = project("scratch-5.5.0-nrw-all.jar");
    project.settings().flavour = LibraryFlavour.NRW.id();
    assertThat(LibraryCheck.nrwListMissing(project)).isTrue();
    assertThat(Abiturklassen.install(project, java.util.Map.of("List", "public class List<T> {}"),
        java.util.List.of(Abiturklassen.LIST))).hasSize(1);
    assertThat(LibraryCheck.nrwListMissing(ScratchProject.open(root))).isFalse();
    assertThat(LibraryCheck.status(project, "5.5.0").state())
        .isEqualTo(LibraryCheck.State.CURRENT);
  }
}
