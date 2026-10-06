package org.openpatch.scratch4j.sound;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Decodes and encodes audio. WAV, AIFF and AU come from the JDK; OGG and MP3
 * come from the vorbisspi/mp3spi javax.sound SPIs on the classpath - so a
 * student's MP3 import is exactly one decode away from a WAV save.
 */
public final class SoundIO {

  private SoundIO() {}

  /** Decodes a file; the format follows the file extension and the SPIs. */
  public static SoundClip decode(Path file) throws IOException {
    try (InputStream raw = new java.io.BufferedInputStream(
        java.nio.file.Files.newInputStream(file))) {
      if (OggVorbisDecoder.isOgg(raw)) {
        return OggVorbisDecoder.decode(raw);
      }
    }
    try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
      return decode(in);
    } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
      throw new IOException("Unsupported audio format: " + file, e);
    }
  }

  /** Decodes a stream (does not close it). */
  public static SoundClip decode(InputStream stream) throws IOException {
    // the SPIs sniff the header via mark/reset - jar streams do not support it
    InputStream buffered = new java.io.BufferedInputStream(stream);
    if (OggVorbisDecoder.isOgg(buffered)) {
      return OggVorbisDecoder.decode(buffered);
    }
    try (AudioInputStream in = AudioSystem.getAudioInputStream(buffered)) {
      return decode(in);
    } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
      throw new IOException("Unsupported audio format", e);
    }
  }

  private static SoundClip decode(AudioInputStream in) throws IOException {
    AudioFormat format = in.getFormat();
    if (format.getSampleRate() <= 0) {
      throw new IOException("Cannot decode stream without a sample rate");
    }
    // convert to 16-bit PCM signed little-endian unless already 8/16/24/32-bit PCM
    AudioFormat.Encoding encoding = format.getEncoding();
    boolean pcmSigned = encoding == AudioFormat.Encoding.PCM_SIGNED;
    boolean pcmUnsigned = encoding == AudioFormat.Encoding.PCM_UNSIGNED;
    boolean pcmFloat = encoding == AudioFormat.Encoding.PCM_FLOAT;
    int bits = format.getSampleSizeInBits();
    if (!(pcmSigned || pcmUnsigned || pcmFloat) || (bits != 8 && bits != 16
        && bits != 24 && bits != 32)) {
      AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
          format.getSampleRate(), 16, format.getChannels(), 2 * format.getChannels(),
          format.getSampleRate(), false);
      in = AudioSystem.getAudioInputStream(target, in);
      format = target;
      bits = 16;
      pcmSigned = true;
    }
    int channels = format.getChannels();
    int bytesPerFrame = format.getFrameSize();
    byte[] buffer = in.readAllBytes();
    int frames = bytesPerFrame > 0 ? buffer.length / bytesPerFrame : 0;
    float[][] out = new float[channels][frames];
    boolean bigEndian = format.isBigEndian();
    int sampleBytes = bits / 8;
    for (int frame = 0; frame < frames; frame++) {
      for (int ch = 0; ch < channels; ch++) {
        int base = frame * bytesPerFrame + ch * sampleBytes;
        out[ch][frame] = readSample(buffer, base, sampleBytes, bits, pcmFloat,
            pcmUnsigned, bigEndian);
      }
    }
    return new SoundClip(out, format.getSampleRate());
  }

  private static float readSample(byte[] buffer, int base, int sampleBytes, int bits,
      boolean pcmFloat, boolean pcmUnsigned, boolean bigEndian) {
    if (pcmFloat && bits == 32) {
      int bits32 = readInt(buffer, base, 4, bigEndian);
      return Float.intBitsToFloat(bits32);
    }
    long value = 0;
    if (bigEndian) {
      for (int i = 0; i < sampleBytes; i++) {
        value = (value << 8) | (buffer[base + i] & 0xFF);
      }
    } else {
      for (int i = sampleBytes - 1; i >= 0; i--) {
        value = (value << 8) | (buffer[base + i] & 0xFF);
      }
    }
    // sign-extend within a long: the value's top bit must land on bit 63
    int shift = 64 - bits;
    long signed = (value << shift) >> shift;
    if (pcmUnsigned) {
      long mid = 1L << (bits - 1);
      signed -= mid;
    }
    float max = pcmFloat ? 1f : (1L << (bits - 1));
    return Math.max(-1f, Math.min(1f, signed / max));
  }

  private static int readInt(byte[] buffer, int base, int bytes, boolean bigEndian) {
    int value = 0;
    if (bigEndian) {
      for (int i = 0; i < bytes; i++) {
        value = (value << 8) | (buffer[base + i] & 0xFF);
      }
    } else {
      for (int i = bytes - 1; i >= 0; i--) {
        value = (value << 8) | (buffer[base + i] & 0xFF);
      }
    }
    return value;
  }

  /** Writes the clip as a 16-bit PCM WAV file (the library plays WAV). */
  public static void writeWav(SoundClip clip, Path file) throws IOException {
    int channels = clip.channelCount();
    AudioFormat format = new AudioFormat(clip.sampleRate(), 16, channels, true, false);
    byte[] buffer = new byte[clip.sampleCount() * channels * 2];
    int i = 0;
    for (int frame = 0; frame < clip.sampleCount(); frame++) {
      for (int ch = 0; ch < channels; ch++) {
        int value = Math.round(clip.sample(ch, frame) * 32767f);
        value = Math.max(-32768, Math.min(32767, value));
        buffer[i++] = (byte) value;
        buffer[i++] = (byte) (value >> 8);
      }
    }
    try (java.io.ByteArrayInputStream bytes =
        new java.io.ByteArrayInputStream(buffer)) {
      AudioInputStream stream = new AudioInputStream(bytes, format,
          clip.sampleCount());
      AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
    }
  }
}
