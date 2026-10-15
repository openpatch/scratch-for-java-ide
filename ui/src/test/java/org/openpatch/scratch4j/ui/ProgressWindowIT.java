package org.openpatch.scratch4j.ui;

import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window shown while a project is imported (needs a display:
 * {@code xvfb-run -a} and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class ProgressWindowIT {

  @Test
  void followsTheImportFromItsWorkerThreadAndCancelsIt() throws Exception {
    I18n.Language language = I18n.current();
    I18n.set(I18n.Language.EN);
    LayoutIT.startFx();
    AtomicReference<ProgressWindow> progress = new AtomicReference<>();
    try {
      LayoutIT.onFx(() -> {
        ProgressWindow window = new ProgressWindow(null, "Importing game.sb3...");
        window.show();
        var bar = (ProgressBar) window.window().getProperties().get("bar");
        var detail = (Label) window.window().getProperties().get("detail");
        assertThat(bar.isIndeterminate()).as("nothing counted yet").isTrue();
        assertThat(detail.getText()).isEqualTo("Reading the file...");
        progress.set(window);
      });

      Thread worker = new Thread(() -> {
        for (int i = 0; i < 4; i++) {
          progress.get().step("Cat: costume" + (i + 1), i, 4);
        }
      });
      worker.start();
      worker.join();

      LayoutIT.onFx(() -> {
        ProgressWindow window = progress.get();
        var bar = (ProgressBar) window.window().getProperties().get("bar");
        var detail = (Label) window.window().getProperties().get("detail");
        assertThat(bar.getProgress()).isEqualTo(0.75);
        assertThat(detail.getText()).isEqualTo("4 of 4: Cat: costume4");
        UiSmokeIT.snapshot(window.window().getScene().getRoot(),
            Path.of("target/progress-window.png"));

        // closing the window cancels: it stays until the import has stopped
        window.window().fireEvent(new WindowEvent(window.window(),
            WindowEvent.WINDOW_CLOSE_REQUEST));
        assertThat(window.window().isShowing()).as("the import is still running").isTrue();
        assertThat(window.cancelled()).isTrue();
        assertThat(detail.getText()).isEqualTo("Stopping the import...");
      });

      // the import's next step ends it
      AtomicReference<Throwable> thrown = new AtomicReference<>();
      Thread stopped = new Thread(() -> {
        try {
          progress.get().step("Cat: costume5", 4, 5);
        } catch (Throwable t) {
          thrown.set(t);
        }
      });
      stopped.start();
      stopped.join();
      assertThat(thrown.get()).isInstanceOf(ProgressWindow.Cancelled.class);

      LayoutIT.onFx(() -> {
        ProgressWindow window = progress.get();
        window.close();
        assertThat(window.window().isShowing()).isFalse();
      });
    } finally {
      I18n.set(language);
      if (progress.get() != null) {
        LayoutIT.onFx(() -> progress.get().close());
      }
    }
  }
}
