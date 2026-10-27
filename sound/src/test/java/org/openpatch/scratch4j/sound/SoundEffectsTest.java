package org.openpatch.scratch4j.sound;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoundEffectsTest {

  private static SoundClip ramp(int samples) {
    float[][] channel = new float[1][samples];
    for (int i = 0; i < samples; i++) {
      channel[0][i] = i / (float) samples;
    }
    return new SoundClip(channel, 1000);
  }

  @Test
  void trimCutAndPasteUseInclusiveSampleBounds() {
    SoundClip clip = ramp(100); // samples 0..99 rising
    SoundClip middle = SoundEffects.trim(clip, new SoundEffects.Selection(10, 19));
    assertThat(middle.sampleCount()).isEqualTo(10);
    assertThat(middle.sample(0, 0)).isCloseTo(0.1f, org.assertj.core.data.Offset.offset(0.001f));

    SoundClip cut = SoundEffects.cut(clip, new SoundEffects.Selection(10, 19));
    assertThat(cut.sampleCount()).isEqualTo(90);
    assertThat(cut.sample(0, 10)).isCloseTo(0.2f, org.assertj.core.data.Offset.offset(0.001f));

    SoundClip pasted = SoundEffects.paste(cut, 10, middle);
    assertThat(pasted.sampleCount()).isEqualTo(100);
    assertThat(pasted).isEqualTo(clip);
  }

  @Test
  void deleteAndInsertSilence() {
    SoundClip clip = ramp(100);
    SoundClip deleted = SoundEffects.delete(clip, new SoundEffects.Selection(0, 49));
    assertThat(deleted.sampleCount()).isEqualTo(50);
    assertThat(deleted.sample(0, 0)).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.001f));

    SoundClip withSilence = SoundEffects.insertSilence(clip, 50, 0.5); // 500 samples @1kHz
    assertThat(withSilence.sampleCount()).isEqualTo(600);
    assertThat(withSilence.sample(0, 50)).isZero();
    assertThat(withSilence.sample(0, 550)).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.001f));
  }

  @Test
  void fadesGoZeroToOneLinearly() {
    SoundClip constant = new SoundClip(new float[][] {new float[100]}, 1000);
    java.util.Arrays.fill(constant.channel(0), 0.5f);

    SoundClip in = SoundEffects.fadeIn(constant, null);
    assertThat(in.sample(0, 0)).isZero();
    assertThat(in.sample(0, 99)).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.01f));

    SoundClip out = SoundEffects.fadeOut(constant, null);
    assertThat(out.sample(0, 0)).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.01f));
    assertThat(out.sample(0, 99)).isZero();
  }

  @Test
  void normalizeBringsThePeakToAboutMinusPointOneDb() {
    SoundClip quiet = SoundEffects.volume(ramp(100), null, 0.4); // peak 0.396
    SoundClip normalized = SoundEffects.normalize(quiet, null);
    assertThat(normalized.peak()).isCloseTo(0.988f, org.assertj.core.data.Offset.offset(0.01f));
    assertThat(normalized.sample(0, 50) / quiet.sample(0, 50)).isGreaterThan(2f);
  }

  @Test
  void reverseFlipsTheSelectionOnly() {
    SoundClip clip = ramp(10);
    SoundClip flipped = SoundEffects.reverse(clip, new SoundEffects.Selection(0, 9));
    assertThat(flipped.sample(0, 0)).isCloseTo(0.9f, org.assertj.core.data.Offset.offset(0.01f));
    assertThat(flipped.sample(0, 9)).isCloseTo(0.0f, org.assertj.core.data.Offset.offset(0.01f));

    SoundClip partial = SoundEffects.reverse(clip, new SoundEffects.Selection(0, 4));
    assertThat(partial.sample(0, 9)).isCloseTo(0.9f, org.assertj.core.data.Offset.offset(0.01f));
    assertThat(partial.sample(0, 0)).isCloseTo(0.4f, org.assertj.core.data.Offset.offset(0.01f));
  }

  @Test
  void speedShrinksOrGrowsTheClip() {
    SoundClip clip = ramp(1000);
    SoundClip faster = SoundEffects.speed(clip, 2);
    assertThat(faster.sampleCount()).isEqualTo(500);
    assertThat(faster.sampleRate()).isEqualTo(1000);

    SoundClip slower = SoundEffects.speed(clip, 0.5);
    assertThat(slower.sampleCount()).isEqualTo(2000);
  }

  private static SoundClip sine(double hz, double seconds, float rate) {
    float[][] channel = new float[1][(int) (seconds * rate)];
    for (int i = 0; i < channel[0].length; i++) {
      channel[0][i] = (float) (0.5 * Math.sin(2 * Math.PI * hz * i / rate));
    }
    return new SoundClip(channel, rate);
  }

  /** Upward zero crossings per second. */
  private static double frequency(SoundClip clip) {
    int crossings = 0;
    for (int i = 1; i < clip.sampleCount(); i++) {
      if (clip.sample(0, i - 1) < 0 && clip.sample(0, i) >= 0) crossings++;
    }
    return crossings / clip.duration();
  }

  @Test
  void speedKeepsThePitch() {
    SoundClip tone = sine(440, 1, 44100);
    SoundClip faster = SoundEffects.speed(tone, 1.25);
    assertThat(faster.duration()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.001));
    assertThat(frequency(faster)).isCloseTo(440, org.assertj.core.data.Offset.offset(10.0));
    SoundClip slower = SoundEffects.speed(tone, 0.8);
    assertThat(slower.duration()).isCloseTo(1.25, org.assertj.core.data.Offset.offset(0.001));
    assertThat(frequency(slower)).isCloseTo(440, org.assertj.core.data.Offset.offset(10.0));
  }

  @Test
  void resampleChangesThePitch() {
    SoundClip higher = SoundEffects.resample(sine(440, 1, 44100), 2);
    assertThat(higher.sampleCount()).isEqualTo(22050);
    assertThat(frequency(higher)).isCloseTo(880, org.assertj.core.data.Offset.offset(10.0));
  }

  @Test
  void volumeScalesAndClamps() {
    SoundClip clip = ramp(10);
    SoundClip louder = SoundEffects.volume(clip, null, 2);
    assertThat(louder.sample(0, 9)).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.01f));

    SoundClip softer = SoundEffects.volume(clip, null, 0.5);
    assertThat(softer.sample(0, 9)).isCloseTo(0.45f, org.assertj.core.data.Offset.offset(0.01f));
  }

  @Test
  void rejectsBadSelections() {
    assertThatThrownBy(() -> new SoundEffects.Selection(5, 4))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
