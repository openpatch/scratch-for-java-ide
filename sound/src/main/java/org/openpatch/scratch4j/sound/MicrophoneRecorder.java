package org.openpatch.scratch4j.sound;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * Records from a microphone until stop(). While recording, {@link #peaks()}
 * gives the loudness so far (one peak per hundredth of a second) for a live
 * waveform.
 *
 * <p>On Linux the sound server (PipeWire or PulseAudio) usually holds the
 * microphones, so Java Sound only reaches its default one. When {@code pactl}
 * and {@code parecord} are installed, the server's microphones are offered as
 * well and recorded through {@code parecord}.
 */
public final class MicrophoneRecorder {

  /** Peaks per second in {@link #peaks()}. */
  public static final int PEAKS_PER_SECOND = 100;

  /** A device to record from: {@code id} for {@link #start}, {@code name} to show. */
  public record Device(String id, String name) {}

  private static final String PULSE = "pulse:";

  private Runnable closeSource;
  private Thread worker;
  private final java.util.concurrent.atomic.AtomicBoolean recording =
      new java.util.concurrent.atomic.AtomicBoolean();
  private volatile SoundClip recorded;
  // guarded by itself: the worker appends, the editor reads while drawing
  private final List<Float> peaks = new ArrayList<>();

  /** The devices that can record mono 16-bit sound at this rate. */
  public static List<Device> devices(float sampleRate) {
    List<Device> devices = new ArrayList<>(soundServerDevices());
    DataLine.Info info = new DataLine.Info(TargetDataLine.class, format(sampleRate));
    for (Mixer.Info mixer : AudioSystem.getMixerInfo()) {
      // "[default]" is the default microphone, which is offered anyway
      if (mixer.getName().endsWith("[default]")) {
        continue;
      }
      try {
        if (AudioSystem.getMixer(mixer).isLineSupported(info)) {
          devices.add(new Device(mixer.getName(), mixer.getName()));
        }
      } catch (RuntimeException ignored) {
        // a device that fails to answer is not offered
      }
    }
    return devices;
  }

  /** The sound server's microphones (not the monitors of its outputs); none without pactl. */
  private static List<Device> soundServerDevices() {
    if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
      return List.of();
    }
    try {
      ProcessBuilder builder = new ProcessBuilder("pactl", "list", "sources");
      builder.environment().put("LC_ALL", "C"); // English field names
      builder.redirectErrorStream(true);
      Process process = builder.start();
      String output = new String(process.getInputStream().readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        return List.of();
      }
      return parseSources(output);
    } catch (IOException e) {
      return List.of(); // no pactl
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    }
  }

  /** The microphones in the output of {@code LC_ALL=C pactl list sources}. */
  static List<Device> parseSources(String output) {
    List<Device> devices = new ArrayList<>();
    String name = null;
    for (String line : output.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("Name: ")) {
        name = trimmed.substring(6).trim();
      } else if (trimmed.startsWith("Description: ") && name != null) {
        if (!name.endsWith(".monitor")) {
          devices.add(new Device(PULSE + name, trimmed.substring(13).trim()));
        }
        name = null;
      }
    }
    return devices;
  }

  private static AudioFormat format(float sampleRate) {
    return new AudioFormat(sampleRate, 16, 1, true, false);
  }

  /** Starts recording from the default microphone; false when there is none. */
  public boolean start(float sampleRate) {
    return start(sampleRate, null);
  }

  /**
   * Starts recording from the device with this id (see {@link #devices}), or
   * the default one when null or no longer there; false when it cannot record.
   */
  public boolean start(float sampleRate, String device) {
    stop();
    InputStream source;
    try {
      source = device != null && device.startsWith(PULSE)
          ? openSoundServer(sampleRate, device.substring(PULSE.length()))
          : openLine(format(sampleRate), device);
    } catch (LineUnavailableException | IllegalArgumentException | SecurityException
        | IOException e) {
      return false;
    }
    recording.set(true);
    synchronized (peaks) {
      peaks.clear();
    }
    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
    int framesPerPeak = Math.max(1, Math.round(sampleRate / PEAKS_PER_SECOND));
    worker = new Thread(() -> {
      // one peak per chunk, so the live waveform grows as the sound comes in
      byte[] chunk = new byte[2 * framesPerPeak];
      try {
        while (recording.get()) {
          int read = source.readNBytes(chunk, 0, chunk.length);
          if (read <= 0) {
            break; // the device went away
          }
          buffer.write(chunk, 0, read);
          float peak = 0;
          for (int i = 0; i + 1 < read; i += 2) {
            peak = Math.max(peak, Math.abs(sampleAt(chunk, i)));
          }
          synchronized (peaks) {
            peaks.add(peak);
          }
        }
      } catch (Exception ignored) {
        // device gone: keep what we have
      }
      byte[] bytes = buffer.toByteArray();
      int frames = bytes.length / 2;
      float[] samples = new float[frames];
      for (int i = 0; i < frames; i++) {
        samples[i] = sampleAt(bytes, 2 * i);
      }
      recorded = new SoundClip(new float[][] {samples}, sampleRate);
    }, "scratch4j-sound-record");
    worker.setDaemon(true);
    worker.start();
    return true;
  }

  private InputStream openLine(AudioFormat format, String device)
      throws LineUnavailableException {
    TargetDataLine line = null;
    if (device != null) {
      DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
      for (Mixer.Info mixer : AudioSystem.getMixerInfo()) {
        if (mixer.getName().equals(device)) {
          line = (TargetDataLine) AudioSystem.getMixer(mixer).getLine(info);
        }
      }
    }
    if (line == null) {
      line = AudioSystem.getTargetDataLine(format);
    }
    line.open(format);
    line.start();
    TargetDataLine opened = line;
    closeSource = () -> {
      opened.stop();
      opened.close();
    };
    return new AudioInputStream(line);
  }

  private InputStream openSoundServer(float sampleRate, String source) throws IOException {
    Process process = new ProcessBuilder("parecord", "--raw", "--format=s16le",
        "--rate=" + Math.round(sampleRate), "--channels=1", "--latency-msec=20",
        "--device=" + source)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
    closeSource = process::destroy;
    // parecord stops at once when the device is gone or busy
    try {
      if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
        closeSource = null;
        throw new IOException("parecord failed: " + process.exitValue());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return process.getInputStream();
  }

  private static float sampleAt(byte[] bytes, int offset) {
    return (short) ((bytes[offset] & 0xFF) | (bytes[offset + 1] << 8)) / 32768f;
  }

  /** The loudness recorded so far, {@link #PEAKS_PER_SECOND} values (0..1) per second. */
  public float[] peaks() {
    synchronized (peaks) {
      float[] copy = new float[peaks.size()];
      for (int i = 0; i < copy.length; i++) {
        copy[i] = peaks.get(i);
      }
      return copy;
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
    Runnable close = closeSource;
    if (close != null) {
      close.run();
      closeSource = null;
    }
    SoundClip result = recorded;
    recorded = null;
    return result;
  }

  public boolean isRecording() {
    return recording.get();
  }
}
