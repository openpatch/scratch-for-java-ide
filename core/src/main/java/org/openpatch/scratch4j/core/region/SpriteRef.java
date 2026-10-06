package org.openpatch.scratch4j.core.region;

import java.util.ArrayList;
import java.util.Objects;

/**
 * The stage designer's model of one sprite instance on a stage, as expressed
 * in the {@code fields}/{@code setup} regions:
 *
 * <pre>
 * Player player;               (fields region)
 * player = new Player();       (setup region)
 * player.addCostume("bunny1_stand");
 * player.setPosition(-120, 40);
 * player.setDirection(90);
 * player.setSize(80);
 * player.hide();               (only when not visible)
 * this.add(player);
 * </pre>
 *
 * <p>A {@code UISprite} may also carry {@code setWidth}/{@code setHeight} (pixels).
 * A {@code Text} field is built in one line,
 * {@code title = new Text("Hello", -100, 150, 200[, TextStyle.BOX]);}, so its
 * position, words, wrap width and style live in the constructor call.
 *
 * <p>Presence flags (not normalized defaults) keep the round-trip lossless:
 * a body that says {@code setDirection(90)} keeps that line, a body without
 * it never gains one.
 */
public final class SpriteRef {

  private final String name;
  private final String type;
  /** The field's modifier ({@code private}, {@code protected}, {@code public}, or empty). */
  private String visibility = "private";

  private String costume;
  private String currentCostume;
  private String rotationStyle;
  private boolean hasPosition;
  private double x;
  private double y;
  private boolean hasDirection;
  private double direction;
  private boolean hasSize;
  private double size;
  private boolean visible = true;
  private boolean added;
  private boolean instantiated;
  private boolean hasWidth;
  private double width;
  private boolean hasHeight;
  private double height;
  /** Java string-literal content (escapes kept, so the round-trip is lossless). */
  private String text;
  private double textWidth;
  /** Java expression for the text style; null = the library default (PLAIN). */
  private String textStyle;

  public SpriteRef(String name, String type) {
    this.name = Objects.requireNonNull(name);
    this.type = Objects.requireNonNull(type);
  }

  public String visibility() {
    return visibility;
  }

  public SpriteRef visibility(String modifier) {
    this.visibility = modifier == null ? "" : modifier;
    return this;
  }

  public String name() {
    return name;
  }

  public String type() {
    return type;
  }

  public String costume() {
    return costume;
  }

  public void costume(String costume) {
    this.costume = costume;
  }

  /** The costume selected after construction, if any. */
  public String currentCostume() {
    return currentCostume;
  }

  public void currentCostume(String name) {
    this.currentCostume = name;
  }

  /** Java expression for the rotation style; null keeps the class default. */
  public String rotationStyle() {
    return rotationStyle;
  }

  public void rotationStyle(String expression) {
    this.rotationStyle = expression;
  }

  public boolean hasPosition() {
    return hasPosition;
  }

  public double x() {
    return x;
  }

  public double y() {
    return y;
  }

  public void setPosition(double x, double y) {
    this.x = x;
    this.y = y;
    this.hasPosition = true;
  }

  public boolean hasDirection() {
    return hasDirection;
  }

  public double direction() {
    return direction;
  }

  public void direction(double direction) {
    this.direction = direction;
    this.hasDirection = true;
  }

  public boolean hasSize() {
    return hasSize;
  }

  public double size() {
    return size;
  }

  public void size(double size) {
    this.size = size;
    this.hasSize = true;
  }

  /** A {@code Text} object rather than a sprite. */
  public boolean isText() {
    return "Text".equals(type);
  }

  public boolean hasWidth() {
    return hasWidth;
  }

  public double width() {
    return width;
  }

  public void width(double width) {
    this.width = width;
    this.hasWidth = true;
  }

  public void clearWidth() {
    this.hasWidth = false;
    this.width = 0;
  }

  public boolean hasHeight() {
    return hasHeight;
  }

  public double height() {
    return height;
  }

  public void height(double height) {
    this.height = height;
    this.hasHeight = true;
  }

  public void clearHeight() {
    this.hasHeight = false;
    this.height = 0;
  }

  /** The words of a Text, as Java string-literal content (escapes such as \\n kept). */
  public String text() {
    return text;
  }

  public void text(String literalContent) {
    this.text = literalContent;
  }

  /** Where a Text wraps, in pixels (0 = no wrapping). */
  public double textWidth() {
    return textWidth;
  }

  public void textWidth(double textWidth) {
    this.textWidth = textWidth;
  }

  public String textStyle() {
    return textStyle;
  }

  public void textStyle(String expression) {
    this.textStyle = expression;
  }

  public boolean isVisible() {
    return visible;
  }

  public void visible(boolean visible) {
    this.visible = visible;
  }

  public boolean isAdded() {
    return added;
  }

  public void added(boolean added) {
    this.added = added;
  }

  public boolean isInstantiated() {
    return instantiated;
  }

  public void instantiated(boolean instantiated) {
    this.instantiated = instantiated;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SpriteRef other)) {
      return false;
    }
    return Objects.equals(name, other.name)
        && Objects.equals(type, other.type)
        && Objects.equals(visibility, other.visibility)
        && Objects.equals(costume, other.costume)
        && Objects.equals(currentCostume, other.currentCostume)
        && Objects.equals(rotationStyle, other.rotationStyle)
        && hasPosition == other.hasPosition
        && Double.compare(x, other.x) == 0
        && Double.compare(y, other.y) == 0
        && hasDirection == other.hasDirection
        && Double.compare(direction, other.direction) == 0
        && hasSize == other.hasSize
        && Double.compare(size, other.size) == 0
        && visible == other.visible
        && added == other.added
        && instantiated == other.instantiated
        && hasWidth == other.hasWidth
        && Double.compare(width, other.width) == 0
        && hasHeight == other.hasHeight
        && Double.compare(height, other.height) == 0
        && Objects.equals(text, other.text)
        && Double.compare(textWidth, other.textWidth) == 0
        && Objects.equals(textStyle, other.textStyle);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, type, visibility, costume, currentCostume, rotationStyle,
        hasPosition, x, y, hasDirection,
        direction, hasSize, size, visible, added, instantiated, hasWidth, width,
        hasHeight, height, text, textWidth, textStyle);
  }

  @Override
  public String toString() {
    return name + " : " + type;
  }

  /** A fresh copy with the same values. */
  public SpriteRef copy() {
    return copyAs(name);
  }

  /** A value-preserving copy with a different Java field name. */
  public SpriteRef copyAs(String newName) {
    SpriteRef copy = new SpriteRef(newName, type);
    copy.visibility = visibility;
    copy.costume = costume;
    copy.currentCostume = currentCostume;
    copy.rotationStyle = rotationStyle;
    copy.hasPosition = hasPosition;
    copy.x = x;
    copy.y = y;
    copy.hasDirection = hasDirection;
    copy.direction = direction;
    copy.size = size;
    copy.hasSize = hasSize;
    copy.hasWidth = hasWidth;
    copy.width = width;
    copy.hasHeight = hasHeight;
    copy.height = height;
    copy.text = text;
    copy.textWidth = textWidth;
    copy.textStyle = textStyle;
    copy.visible = visible;
    copy.added = added;
    copy.instantiated = instantiated;
    return copy;
  }

  /** All sprite instances of a stage, in add order. */
  public static final class Refs extends ArrayList<SpriteRef> {
    public SpriteRef byName(String name) {
      for (SpriteRef ref : this) {
        if (ref.name().equals(name)) {
          return ref;
        }
      }
      return null;
    }
  }
}
