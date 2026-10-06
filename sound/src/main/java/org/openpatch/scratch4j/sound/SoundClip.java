package org.openpatch.scratch4j.sound;

import java.util.Arrays;

/**
 * Decoded audio: PCM float samples in [-1, 1] per channel, plus the sample
 * rate. Immutable — every effect returns a new clip, which makes the sound
 * editor's undo a stack of clips.
 */
public final class SoundClip {

  private final float[][] channels;
  private final float sampleRate;

  public SoundClip(float[][] channels, float sampleRate) {
    if (channels.length == 0) {
      throw new IllegalArgumentException("no channels");
    }
    for (float[] channel : channels) {
      if (channel == null) {
        throw new IllegalArgumentException("null channel");
      }
    }
    this.channels = channels;
    this.sampleRate = sampleRate;
  }

  /** A silent clip. */
  public static SoundClip silence(int channelCount, int samples, float sampleRate) {
    float[][] channels = new float[channelCount][samples];
    return new SoundClip(channels, sampleRate);
  }

  public int channelCount() {
    return channels.length;
  }

  public int sampleCount() {
    return channels[0].length;
  }

  public float sampleRate() {
    return sampleRate;
  }

  /** The samples of a channel; do not modify (shared for speed). */
  public float[] channel(int index) {
    return channels[index];
  }

  public float sample(int channel, int index) {
    return channels[channel][index];
  }

  /** Duration in seconds. */
  public double duration() {
    return sampleCount() / (double) sampleRate;
  }

  /** Peak absolute sample value (0..1). */
  public float peak() {
    float peak = 0;
    for (float[] channel : channels) {
      for (float v : channel) {
        float abs = Math.abs(v);
        if (abs > peak) {
          peak = abs;
        }
      }
    }
    return peak;
  }

  /** The clip's samples as a copy, all channels flattened channel-by-channel. */
  public SoundClip copy() {
    float[][] copy = new float[channels.length][];
    for (int i = 0; i < channels.length; i++) {
      copy[i] = channels[i].clone();
    }
    return new SoundClip(copy, sampleRate);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SoundClip other)) {
      return false;
    }
    return sampleRate == other.sampleRate && Arrays.deepEquals(channels, other.channels);
  }

  @Override
  public int hashCode() {
    return Arrays.deepHashCode(channels) * 31 + Float.hashCode(sampleRate);
  }

  @Override
  public String toString() {
    return "SoundClip[" + channelCount() + "ch " + sampleCount()
        + " samples @" + (int) sampleRate + "Hz]";
  }
}
