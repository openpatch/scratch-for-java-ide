package org.openpatch.scratch4j.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A freeze of the window is written down with what the FX thread was doing
 * (needs a display — {@code xvfb-run -a} — and {@code -Dscratch4j.uismoke=true}).
 */
@EnabledIfSystemProperty(named = "scratch4j.uismoke", matches = "true")
class LagRecorderIT {

  @TempDir
  Path tmp;

  @Test
  void aFreezeIsLoggedWithItsStack() throws Exception {
    LayoutIT.startFx();
    Path log = tmp.resolve("lag.log");
    AtomicReference<Thread> watcher = new AtomicReference<>();
    LayoutIT.onFx(() -> watcher.set(LagRecorder.watch(log)));
    LayoutIT.settle();
    try {
      LayoutIT.onFx(LagRecorderIT::freeze);
      LayoutIT.settle();
      String written = Files.readString(log);
      assertThat(written).contains("frozen").contains("LagRecorderIT.freeze");
    } finally {
      watcher.get().interrupt();
    }
  }

  private static void freeze() throws InterruptedException {
    Thread.sleep(800);
  }
}
