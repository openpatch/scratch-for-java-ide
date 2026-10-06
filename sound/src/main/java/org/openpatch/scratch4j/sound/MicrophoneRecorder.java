package org.openpatch.scratch4j.sound;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/** Records from the microphone until stop(). */
public final class MicrophoneRecorder {

  private TargetDataLine line;
  private Thread worker;
  private final java.util.concurrent.atomic.AtomicBoolean recording =
      new java.util.concurrent.atomic.AtomicBoolean();
  private volatile SoundClip recorded;

  /** Starts recording at the given sample rate; false when no microphone. */
  public boolean start(float sampleRate) {
    stop();
    try {
      AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
      line = AudioSystem.getTargetDataLine(format);
      line.open(format);
      line.start();
      recording.set(true);
      java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
      worker = new Thread(() -> {
        byte[] chunk = new byte[4096];
        try {
          while (recording.get()) {
            int read = line.read(chunk, 0, chunk.length);
            buffer.write(chunk, 0, read);
          }
        } catch (Exception ignored) {
          // device gone: keep what we have
        }
        byte[] bytes = buffer.toByteArray();
        int frames = bytes.length / 2;
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i++) {
          int value = (short) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] << 8));
          samples[i] = value / 32768f;
        }
        recorded = new SoundClip(new float[][] {samples}, sampleRate);
      }, "scratch4j-sound-record");
      worker.setDaemon(true);
      worker.start();
      return true;
    } catch (LineUnavailableException | IllegalArgumentException e) {
      recording.set(false);
      return false;
    }
  }

  /** Stops and returns the recording (null when nothing was captured). */
  public SoundClip stop() {
    recording.set(false);
    Thread w = worker;
    if (w != null) {
      try {
        w.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      worker = null;
    }
    TargetDataLine l = line;
    if (l != null) {
      l.stop();
      l.close();
      line = null;
    }
    SoundClip result = recorded;
    recorded = null;
    return result;
  }

  public boolean isRecording() {
    return recording.get();
  }
}
