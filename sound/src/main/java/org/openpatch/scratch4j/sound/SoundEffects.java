package org.openpatch.scratch4j.sound;

/**
 * The sound editor's operations. Every effect takes a clip (or a clip and a
 * selection, samples inclusive) and returns a new one — the editor's undo
 * stack is just a stack of clips.
 */
public final class SoundEffects {

  private SoundEffects() {}

  /** start/end inclusive sample indices. */
  public record Selection(int start, int end) {

    public Selection {
      if (start < 0 || end < start) {
        throw new IllegalArgumentException("bad selection: " + start + ".." + end);
      }
    }

    public int length() {
      return end - start + 1;
    }
  }

  /** Extracts the selection as a new clip. */
  public static SoundClip copy(SoundClip clip, Selection selection) {
    return slice(clip, selection.start(), selection.end());
  }

  /** Removes the selection. */
  public static SoundClip delete(SoundClip clip, Selection selection) {
    return splice(clip, selection.start(), selection.end(), new SoundClip[0]);
  }

  /** Removes the selection (cut = copy + delete). */
  public static SoundClip cut(SoundClip clip, Selection selection) {
    return delete(clip, selection);
  }

  /** Inserts {@code insert} at the given sample index. */
  public static SoundClip paste(SoundClip clip, int at, SoundClip insert) {
    return splice(clip, at, at - 1, new SoundClip[] {insert});
  }

  /** Keeps only the selection. */
  public static SoundClip trim(SoundClip clip, Selection selection) {
    return slice(clip, selection.start(), selection.end());
  }

  /** Inserts silence at the given sample index. */
  public static SoundClip insertSilence(SoundClip clip, int at, double seconds) {
    int samples = Math.max(0, (int) Math.round(seconds * clip.sampleRate()));
    SoundClip silence = SoundClip.silence(clip.channelCount(), samples,
        clip.sampleRate());
    return splice(clip, at, at - 1, new SoundClip[] {silence});
  }

  /** Linear fade in over the selection (or the whole clip when null). */
  public static SoundClip fadeIn(SoundClip clip, Selection selection) {
    return fade(clip, selection, true);
  }

  /** Linear fade out over the selection (or the whole clip when null). */
  public static SoundClip fadeOut(SoundClip clip, Selection selection) {
    return fade(clip, selection, false);
  }

  private static SoundClip fade(SoundClip clip, Selection selection, boolean in) {
    Selection range = selection == null
        ? new Selection(0, clip.sampleCount() - 1) : selection;
    SoundClip out = clip.copy();
    for (int ch = 0; ch < out.channelCount(); ch++) {
      for (int i = range.start(); i <= Math.min(range.end(), out.sampleCount() - 1); i++) {
        double p = (i - range.start()) / (double) Math.max(1, range.length() - 1);
        double factor = in ? p : 1 - p;
        out.channel(ch)[i] = clip.sample(ch, i) * (float) factor;
      }
    }
    return out;
  }

  /** Louder (+) or softer (-): scales by a factor (2 = twice as loud). */
  public static SoundClip volume(SoundClip clip, Selection selection, double factor) {
    Selection range = selection == null
        ? new Selection(0, clip.sampleCount() - 1) : selection;
    SoundClip out = clip.copy();
    for (int ch = 0; ch < out.channelCount(); ch++) {
      for (int i = range.start(); i <= Math.min(range.end(), out.sampleCount() - 1); i++) {
        out.channel(ch)[i] = clamp(clip.sample(ch, i) * (float) factor);
      }
    }
    return out;
  }

  /** Scales so the peak reaches -0.1 dB. */
  public static SoundClip normalize(SoundClip clip, Selection selection) {
    Selection range = selection == null
        ? new Selection(0, clip.sampleCount() - 1) : selection;
    float peak = 0;
    for (int ch = 0; ch < clip.channelCount(); ch++) {
      for (int i = range.start(); i <= Math.min(range.end(), clip.sampleCount() - 1); i++) {
        peak = Math.max(peak, Math.abs(clip.sample(ch, i)));
      }
    }
    if (peak <= 0.00001f) {
      return clip.copy();
    }
    float factor = 0.988f / peak; // ~ -0.1 dB
    SoundClip out = clip.copy();
    for (int ch = 0; ch < out.channelCount(); ch++) {
      for (int i = range.start(); i <= Math.min(range.end(), out.sampleCount() - 1); i++) {
        out.channel(ch)[i] = clamp(clip.sample(ch, i) * factor);
      }
    }
    return out;
  }

  /**
   * Scratch's "echo": the sound repeats every 250 ms, each time half as loud
   * (a feedback delay). On the whole clip the end grows by the echo's tail.
   */
  public static SoundClip echo(SoundClip clip, Selection selection) {
    return comb(clip, selection, 0.25, 0.5, 1.0, false);
  }

  /**
   * Scratch's "robot": a very short feedback delay (1/120 s) that rings at
   * 120 Hz and makes a voice metallic; scaled back so it does not clip.
   */
  public static SoundClip robot(SoundClip clip, Selection selection) {
    return comb(clip, selection, 1 / 120.0, 0.9, 0, true);
  }

