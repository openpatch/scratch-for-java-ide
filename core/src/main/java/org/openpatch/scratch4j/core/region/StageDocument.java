package org.openpatch.scratch4j.core.region;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;

/**
 * One stage class as the designer sees it: the stage designer's blocks hold
 * the model; everything else in the file is untouchable.
 *
 * <p>A statement in a block that the designer does not write (see
 * {@link RegionStatements}) stays as it is. It is {@linkplain #foreign()
 * foreign}, and the sprites it touches are {@linkplain #codeOwnedSprites()
 * left to the code}: the designer shows them but does not change them. Only
 * Java that does not parse makes the whole stage unreadable.
 */
public final class StageDocument {

  /** Field types that are values, not sprites. */
  private static final Pattern VALUE_TYPE = Pattern.compile(
      "byte|short|int|long|float|double|boolean|char|(?:java\\.lang\\.)?(?:String|Byte|Short"
          + "|Integer|Long|Float|Double|Boolean|Character)");

  /** A statement in a block that the designer leaves to the code: its line in the file. */
  public record Foreign(int line, String statement) {}

  private final String source;
  private final StageModel model;
  private final StageModel original;
  private final Set<String> codeOwned;
  private final List<Foreign> foreign;

  private StageDocument(String source, StageModel model, Set<String> codeOwned,
      List<Foreign> foreign) {
    this.source = source;
    this.model = model;
    this.original = copy(model);
    this.codeOwned = Set.copyOf(codeOwned);
    this.foreign = List.copyOf(foreign);
  }

  private static StageModel copy(StageModel model) {
    StageModel copy = StageModel.create();
    copy.size(model.width(), model.height());
    copy.backdrops().addAll(model.backdrops());
    copy.sounds().addAll(model.sounds());
    copy.color(model.color());
    model.sprites().forEach(ref -> copy.addSprite(ref.copy()));
    return copy;
  }

  /**
   * Reads a stage class.
   *
   * @throws RegionParser.RegionParseException when the markers do not pair up
   * @throws RegionStatements.UnsupportedRegionException when a block is not
   *     finished Java
   */
  public static StageDocument read(String source) {
    StageModel model = StageModel.create();
    var regions = RegionParser.parse(source);
    Set<String> codeOwned = new HashSet<>();
    List<Foreign> foreign = new ArrayList<>();
    Region fields = find(regions, "fields");
    if (fields != null) for (var statement : JavaFragment.statements(fields.body(), true)) {
      if (!RegionStatements.readField(statement.tree(), model)) {
        foreign.add(foreign(source, fields, statement));
        if (statement.tree() instanceof com.sun.source.tree.VariableTree variable) {
          String name = variable.getName().toString();
          if (model.sprites().byName(name) == null) {
            model.addSprite(new SpriteRef(name, variable.getType().toString()));
          }
          codeOwned.add(name);
        }
      }
    }
    Region setup = find(regions, "setup");
    // the layer order is the add order (front: added last), not the fields' order
    List<String> addOrder = new ArrayList<>();
    if (setup != null) for (var statement : JavaFragment.statements(setup.body(), false)) {
      String key = StageRegionEdit.key(statement.tree(), false);
      if (key != null && key.endsWith("/add")) addOrder.add(key.substring(0, key.length() - 4));
      if (!RegionStatements.readSetup(statement, model)) {
        foreign.add(foreign(source, setup, statement));
        // every sprite the statement mentions is the code's from now on
        new com.sun.source.util.TreeScanner<Void, Void>() {
          @Override public Void visitIdentifier(com.sun.source.tree.IdentifierTree identifier,
              Void unused) {
            if (model.sprites().byName(identifier.getName().toString()) != null) {
              codeOwned.add(identifier.getName().toString());
            }
            return super.visitIdentifier(identifier, unused);
          }

          @Override public Void visitMemberSelect(com.sun.source.tree.MemberSelectTree member,
              Void unused) {
            String name = RegionStatements.name(member);
            if (name != null && model.sprites().byName(name) != null) codeOwned.add(name);
            return super.visitMemberSelect(member, unused);
          }
        }.scan(statement.tree(), null);
      }
    }
    List<SpriteRef> declared = List.copyOf(model.sprites());
    model.sprites().sort(java.util.Comparator.comparingInt(ref -> {
      int added = addOrder.indexOf(ref.name());
      return added >= 0 ? added : addOrder.size() + declared.indexOf(ref);
    }));
    for (SpriteRef ref : List.copyOf(model.sprites())) {
      if (VALUE_TYPE.matcher(ref.type()).matches()) {
        // int score; String name; are no sprites: the code keeps them as they are
        model.sprites().remove(ref);
        codeOwned.remove(ref.name());
      } else if (!ref.isAdded() || !ref.isInstantiated()) {
        codeOwned.add(ref.name());
      }
    }
    return new StageDocument(source, model, codeOwned, foreign);
  }

  private static Foreign foreign(String source, Region region, JavaFragment.Statement statement) {
    int line = lineOf(source, region.beginOffset()) + statement.line() - 1;
    return new Foreign(line, statement.source().strip());
  }

