package org.openpatch.scratch4j.core.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The designer's model of a stage class: its size (the window's), its
 * backdrops, sounds, background colour and sprite instances, all living in
 * the marked regions.
 */
public final class StageModel {

  private int width = 480;
  private int height = 360;
  private final List<Backdrop> backdrops = new ArrayList<>();
  private final List<Sound> sounds = new ArrayList<>();
  private final SpriteRef.Refs sprites = new SpriteRef.Refs();
  private BackgroundColor color;

  /** One backdrop: a built-in name, or a name plus a file path. */
  public record Backdrop(String name, String path, Boolean stretch) {

    public Backdrop {
      Objects.requireNonNull(name);
    }

    public Backdrop(String name, String path) {
      this(name, path, null);
    }

    public boolean isStretched() {
      return Boolean.TRUE.equals(stretch);
    }
  }

  /** A built-in sound name, or a name plus a project-relative file path. */
  public record Sound(String name, String path) {
    public Sound {
      Objects.requireNonNull(name);
    }
  }

  /** The colour behind the backdrops, {@code this.setColor(r, g, b)}: 0 to 255 each. */
  public record BackgroundColor(int red, int green, int blue) {
    public BackgroundColor {
      for (int part : new int[] {red, green, blue}) {
        if (part < 0 || part > 255) {
          throw new IllegalArgumentException("not a colour part (0-255): " + part);
        }
      }
    }
  }

  public static StageModel create() {
    return new StageModel();
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public void size(int width, int height) {
    this.width = width;
    this.height = height;
  }

  public List<Backdrop> backdrops() {
    return backdrops;
  }

  public void addBackdrop(Backdrop backdrop) {
    backdrops.add(backdrop);
  }

  public List<Sound> sounds() {
    return sounds;
  }

  public void addSound(Sound sound) {
    sounds.add(sound);
  }

  /** The background colour, or null: the library's white. */
  public BackgroundColor color() {
    return color;
  }

  public void color(BackgroundColor color) {
    this.color = color;
  }

  public SpriteRef.Refs sprites() {
    return sprites;
  }

  /** Adds a sprite instance (without touching instance naming rules). */
  public void addSprite(SpriteRef ref) {
    sprites.add(ref);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof StageModel other)) {
      return false;
    }
    return width == other.width && height == other.height
        && backdrops.equals(other.backdrops)
        && sounds.equals(other.sounds)
        && Objects.equals(color, other.color)
        && sprites.equals(other.sprites);
  }

  @Override
  public int hashCode() {
    return Objects.hash(width, height, backdrops, sounds, color, sprites);
  }
}
