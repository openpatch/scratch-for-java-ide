package org.openpatch.scratch4j.core.assets;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The frames of a file-pattern animation, {@code addAnimation("walk",
 * "assets/images/walk%d.png", n)}: files numbered from 1 (the library's
 * convention) without gaps. Adding, duplicating, deleting and reordering keep
 * the numbering contiguous; deleted frames go to {@code .scratch4j/trash}.
 */
public final class FrameSequence {

  private final Path root;
  private final String pattern;

  /** {@code pattern} is project-relative and contains one {@code %d}. */
  public FrameSequence(Path projectRoot, String pattern) {
    if (pattern == null || !pattern.contains("%d") || pattern.indexOf("%d") != pattern.lastIndexOf("%d")) {
      throw new IllegalArgumentException("A frame pattern needs exactly one %d");
    }
    this.root = projectRoot;
    this.pattern = pattern;
  }

  public String pattern() {
    return pattern;
  }

  /** File of frame {@code number} (1-based). */
  public Path frame(int number) {
    return root.resolve(String.format(pattern, number));
  }

  /** The existing frames 1, 2, ... up to the first gap. */
  public List<Path> frames() {
    List<Path> frames = new ArrayList<>();
    for (int i = 1; Files.isRegularFile(frame(i)); i++) {
      frames.add(frame(i));
    }
    return frames;
  }

  /** Appends a transparent frame of the given size; returns its file. */
  public Path addBlank(int width, int height) throws IOException {
    Path next = frame(frames().size() + 1);
    Files.createDirectories(next.getParent());
    BufferedImage blank = new BufferedImage(Math.max(1, width), Math.max(1, height),
        BufferedImage.TYPE_INT_ARGB);
    if (!javax.imageio.ImageIO.write(blank, "png", next.toFile())) {
      throw new IOException("Could not write " + next);
    }
    return next;
  }

  /** Inserts a copy of frame {@code number} right after it; later frames move up one. */
  public Path duplicate(int number) throws IOException {
    List<Path> frames = frames();
    check(number, frames);
    for (int i = frames.size(); i > number; i--) {
      Files.move(frame(i), frame(i + 1));
    }
    Files.copy(frame(number), frame(number + 1));
    return frame(number + 1);
  }

  /** Moves frame {@code number} to the trash; later frames move down one. */
  public Path delete(int number) throws IOException {
    List<Path> frames = frames();
    check(number, frames);
    Path trash = root.resolve(".scratch4j/trash");
    Files.createDirectories(trash);
    Path trashed = trash.resolve(Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + "-"
        + frame(number).getFileName());
    Files.move(frame(number), trashed);
    for (int i = number + 1; i <= frames.size(); i++) {
      Files.move(frame(i), frame(i - 1));
    }
    return trashed;
  }

  /** Swaps frames {@code a} and {@code b}. */
  public void swap(int a, int b) throws IOException {
    List<Path> frames = frames();
    check(a, frames);
    check(b, frames);
    if (a == b) {
      return;
    }
    Path temp = frame(a).resolveSibling(".swap-" + UUID.randomUUID() + ".tmp");
    Files.move(frame(a), temp);
    Files.move(frame(b), frame(a));
    Files.move(temp, frame(b), StandardCopyOption.ATOMIC_MOVE);
  }

  private static void check(int number, List<Path> frames) {
    if (number < 1 || number > frames.size()) {
      throw new IllegalArgumentException("No frame " + number);
    }
  }
}
