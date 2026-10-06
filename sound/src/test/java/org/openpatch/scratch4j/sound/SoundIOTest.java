package org.openpatch.scratch4j.sound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SoundIOTest {

  @TempDir
  Path tmp;

  /** 0.1 s of a 440 Hz sine, 44.1 kHz, stereo. */
  static SoundClip sine(float frequency, float seconds) {
    int samples = (int) (44100 * seconds);
    float[][] channels = new float[2][samples];
    for (int i = 0; i < samples; i++) {
      float v = (float) Math.sin(2 * Math.PI * frequency * i / 44100f) * 0.5f;
      channels[0][i] = v;
      channels[1][i] = v;
    }
    return new SoundClip(channels, 44100);
  }

  @Test
  void wavRoundTripPreservesSamplesAndRate() throws IOException {
    SoundClip clip = sine(440, 0.1f);
    Path file = tmp.resolve("sine.wav");
    SoundIO.writeWav(clip, file);
    assertThat(file).isRegularFile();

    SoundClip decoded = SoundIO.decode(file);
    assertThat(decoded.sampleRate()).isEqualTo(44100);
    assertThat(decoded.channelCount()).isEqualTo(2);
    assertThat(decoded.sampleCount()).isEqualTo(clip.sampleCount());
    for (int ch = 0; ch < 2; ch++) {
      for (int i = 0; i < decoded.sampleCount(); i += 100) {
        assertThat(decoded.sample(ch, i)).isCloseTo(clip.sample(ch, i),
            org.assertj.core.data.Offset.offset(0.001f));
      }
    }
  }

  @Test
  void decodesBuiltInOggSoundFromTheLibraryJar() throws IOException {
    // a real .ogg from the scratch jar (BuiltinSounds points into the jar)
    try (var in = org.openpatch.scratch.internal.BuiltinSounds.class
        .getResourceAsStream("/sounds/impact/impactGlass_medium_001.ogg")) {
      assertThat(in).isNotNull();
      SoundClip clip = SoundIO.decode(in);
      assertThat(clip.sampleRate()).isPositive();
      assertThat(clip.sampleCount()).isGreaterThan(100);
      assertThat(clip.peak()).isGreaterThan(0.01f);
    }
  }

  /** vorbisspi returned no samples for 80 of them (short files); JOrbis decodes all. */
  @Test
  void decodesEveryBuiltInSound() throws IOException {
    java.util.List<String> silent = new java.util.ArrayList<>();
    for (String name : org.openpatch.scratch.internal.BuiltinSounds.getNames()) {
      try (var in = org.openpatch.scratch.internal.BuiltinSounds.class
          .getResourceAsStream("/" + org.openpatch.scratch.internal.BuiltinSounds.get(name))) {
        SoundClip clip = SoundIO.decode(in);
        if (clip.sampleCount() == 0 || clip.peak() < 0.001f) {
          silent.add(name);
        }
      }
    }
    assertThat(silent).as("built-in sounds that decode to silence").isEmpty();
  }

  /** The JOrbis path matches the javax.sound SPI where the SPI works. */
  @Test
  void jorbisMatchesTheSpiOnAFileTheSpiHandles() throws Exception {
    String path = "/sounds/impact/impactGlass_medium_001.ogg";
    SoundClip ours;
    try (var in = SoundIOTest.class.getResourceAsStream(path)) {
      ours = SoundIO.decode(in);
    }
    byte[] pcm;
    javax.sound.sampled.AudioFormat format;
    try (var in = javax.sound.sampled.AudioSystem.getAudioInputStream(
        new java.io.BufferedInputStream(SoundIOTest.class.getResourceAsStream(path)))) {
      var base = in.getFormat();
      format = new javax.sound.sampled.AudioFormat(base.getSampleRate(), 16,
          base.getChannels(), true, false);
      pcm = javax.sound.sampled.AudioSystem.getAudioInputStream(format, in).readAllBytes();
    }
    int frames = pcm.length / format.getFrameSize();
    assertThat(ours.channelCount()).isEqualTo(format.getChannels());
    assertThat(ours.sampleRate()).isEqualTo(format.getSampleRate());
    assertThat(Math.abs(ours.sampleCount() - frames)).isLessThan(2048);
    for (int i = 1000; i < Math.min(frames, ours.sampleCount()); i += 997) {
      float spi = (short) ((pcm[i * format.getFrameSize() + 1] << 8)
          | (pcm[i * format.getFrameSize()] & 0xff)) / 32768f;
      assertThat(ours.channel(0)[i]).isCloseTo(spi, org.assertj.core.data.Offset.offset(0.01f));
    }
  }

  @Test
  void rejectsGarbageWithAClearError() throws IOException {
    Path file = tmp.resolve("garbage.wav");
    Files.writeString(file, "this is not audio");
    assertThatThrownBy(() -> SoundIO.decode(file))
        .isInstanceOf(IOException.class)
        .hasCauseInstanceOf(UnsupportedAudioFileException.class);
  }

  @Test
  void clipsAreImmutableCopies() {
    SoundClip clip = SoundClip.silence(1, 100, 44100);
    SoundClip copy = clip.copy();
    assertThat(copy).isEqualTo(clip);
    assertThatThrownBy(() -> new SoundClip(new float[0][], 44100))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
