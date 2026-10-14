package org.openpatch.scratch4j.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RunHandleTest {

  /** A finished program that printed {@code stderr}. */
  private static Process printed(String stderr) {
    return new Process() {
      private final InputStream out = InputStream.nullInputStream();
      private final InputStream err =
          new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));

      @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
      @Override public InputStream getInputStream() { return out; }
      @Override public InputStream getErrorStream() { return err; }
      @Override public int waitFor() { return 0; }
      @Override public int exitValue() { return 0; }
      @Override public void destroy() {}
    };
  }

  @Test
  void aLineTheIdeCannotHandleDoesNotEndTheReading() throws Exception {
    List<String> lines = new CopyOnWriteArrayList<>();
    AtomicLong frames = new AtomicLong(-2);
    RunListener listener = new RunListener() {
      @Override public void onStderr(String line) {
        if (line.equals("unexpected")) {
          throw new IllegalStateException("cannot handle " + line);
        }
        lines.add(line);
      }

      @Override public void onFrames(long f) {
        frames.set(f);
      }
    };
    RunHandle.pump(printed("before\nunexpected\nafter\n" + RunHandle.CONTROL_MARK + "frames 42\n"),
        listener, new CompletableFuture<>());

    long end = System.currentTimeMillis() + 5000;
    while (frames.get() != 42 && System.currentTimeMillis() < end) {
      Thread.sleep(20);
    }
    // the program's later output, the heartbeat included, still arrives
    assertThat(lines).containsExactly("before", "after");
    assertThat(frames.get()).isEqualTo(42);
  }
}
