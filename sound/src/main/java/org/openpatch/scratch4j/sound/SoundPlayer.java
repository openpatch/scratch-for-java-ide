package org.openpatch.scratch4j.sound;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plays a clip (or a selection) on a background thread; stop() cancels. */
public final class SoundPlayer {

  @FunctionalInterface
  interface LineFactory {
    SourceDataLine line(AudioFormat format) throws LineUnavailableException;
  }

  private final LineFactory lineFactory;
  private Playback current;

  public SoundPlayer() {
    this(AudioSystem::getSourceDataLine);
  }

  SoundPlayer(LineFactory lineFactory) {
    this.lineFactory = lineFactory;
  }

  private static final class Playback {
    final AtomicBoolean running = new AtomicBoolean(true);
    final SourceDataLine line;
    Thread worker;

    Playback(SourceDataLine line) {
      this.line = line;
    }
  }

  /** Plays the whole clip; returns false when no audio line is available. */
  public boolean play(SoundClip clip) {
    return play(clip, null, null);
  }

  /**
   * Plays the clip; {@code selection} limits the range (whole clip when null).
   * {@code done} runs when playback finishes (or the audio line fails), but not
   * when playback is stopped or superseded by another session.
   *
   * @return false when no audio device is available
   */
  public boolean play(SoundClip clip, SoundEffects.Selection selection, Runnable done) {
    return play(clip, selection, false, done);
  }

  /** Plays a selection repeatedly when {@code loop} is true, until stopped. */
  public synchronized boolean play(SoundClip clip, SoundEffects.Selection selection,
                                   boolean loop, Runnable done) {
    stop();
    if (clip.sampleCount() == 0) {
      return false;
    }
    SoundEffects.Selection range = selection == null
        ? new SoundEffects.Selection(0, clip.sampleCount() - 1) : selection;
    if (range.start() < 0 || range.end() >= clip.sampleCount()
        || range.start() > range.end()) {
      throw new IllegalArgumentException("Invalid playback selection");
    }
    try {
      AudioFormat format = new AudioFormat(clip.sampleRate(), 16,
          clip.channelCount(), true, false);
      SourceDataLine line = lineFactory.line(format);
      line.open(format);
      line.start();
      Playback playback = new Playback(line);
      current = playback;
      playback.worker = new Thread(() -> {
        try {
          byte[] buffer = new byte[2048 * clip.channelCount() * 2];
          int frame = range.start();
          while (playback.running.get()) {
            int bytes = 0;
            while (bytes < buffer.length && playback.running.get()) {
              for (int ch = 0; ch < clip.channelCount(); ch++) {
                int value = Math.round(clip.sample(ch, frame) * 32767f);
                value = Math.max(-32768, Math.min(32767, value));
                buffer[bytes++] = (byte) value;
                buffer[bytes++] = (byte) (value >> 8);
              }
              frame++;
              if (frame > range.end()) {
                if (!loop) {
                  break;
                }
                frame = range.start();
              }
            }
            if (bytes > 0 && playback.running.get()) {
              line.write(buffer, 0, bytes);
            }
            if (!loop && frame > range.end()) {
              break;
            }
          }
          if (playback.running.get()) {
            line.drain();
          }
        } catch (Exception ignored) {
          // stopped mid-write is normal
        } finally {
          line.close();
          boolean wasCurrent;
          synchronized (SoundPlayer.this) {
            wasCurrent = current == playback;
            if (wasCurrent) {
              current = null;
            }
          }
          if (wasCurrent && done != null) {
            done.run();
          }
        }
      }, "scratch4j-sound-play");
      playback.worker.setDaemon(true);
      playback.worker.start();
      return true;
    } catch (LineUnavailableException | IllegalArgumentException e) {
      return false;
    }
  }

  public synchronized void stop() {
    Playback playback = current;
    current = null;
    if (playback != null) {
      playback.running.set(false);
      playback.line.stop();
      playback.line.flush();
      playback.line.close();
      playback.worker.interrupt();
    }
  }

  public synchronized boolean isPlaying() {
    return current != null && current.running.get();
  }
}
