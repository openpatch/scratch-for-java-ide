package org.openpatch.scratch4j.sound;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SoundPlayerTest {

  @Test
  void loopsOnlyTheSelectedFramesUntilStopped() throws Exception {
    CountDownLatch writes = new CountDownLatch(3);
    AtomicReference<byte[]> first = new AtomicReference<>();
    AtomicInteger completed = new AtomicInteger();
    SourceDataLine line = fakeLine((bytes, offset, length) -> {
      first.compareAndSet(null, Arrays.copyOfRange(bytes, offset, offset + length));
      writes.countDown();
      Thread.sleep(2);
      return length;
    });
    SoundPlayer player = new SoundPlayer(format -> line);
    SoundClip clip = new SoundClip(new float[][] {{0, 0.5f, -0.5f}}, 44100);

    assertThat(player.play(clip, new SoundEffects.Selection(1, 2), true,
        completed::incrementAndGet)).isTrue();
    assertThat(writes.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(player.isPlaying()).isTrue();
    player.stop();
    assertThat(player.isPlaying()).isFalse();
    assertThat(completed.get()).isZero();

    byte[] samples = first.get();
    assertThat(samples).hasSize(4096);
    // 0.5, -0.5, 0.5: the selection wraps without playing frame zero.
    assertThat(samples[0] & 0xff).isZero();
    assertThat(samples[1] & 0xff).isEqualTo(64);
    assertThat(samples[2] & 0xff).isEqualTo(1);
    assertThat(samples[3] & 0xff).isEqualTo(192);
    assertThat(samples[4] & 0xff).isZero();
    assertThat(samples[5] & 0xff).isEqualTo(64);
  }

  @Test
  void finishesNonLoopingSelectionAndCallsCompletion() throws Exception {
    CountDownLatch completed = new CountDownLatch(1);
    AtomicReference<byte[]> written = new AtomicReference<>();
    SourceDataLine line = fakeLine((bytes, offset, length) -> {
      written.set(Arrays.copyOfRange(bytes, offset, offset + length));
      return length;
    });
    SoundPlayer player = new SoundPlayer(format -> line);
    SoundClip clip = new SoundClip(new float[][] {{0, 0.5f, -0.5f}}, 44100);

    assertThat(player.play(clip, new SoundEffects.Selection(1, 2), false,
        completed::countDown)).isTrue();
    assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(player.isPlaying()).isFalse();
    assertThat(written.get()).hasSize(4);
  }

  private interface Writer {
    int write(byte[] bytes, int offset, int length) throws InterruptedException;
  }

  private static SourceDataLine fakeLine(Writer writer) {
    return (SourceDataLine) Proxy.newProxyInstance(SoundPlayerTest.class.getClassLoader(),
        new Class<?>[] {SourceDataLine.class}, (proxy, method, args) -> {
          if (method.getName().equals("write")) {
            return writer.write((byte[]) args[0], (int) args[1], (int) args[2]);
          }
          if (method.getReturnType() == boolean.class) {
            return true;
          }
          if (method.getReturnType() == int.class) {
            return 0;
          }
          if (method.getReturnType() == long.class) {
            return 0L;
          }
          return null;
        });
  }
}
