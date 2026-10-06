package org.openpatch.scratch4j.sound;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Decodes Ogg Vorbis with JOrbis directly. The vorbisspi javax.sound wrapper
 * returns no samples for short files: 80 of the library's 266 built-in
 * sounds (most footsteps, many UI clicks) decoded to silence through it.
 */
final class OggVorbisDecoder {

  private static final int CHUNK = 4096;

  private OggVorbisDecoder() {}

  /** True when the stream starts with the Ogg capture pattern (stream must support mark). */
  static boolean isOgg(InputStream in) throws IOException {
    in.mark(4);
    byte[] magic = in.readNBytes(4);
    in.reset();
    return magic.length == 4 && magic[0] == 'O' && magic[1] == 'g' && magic[2] == 'g'
        && magic[3] == 'S';
  }

  static SoundClip decode(InputStream input) throws IOException {
    SyncState sync = new SyncState();
    StreamState stream = new StreamState();
    Page page = new Page();
    Packet packet = new Packet();
    Info info = new Info();
    Comment comment = new Comment();
    DspState dsp = new DspState();
    Block block = new Block(dsp);
    sync.init();

    // the first page carries the identification header
    feed(sync, input);
    if (sync.pageout(page) != 1) {
      throw new IOException("Not an Ogg Vorbis stream");
    }
    stream.init(page.serialno());
    info.init();
    comment.init();
    if (stream.pagein(page) < 0 || stream.packetout(packet) != 1
        || info.synthesis_headerin(comment, packet) < 0) {
      throw new IOException("Not an Ogg Vorbis stream");
    }
    // then the comment and codebook headers
    int headers = 1;
    while (headers < 3) {
      int result = sync.pageout(page);
      if (result == 0) {
        if (feed(sync, input) <= 0) {
          throw new IOException("Ogg Vorbis stream ends inside its headers");
        }
        continue;
      }
      if (result < 0) {
        continue;
      }
      stream.pagein(page);
      while (headers < 3) {
        result = stream.packetout(packet);
        if (result == 0) {
          break;
        }
        if (result < 0 || info.synthesis_headerin(comment, packet) < 0) {
          throw new IOException("Corrupt Ogg Vorbis header");
        }
        headers++;
      }
    }

    dsp.synthesis_init(info);
    block.init(dsp);
    int channels = info.channels;
    float[][] out = new float[channels][CHUNK * 4];
    int length = 0;
    float[][][] pcm = new float[1][][];
    int[] index = new int[channels];

    boolean endOfStream = false;
    while (!endOfStream) {
      int result = sync.pageout(page);
      if (result == 0) {
        endOfStream = feed(sync, input) <= 0;
        continue;
      }
      if (result < 0) {
        continue; // a hole in the data: skip it
      }
      stream.pagein(page);
      while (stream.packetout(packet) != 0) {
        if (block.synthesis(packet) == 0) {
          dsp.synthesis_blockin(block);
        }
        int samples;
        while ((samples = dsp.synthesis_pcmout(pcm, index)) > 0) {
          if (length + samples > out[0].length) {
            int capacity = Math.max(out[0].length * 2, length + samples);
            for (int ch = 0; ch < channels; ch++) {
              out[ch] = Arrays.copyOf(out[ch], capacity);
            }
          }
          for (int ch = 0; ch < channels; ch++) {
            float[] source = pcm[0][ch];
            for (int i = 0; i < samples; i++) {
              out[ch][length + i] = Math.max(-1f, Math.min(1f, source[index[ch] + i]));
            }
          }
          length += samples;
          dsp.synthesis_read(samples);
        }
      }
      if (page.eos() != 0) {
        endOfStream = true;
      }
    }
    stream.clear();
    block.clear();
    dsp.clear();
    info.clear();
    sync.clear();

    for (int ch = 0; ch < channels; ch++) {
      out[ch] = Arrays.copyOf(out[ch], length);
    }
    return new SoundClip(out, info.rate);
  }

  /** Reads the next chunk into the sync buffer; returns the bytes read (-1 at the end). */
  private static int feed(SyncState sync, InputStream input) throws IOException {
    int offset = sync.buffer(CHUNK);
    int read = input.read(sync.data, offset, CHUNK);
    sync.wrote(Math.max(read, 0));
    return read;
  }
}
