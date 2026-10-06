package org.openpatch.scratch4j.sound;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScratchEffectsTest {

  /** One click at the start, then silence: echoes are easy to find. */
  private static SoundClip click(float rate, int samples) {
    float[][] channel = new float[1][samples];
    channel[0][0] = 0.8f;
    return new SoundClip(channel, rate);
  }

  @Test
  void echoRepeatsEveryQuarterSecondAtHalfVolumeAndGrowsATail() {
    SoundClip clip = click(1000, 1000);
    SoundClip echoed = SoundEffects.echo(clip, null);
    assertThat(echoed.sampleCount()).isEqualTo(2000);
    assertThat(echoed.sample(0, 0)).isEqualTo(0.8f);
    assertThat(echoed.sample(0, 250)).isEqualTo(0.4f);
    assertThat(echoed.sample(0, 500)).isEqualTo(0.2f);
    assertThat(echoed.sample(0, 100)).isZero();
  }

  @Test
  void echoOnASelectionKeepsTheLength() {
    SoundClip clip = click(1000, 1000);
    SoundClip echoed = SoundEffects.echo(clip, new SoundEffects.Selection(0, 499));
    assertThat(echoed.sampleCount()).isEqualTo(1000);
    assertThat(echoed.sample(0, 250)).isEqualTo(0.4f);
    assertThat(echoed.sample(0, 750)).isZero(); // outside the selection
  }

  @Test
  void robotRingsAt120HzWithoutClipping() {
    float rate = 12000;
    float[][] tone = new float[1][12000];
    for (int i = 0; i < tone[0].length; i++) {
      tone[0][i] = 0.9f * (float) Math.sin(2 * Math.PI * 440 * i / rate);
    }
    SoundClip robot = SoundEffects.robot(new SoundClip(tone, rate), null);
    assertThat(robot.sampleCount()).isEqualTo(12000);
    assertThat(robot.peak()).isLessThanOrEqualTo(0.9001f);
    // the comb repeats the signal every 100 samples (12000 / 120)
    SoundClip impulse = SoundEffects.robot(click(12000, 1000), null);
    assertThat(impulse.sample(0, 100)).isGreaterThan(0);
    assertThat(impulse.sample(0, 50)).isZero();
  }
}
