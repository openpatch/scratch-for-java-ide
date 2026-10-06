package org.openpatch.scratch4j.core.region;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and generates the restricted statement subset the stage designer
 * owns inside the {@code fields} and {@code setup} regions.
 *
 * <p>The subset (2-space style, {@code this.} prefix, as in the library docs):
 *
 * <pre>
 * fields:  Type name;
 * setup:   this.addBackdrop("name");   this.addBackdrop("name", "path"[, stretch]);
 *          this.addSound("name");      this.addSound("name", "path");
 *          name = new Type();
 *          name.addCostume("costume");
 *          name.switchCostume("costume");
 *          name.setPosition(x, y);
 *          name.setDirection(deg);
 *          name.setRotationStyle(RotationStyle.LEFT_RIGHT);
 *          name.setSize(percent);
 *          name.setWidth(px); / name.setHeight(px);   (UISprite)
 *          name = new Text("words", x, y, width[, TextStyle.BOX]);
 *          name.hide(); / name.show();
 *          this.add(name);
 * </pre>
 *
 * <p>Every declared field must be instantiated and added — that is the
 * canonical shape the designer generates. Anything else (other calls, other
 * statements, comments, half-finished blocks) throws
 * {@link UnsupportedRegionException}: the designer then goes read-only for
 * that class instead of destroying user code.
 */
public final class RegionStatements {

  /** The region is not expressible in the designer's subset. */
  public static final class UnsupportedRegionException extends RuntimeException {
    public UnsupportedRegionException(String message) {
      super(message);
    }
  }

  private static final Pattern FIELD_DECL =
      Pattern.compile("(?:(private|protected|public)\\s+)?(\\w+)\\s+(\\w+)\\s*;");
  private static final Pattern ADD_BACKDROP =
      Pattern.compile("this\\.addBackdrop\\(\"([^\"]+)\"(?:\\s*,\\s*\"([^\"]+)\"(?:\\s*,\\s*(true|false))?)?\\)\\s*;");
  private static final Pattern ADD_SOUND =
      Pattern.compile("this\\.addSound\\(\"([^\"]+)\"(?:\\s*,\\s*\"([^\"]+)\")?\\)\\s*;");
  private static final Pattern NEW_INSTANCE =
      Pattern.compile("(\\w+)\\s*=\\s*new\\s+(\\w+)\\(\\)\\s*;");
  private static final Pattern ADD_COSTUME =
      Pattern.compile("(\\w+)\\.addCostume\\(\"([^\"]+)\"\\)\\s*;");
  private static final Pattern SWITCH_COSTUME =
      Pattern.compile("(\\w+)\\.switchCostume\\(\"([^\"]+)\"\\)\\s*;");
  private static final Pattern SET_ROTATION_STYLE = Pattern.compile(
      "(\\w+)\\.setRotationStyle\\(((?:org\\.openpatch\\.scratch\\.)?RotationStyle\\.(?:ALL_AROUND|LEFT_RIGHT|DONT))\\)\\s*;");
  private static final Pattern SET_POSITION =
      Pattern.compile("(\\w+)\\.setPosition\\((-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\)\\s*;");
  private static final Pattern SET_DIRECTION =
      Pattern.compile("(\\w+)\\.setDirection\\((-?\\d+(?:\\.\\d+)?)\\)\\s*;");
  private static final Pattern SET_SIZE =
      Pattern.compile("(\\w+)\\.setSize\\((-?\\d+(?:\\.\\d+)?)\\)\\s*;");
  private static final String NUMBER = "(-?\\d+(?:\\.\\d+)?)";
  private static final Pattern SET_WIDTH =
      Pattern.compile("(\\w+)\\.setWidth\\(" + NUMBER + "\\)\\s*;");
  private static final Pattern SET_HEIGHT =
      Pattern.compile("(\\w+)\\.setHeight\\(" + NUMBER + "\\)\\s*;");
  private static final Pattern NEW_TEXT = Pattern.compile(
      "(\\w+)\\s*=\\s*new\\s+Text\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*" + NUMBER
      + "\\s*,\\s*" + NUMBER + "\\s*,\\s*" + NUMBER
      + "(?:\\s*,\\s*((?:org\\.openpatch\\.scratch\\.)?TextStyle\\.(?:PLAIN|BOX|SPEAK|THINK)))?"
      + "\\)\\s*;");
  private static final Pattern HIDE = Pattern.compile("(\\w+)\\.hide\\(\\)\\s*;");
  private static final Pattern SHOW = Pattern.compile("(\\w+)\\.show\\(\\)\\s*;");
  private static final Pattern THIS_ADD = Pattern.compile("this\\.add\\((\\w+)\\)\\s*;");