  /** y[n] = x[n] + feedback * y[n - delay], over the selection (and a tail). */
  private static SoundClip comb(SoundClip clip, Selection selection, double delaySeconds,
      double feedback, double tailSeconds, boolean keepPeak) {
    boolean whole = selection == null
        || (selection.start() <= 0 && selection.end() >= clip.sampleCount() - 1);
    Selection range = selection == null
        ? new Selection(0, clip.sampleCount() - 1) : selection;
    int delay = Math.max(1, (int) Math.round(delaySeconds * clip.sampleRate()));
    int tail = whole ? (int) Math.round(tailSeconds * clip.sampleRate()) : 0;
    int length = clip.sampleCount() + tail;
    float[][] channels = new float[clip.channelCount()][length];
    for (int ch = 0; ch < channels.length; ch++) {
      float[] out = channels[ch];
      System.arraycopy(clip.channel(ch), 0, out, 0, clip.sampleCount());
      int end = Math.min(range.end(), clip.sampleCount() - 1) + tail;
      float before = 0;
      float after = 0;
      for (int i = range.start(); i <= end; i++) {
        before = Math.max(before, Math.abs(i < clip.sampleCount() ? clip.sample(ch, i) : 0));
      }
      for (int i = range.start(); i <= end; i++) {
        float dry = i < clip.sampleCount() ? clip.sample(ch, i) : 0;
        float wet = i - delay >= range.start() ? out[i - delay] : 0;
        out[i] = dry + (float) feedback * wet;
        after = Math.max(after, Math.abs(out[i]));
      }
      float scale = keepPeak && after > 0 ? Math.max(before, 0.001f) / after
          : after > 1 ? 1 / after : 1;
      for (int i = range.start(); i <= end; i++) {
        out[i] = clamp(out[i] * scale);
      }
    }
    return new SoundClip(channels, clip.sampleRate());
  }

  /** Reverses the selection (or the whole clip when null). */
  public static SoundClip reverse(SoundClip clip, Selection selection) {
    Selection range = selection == null
        ? new Selection(0, clip.sampleCount() - 1) : selection;
    SoundClip out = clip.copy();
    for (int ch = 0; ch < out.channelCount(); ch++) {
      for (int i = range.start(); i <= Math.min(range.end(), out.sampleCount() - 1); i++) {
        out.channel(ch)[i] = clip.sample(ch, range.end() - (i - range.start()));
      }
    }
    return out;
  }

  /**
   * Faster/slower without changing the pitch perception of a resample: plays
   * the samples at a different rate (factor 2 = twice as fast = half as long).
   */
  public static SoundClip speed(SoundClip clip, double factor) {
    if (factor <= 0) {
      throw new IllegalArgumentException("factor must be positive");
    }
    int newCount = Math.max(1, (int) Math.round(clip.sampleCount() / factor));
    float[][] out = new float[clip.channelCount()][newCount];
    for (int ch = 0; ch < clip.channelCount(); ch++) {
      for (int i = 0; i < newCount; i++) {
        double position = i * factor;
        int i0 = (int) Math.floor(position);
        int i1 = Math.min(clip.sampleCount() - 1, i0 + 1);
        double frac = position - i0;
        out[ch][i] = clip.sample(ch, i0) * (float) (1 - frac)
            + clip.sample(ch, i1) * (float) frac;
      }
    }
    return new SoundClip(out, clip.sampleRate());
  }

  private static SoundClip slice(SoundClip clip, int start, int end) {
    end = Math.min(end, clip.sampleCount() - 1);
    int samples = Math.max(0, end - start + 1);
    float[][] out = new float[clip.channelCount()][samples];
    for (int ch = 0; ch < clip.channelCount(); ch++) {
      System.arraycopy(clip.channel(ch), start, out[ch], 0, samples);
    }
    return new SoundClip(out, clip.sampleRate());
  }

  /**
   * Removes [start..end] (inclusive; an empty range inserts between
   * start-1 and start) and inserts the given clips at that position.
   */
  private static SoundClip splice(SoundClip clip, int start, int end, SoundClip[] inserts) {
    start = Math.max(0, start);
    end = Math.min(end, clip.sampleCount() - 1);
    int insertSamples = 0;
    for (SoundClip insert : inserts) {
      insertSamples += insert.sampleCount();
    }
    int removed = Math.max(0, end - start + 1);
    int newCount = clip.sampleCount() - removed + insertSamples;
    float[][] out = new float[clip.channelCount()][newCount];
    for (int ch = 0; ch < clip.channelCount(); ch++) {
      int dest = 0;
      System.arraycopy(clip.channel(ch), 0, out[ch], dest, start);
      dest += start;
      for (SoundClip insert : inserts) {
        System.arraycopy(insert.channel(ch), 0, out[ch], dest, insert.sampleCount());
        dest += insert.sampleCount();
      }
      int tail = clip.sampleCount() - (end + 1);
      if (tail > 0) {
        System.arraycopy(clip.channel(ch), end + 1, out[ch], dest, tail);
      }
    }
    return new SoundClip(out, clip.sampleRate());
  }

  private static float clamp(float value) {
    return Math.max(-1f, Math.min(1f, value));
  }
}