  /** Whether the designer may change this sprite (not left to the code). */
  public boolean isSpriteEditable(String name) {
    return !codeOwned.contains(name);
  }

  /** The stage's own {@code setColor} calls: {@code this.setColor(...)} or {@code setColor(...)}. */
  private static final Pattern SET_COLOR =
      Pattern.compile("(?<![\\w.])(?:this\\s*\\.\\s*)?setColor\\s*\\(");

  /**
   * Whether the designer may set the background colour: no other code of the
   * class sets it (it would win, or be overwritten). One call is the
   * designer's own when it read a colour.
   */
  public boolean isColorEditable() {
    long calls = SET_COLOR.matcher(source).results().count();
    return calls == (original.color() != null ? 1 : 0);
  }

  /** The sprites the code decides about: the designer shows them, and leaves them be. */
  public Set<String> codeOwnedSprites() {
    return codeOwned;
  }

  /** The statements in the blocks that the designer leaves to the code. */
  public List<Foreign> foreign() {
    return foreign;
  }

  private static Region find(List<Region> regions, String id) {
    return regions.stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
  }

  /** Line (1-based) of an offset. */
  private static int lineOf(String source, int offset) {
    int line = 1;
    for (int i = 0; i < offset && i < source.length(); i++) {
      if (source.charAt(i) == '\n') line++;
    }
    return line;
  }

  public StageModel model() {
    return model;
  }

  /** Rename a managed sprite field when handwritten stage code has no references to it. */
  public void renameInstance(String oldName, String newName) {
    if (!newName.matches("[A-Za-z_][A-Za-z0-9_]*")
        || SourceVersion.isKeyword(newName)) {
      throw new IllegalArgumentException("Not a valid Java field name: " + newName);
    }
    SpriteRef old = model.sprites().byName(oldName);
    if (old == null || model.sprites().byName(newName) != null) {
      throw new IllegalArgumentException("Unknown or duplicate sprite name: " + newName);
    }
    StringBuilder outside = new StringBuilder(source);
    for (Region region : RegionParser.parse(source)) {
      if (region.id().equals("fields") || region.id().equals("setup")) {
        for (int i = region.beginOffset(); i < region.endOffset(); i++) {
          outside.setCharAt(i, ' ');
        }
      }
    }
    if (Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(oldName)
        + "(?![A-Za-z0-9_$])").matcher(outside).find()) {
      throw new IllegalArgumentException(
          "Hand-written stage code refers to " + oldName + "; rename it in code first");
    }
    model.sprites().set(model.sprites().indexOf(old), old.copyAs(newName));
  }

  public String source() {
    return source;
  }

  /**
   * Writes the model back into the blocks, changing only what the model
   * changed. Returns the new source text; nothing is written to disk here.
   */
  public String write(StageModel updated) {
    return writeOn(source, updated);
  }

  /** Applies only this visual edit's changed values to the latest code editor text. */
  public String writeOn(String latestSource, StageModel updated) {
    StageDocument latest = read(latestSource);
    for (SpriteRef old : original.sprites()) {
      SpriteRef changed = updated.sprites().byName(old.name());
      if (!java.util.Objects.equals(old, changed)
          && (!isSpriteEditable(old.name()) || !latest.isSpriteEditable(old.name()))) {
        throw new IllegalArgumentException(old.name() + " is controlled by its Java code");
      }
    }
    if (!java.util.Objects.equals(original.color(), updated.color())
        && (!isColorEditable() || !latest.isColorEditable())) {
      throw new IllegalArgumentException("The background colour is set in the Java code");
    }
    // a stage written by hand gets its regions with the first designer edit
    String result = DesignerRegions.ensureStage(latestSource);
    if (!updated.sprites().isEmpty()) {
      if (!RegionParser.has(result, "fields")) {
        throw new RegionStatements.UnsupportedRegionException(
            "stage class has no fields region for its sprites");
      }
      result = StageRegionEdit.rewrite(result, "fields", original, updated, latest.model());
    } else if (RegionParser.has(result, "fields")) {
      result = StageRegionEdit.rewrite(result, "fields", original, updated, latest.model());
    }
    result = StageRegionEdit.rewrite(result, "setup", original, updated, latest.model());
    boolean texts = updated.sprites().stream().anyMatch(SpriteRef::isText);
    boolean styles = updated.sprites().stream()
        .anyMatch(r -> r.isText() && r.textStyle() != null && r.textStyle().startsWith("TextStyle."));
    if (texts) {
      result = ensureImport(result, "Text");
    }
    if (styles) {
      result = ensureImport(result, "TextStyle");
    }
    if (updated.sprites().stream().anyMatch(r -> r.rotationStyle() != null)) {
      result = ensureImport(result, "RotationStyle");
    }
    return result;
  }

  /**
   * Adds {@code import org.openpatch.scratch.<simpleName>;} after the last
   * import (or at the top) unless the class or the package wildcard is
   * already imported. Generated {@code Text} fields need it to compile.
   */
  static String ensureImport(String source, String simpleName) {
    return org.openpatch.scratch4j.core.project.JavaImports.ensure(source,
        "org.openpatch.scratch." + simpleName);
  }

  /** Convenience: write(this model()). */
  public String write() {
    return write(model);
  }
}