  private RegionStatements() {}

  /** Parses the fields-region body into sprite refs (with type, no values). */
  public static void parseFields(String body, StageModel model) {
    List<String> lines = body.lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).strip();
      if (line.isEmpty()) {
        continue;
      }
      Matcher m = FIELD_DECL.matcher(line);
      if (!m.matches()) {
        throw new UnsupportedRegionException("fields line " + (i + 1)
            + " is not a field declaration: " + line);
      }
      SpriteRef ref = new SpriteRef(m.group(3), m.group(2))
          .visibility(m.group(1) == null ? "" : m.group(1));
      if (model.sprites().byName(ref.name()) != null) {
        throw new UnsupportedRegionException("duplicate sprite name: " + ref.name());
      }
      model.sprites().add(ref);
    }
  }

  /** Parses the setup-region body into backdrops and sprite values. */
  public static void parseSetup(String body, StageModel model) {
    List<String> lines = body.lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).strip();
      if (line.isEmpty()) {
        continue;
      }
      String location = "setup line " + (i + 1) + ": ";
      Matcher m;
      if ((m = ADD_BACKDROP.matcher(line)).matches()) {
        String path = m.group(2);
        if (m.group(3) != null && m.group(1).equals(path)) {
          path = null; // generated built-in stretch uses the name as its path
        }
        model.addBackdrop(new StageModel.Backdrop(m.group(1), path,
            m.group(3) == null ? null : Boolean.valueOf(m.group(3))));
      } else if ((m = ADD_SOUND.matcher(line)).matches()) {
        model.addSound(new StageModel.Sound(m.group(1), m.group(2)));
      } else if ((m = NEW_TEXT.matcher(line)).matches()) {
        SpriteRef ref = require(model, m.group(1), location);
        if (!ref.isText()) {
          throw new UnsupportedRegionException(location
              + "type Text does not match the field " + ref.type());
        }
        ref.text(m.group(2));
        ref.setPosition(Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4)));
        ref.textWidth(Double.parseDouble(m.group(5)));
        ref.textStyle(m.group(6));
        ref.instantiated(true);
      } else if ((m = NEW_INSTANCE.matcher(line)).matches()) {
        SpriteRef ref = require(model, m.group(1), location);
        if (ref.isText()) {
          throw new UnsupportedRegionException(location
              + "a Text needs its words and position: new Text(\"...\", x, y, width)");
        }
        if (!ref.type().equals(m.group(2))) {
          throw new UnsupportedRegionException(location
              + "type " + m.group(2) + " does not match the field " + ref.type());
        }
        ref.instantiated(true);
      } else if ((m = ADD_COSTUME.matcher(line)).matches()) {
        require(model, m.group(1), location).costume(m.group(2));
      } else if ((m = SWITCH_COSTUME.matcher(line)).matches()) {
        require(model, m.group(1), location).currentCostume(m.group(2));
      } else if ((m = SET_POSITION.matcher(line)).matches()) {
        require(model, m.group(1), location)
            .setPosition(Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3)));
      } else if ((m = SET_DIRECTION.matcher(line)).matches()) {
        require(model, m.group(1), location).direction(Double.parseDouble(m.group(2)));
      } else if ((m = SET_ROTATION_STYLE.matcher(line)).matches()) {
        require(model, m.group(1), location).rotationStyle(m.group(2));
      } else if ((m = SET_SIZE.matcher(line)).matches()) {
        require(model, m.group(1), location).size(Double.parseDouble(m.group(2)));
      } else if ((m = SET_WIDTH.matcher(line)).matches()) {
        require(model, m.group(1), location).width(Double.parseDouble(m.group(2)));
      } else if ((m = SET_HEIGHT.matcher(line)).matches()) {
        require(model, m.group(1), location).height(Double.parseDouble(m.group(2)));
      } else if ((m = HIDE.matcher(line)).matches()) {
        require(model, m.group(1), location).visible(false);
      } else if ((m = SHOW.matcher(line)).matches()) {
        require(model, m.group(1), location).visible(true);
      } else if ((m = THIS_ADD.matcher(line)).matches()) {
        require(model, m.group(1), location).added(true);
      } else {
        throw new UnsupportedRegionException(
            location + "not part of the designer subset: " + line);
      }
    }
    for (SpriteRef ref : model.sprites()) {
      if (!ref.isInstantiated() || !ref.isAdded()) {
        throw new UnsupportedRegionException(
            "sprite " + ref.name() + " is declared but not instantiated and added");
      }
    }
  }

  private static SpriteRef require(StageModel model, String name, String location) {
    SpriteRef ref = model.sprites().byName(name);
    if (ref == null) {
      throw new UnsupportedRegionException(location + "unknown sprite name: " + name);
    }
    return ref;
  }

  /** Generates the canonical fields-region body (no trailing newline beyond the last line). */
  public static String generateFields(StageModel model, String indent) {
    StringBuilder sb = new StringBuilder();
    for (SpriteRef ref : model.sprites()) {
      // the field keeps its modifier; new sprites are private
      sb.append(indent).append(ref.visibility().isEmpty() ? "" : ref.visibility() + " ")
          .append(ref.type()).append(' ').append(ref.name()).append(";\n");
    }
    return sb.toString();
  }

  /** Generates the canonical setup-region body. */
  public static String generateSetup(StageModel model, String indent) {
    StringBuilder sb = new StringBuilder();
    for (StageModel.Backdrop backdrop : model.backdrops()) {
      sb.append(indent).append("this.addBackdrop(\"").append(backdrop.name()).append('"');
      if (backdrop.path() != null || backdrop.stretch() != null) {
        sb.append(", \"").append(backdrop.path() == null ? backdrop.name()
            : backdrop.path()).append('"');
      }
      if (backdrop.stretch() != null) {
        sb.append(", ").append(backdrop.stretch());
      }
      sb.append(");\n");
    }
    for (StageModel.Sound sound : model.sounds()) {
      sb.append(indent).append("this.addSound(\"").append(sound.name()).append('"');
      if (sound.path() != null) {
        sb.append(", \"").append(sound.path()).append('"');
      }
      sb.append(");\n");
    }
    for (SpriteRef ref : model.sprites()) {
      if (ref.isText()) {
        sb.append(indent).append(ref.name()).append(" = new Text(\"")
            .append(ref.text() == null ? "" : ref.text()).append("\", ")
            .append(number(ref.x())).append(", ").append(number(ref.y())).append(", ")
            .append(number(ref.textWidth()));
        if (ref.textStyle() != null) {
          sb.append(", ").append(ref.textStyle());
        }
        sb.append(");\n");
        if (!ref.isVisible()) {
          sb.append(indent).append(ref.name()).append(".hide();\n");
        }
        sb.append(indent).append("this.add(").append(ref.name()).append(");\n");
        continue;
      }
      sb.append(indent).append(ref.name()).append(" = new ").append(ref.type()).append("();\n");
      if (ref.costume() != null) {
        sb.append(indent).append(ref.name()).append(".addCostume(\"")
            .append(ref.costume()).append("\");\n");
      }
      if (ref.currentCostume() != null) {
        sb.append(indent).append(ref.name()).append(".switchCostume(\"")
            .append(ref.currentCostume()).append("\");\n");
      }
      if (ref.hasPosition()) {
        sb.append(indent).append(ref.name()).append(".setPosition(")
            .append(number(ref.x())).append(", ").append(number(ref.y())).append(");\n");
      }
      if (ref.hasDirection()) {
        sb.append(indent).append(ref.name()).append(".setDirection(")
            .append(number(ref.direction())).append(");\n");
      }
      if (ref.rotationStyle() != null) {
        sb.append(indent).append(ref.name()).append(".setRotationStyle(")
            .append(ref.rotationStyle()).append(");\n");
      }
      if (ref.hasSize()) {
        sb.append(indent).append(ref.name()).append(".setSize(")
            .append(number(ref.size())).append(");\n");
      }
      if (ref.hasWidth()) {
        sb.append(indent).append(ref.name()).append(".setWidth(")
            .append(number(ref.width())).append(");\n");
      }
      if (ref.hasHeight()) {
        sb.append(indent).append(ref.name()).append(".setHeight(")
            .append(number(ref.height())).append(");\n");
      }
      if (!ref.isVisible()) {
        sb.append(indent).append(ref.name()).append(".hide();\n");
      }
      sb.append(indent).append("this.add(").append(ref.name()).append(");\n");
    }
    return sb.toString();
  }

  /** Numbers print without a trailing .0 when they are whole. */
  public static String number(double value) {
    if (value == Math.floor(value) && !Double.isInfinite(value)) {
      return Long.toString((long) value);
    }
    return Double.toString(value);
  }
}
