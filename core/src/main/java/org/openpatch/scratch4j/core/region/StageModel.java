package org.openpatch.scratch4j.core.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The designer's model of a stage class: its size ({@code super(w, h)}), its
 * backdrops, sounds and sprite instances, all living in the marked regions.
 */
public final class StageModel {

  private int width = 480;
  private int height = 360;
  private final List<Backdrop> backdrops = new ArrayList<>();
  private final List<Sound> sounds = new ArrayList<>();
  private final SpriteRef.Refs sprites = new SpriteRef.Refs();

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
        && sprites.equals(other.sprites);
  }

  @Override
  public int hashCode() {
    return Objects.hash(width, height, backdrops, sounds, sprites);
  }
}
