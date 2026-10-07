package org.openpatch.scratch4j.core.project;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts the scripts of one Scratch target (sprite or stage) into Java for
 * a Scratch for Java class. Hat blocks become the library's event methods,
 * "when green flag clicked" goes into the constructor and a trailing
 * "forever" into {@code run()} (which the library calls every frame), the
 * common "forever with one wait" becomes {@code getTimer(..).everyMillis(ms)}.
 * Blocks that have no faithful one-to-one translation — waits in the middle of
 * a script, "ask", "wait until", and clones for a library without them —
 * become {@code // TODO Scratch:} lines that show the original block, so
 * nothing is lost silently. With a library that has clones, "create clone of
 * myself", "when I start as a clone" and "delete this clone" become
 * {@code clone()}, {@code whenStartsAsClone()} and {@code deleteThisClone()}.
 */
final class Sb3Scripts {

  /** What one target contributes to its class. */
  record Output(String fields, String constructor, String run, String methods, int scripts,
      int todos, boolean needsValues) {}

  private enum Type { NUM, STR, BOOL, OBJ }

  private record Expr(String code, Type type) {}

  /** A variable or list as Java sees it. */
  record Variable(String java, boolean global, boolean number, boolean list) {}

  private final JsonNode blocks;
  private final boolean stage;
  private final Map<String, Variable> variables;
  private final Map<String, String> spriteClasses;
  private final Map<String, String[]> procedures = new LinkedHashMap<>();
  private int todos;
  /** Parameter kinds (n, s, b) of the custom block being converted. */
  private Map<String, String> parameters = Map.of();
  private boolean needsValues;
  private int loopDepth;
  private int timers;
  /** The library has Scratch's clones (clone(), whenStartsAsClone()). */
  private boolean clones;
  /** This target makes or is clones: run() then tells the original and clones apart. */
  private boolean usesClones;

  /**
   * {@code variables} maps Scratch variable/list ids to Java names (globals
   * live as statics on the stage class), {@code spriteClasses} Scratch sprite
   * names to Java classes.
   */
  Sb3Scripts(JsonNode target, boolean stage, String stageClass, Map<String, Variable> variables,
      Map<String, String> spriteClasses) {
    this.blocks = target.path("blocks");
    this.stage = stage;
    this.variables = variables;
    this.spriteClasses = spriteClasses;
  }

  /** Converts clone blocks too (the project's library has them). */
  Sb3Scripts withClones(boolean clones) {
    this.clones = clones;
    return this;
  }

  Output convert(String ownFields) {
    // custom blocks first: calls need their parameter lists
    for (var entry : blocks.properties()) {
      JsonNode block = entry.getValue();
      if ("procedures_definition".equals(op(block))) {
        JsonNode prototype = blocks.path(input(block, "custom_block"));
        String proccode = prototype.path("mutation").path("proccode").asString("");
        procedures.put(proccode, procedureSignature(prototype));
      }
    }
    // one body per script; scripts sharing a trigger each get their own method,
    // so "stop this script" (return;) ends only its own script
    List<String> flags = new ArrayList<>();
    List<String> forevers = new ArrayList<>();
    Map<String, List<String>> keys = new LinkedHashMap<>();
    Map<String, List<String>> messages = new LinkedHashMap<>();
    Map<String, List<String>> backdrops = new LinkedHashMap<>();
    List<String> clicked = new ArrayList<>();
    List<String> asClone = new ArrayList<>();
    List<String> cloneForevers = new ArrayList<>();
    StringBuilder methods = new StringBuilder();
    StringBuilder helpers = new StringBuilder();
    StringBuilder other = new StringBuilder();
    int scripts = 0;
    for (var entry : blocks.properties()) {
      JsonNode hat = entry.getValue();
      if (!hat.path("topLevel").asBoolean(false) || hat.path("shadow").asBoolean(false)
          || !hat.isObject()) {
        continue;
      }
      scripts++;
      String next = hat.path("next").asString(null);
      switch (op(hat)) {
        case "event_whenflagclicked" -> flagScript(next, flags, forevers);
        case "event_whenkeypressed" -> keys
            .computeIfAbsent(field(hat, "KEY_OPTION"), k -> new ArrayList<>())
            .add(statements(next, "    "));
        case "event_whenthisspriteclicked", "event_whenstageclicked" ->
            clicked.add(statements(next, "    "));
        case "event_whenbroadcastreceived" -> messages
            .computeIfAbsent(field(hat, "BROADCAST_OPTION"), k -> new ArrayList<>())
            .add(statements(next, "    "));
        case "event_whenbackdropswitchesto" -> backdrops
            .computeIfAbsent(field(hat, "BACKDROP"), k -> new ArrayList<>())
            .add(statements(next, "    "));
        case "procedures_definition" -> methods.append(procedure(hat));
        case "control_start_as_clone" -> {
          if (this.clones && !this.stage) {
            // like a green flag script: a trailing forever runs every frame, in clones only
            usesClones = true;
            flagScript(next, asClone, cloneForevers);
          } else {
            other.append("  // TODO Scratch: a script starting with \"").append(describe(hat))
                .append("\" (").append(count(next) + 1).append(" blocks) was not converted.\n");
            todos++;
          }
        }
        default -> {
          other.append("  // TODO Scratch: a script starting with \"").append(describe(hat))
              .append("\" (").append(count(next) + 1).append(" blocks) was not converted.\n");
          todos++;
        }
      }
    }
    String constructor = dispatch(flags, "whenGreenFlag", "", helpers);
    String run = dispatch(forevers, "forever", "", helpers);
    if (usesClones) {
      // In Scratch a clone runs only its "when I start as a clone" scripts, but
      // run() is called for the original and every clone alike.
      String original = run;
      String asCloneRun = dispatch(cloneForevers, "foreverAsClone", "", helpers);
      run = (original.isEmpty() ? "" : "    if (!this.isClone()) {\n" + shift(original, "  ")
          + "    }\n")
          + (asCloneRun.isEmpty() ? "" : "    if (this.isClone()) {\n" + shift(asCloneRun, "  ")
          + "    }\n");
    }
    if (!keys.isEmpty()) {
      methods.append("\n  public void whenKeyPressed(KeyCode keyCode) {\n");
      for (var key : keys.entrySet()) {
        String code = keyCode(key.getKey());
        String name = "whenKey" + Sb3Importer.javaName(key.getKey(), true);
        if (code == null) {
          methods.append("    // any key\n").append(dispatch(key.getValue(), name, "", helpers));
        } else {
          methods.append("    if (keyCode == KeyCode.").append(code).append(") {\n")
              .append(dispatch(key.getValue(), name, "  ", helpers)).append("    }\n");
        }
      }
      methods.append("  }\n");
    }
    if (!clicked.isEmpty() && !stage) {
      methods.append("\n  public void whenClicked() {\n")
          .append(dispatch(clicked, "whenThisSpriteClicked", "", helpers)).append("  }\n");
    } else if (!clicked.isEmpty()) {
      other.append("  // TODO Scratch: \"when stage clicked\" scripts were not converted:\n");
      for (String body : clicked) {
        other.append(commentOut(body));
      }
      todos++;
    }
    if (!asClone.isEmpty()) {
      methods.append("\n  public void whenStartsAsClone() {\n")
          .append(dispatch(asClone, "whenIStartAsAClone", "", helpers)).append("  }\n");
    }
    if (!messages.isEmpty()) {
      methods.append("\n  public void whenIReceive(String message) {\n");
      for (var message : messages.entrySet()) {
        methods.append("    if (message.equals(").append(literal(message.getKey()))
            .append(")) {\n")
            .append(dispatch(message.getValue(), "whenIReceive"
                + Sb3Importer.javaName(message.getKey(), true), "  ", helpers))
            .append("    }\n");
      }
      methods.append("  }\n");
    }
    if (!backdrops.isEmpty()) {
      methods.append("\n  public void whenBackdropSwitches(String name) {\n");
      for (var backdrop : backdrops.entrySet()) {
        methods.append("    if (name.equals(").append(literal(backdrop.getKey()))
            .append(")) {\n")
            .append(dispatch(backdrop.getValue(), "whenBackdropSwitchesTo"
                + Sb3Importer.javaName(backdrop.getKey(), true), "  ", helpers))
            .append("    }\n");
      }
      methods.append("  }\n");
    }
    methods.append(helpers);
    return new Output(ownFields, constructor, run,
        methods + (other.length() > 0 ? "\n" + other : ""), scripts, todos, needsValues);
  }

  /** Statements before a trailing forever run once; the forever body runs every frame. */
  /** Statements before a trailing forever run once; the forever body runs every frame. */
  private void flagScript(String first, List<String> flags, List<String> forevers) {
    List<String> chain = chain(first);
    String forever = null;
    if (!chain.isEmpty() && "control_forever".equals(op(blocks.path(chain.get(chain.size() - 1))))) {
      forever = chain.remove(chain.size() - 1);
    }
    StringBuilder once = new StringBuilder();
    for (String id : chain) {
      // with a forever after them, even the last of these blocks did not end the script
      once.append(statement(id, "    ", forever == null ? chain : withSentinel(chain), true));
    }
    if (once.length() > 0) {
      flags.add(once.toString());
    }
    if (forever != null) {
      String body = foreverBody(input(blocks.path(forever), "SUBSTACK"), "    ");
      if (!body.isEmpty()) {
        forevers.add(body);
      }
    }
  }

  /**
   * The code for one trigger: a single script inline (shifted by {@code extra}),
   * several scripts as calls to one method each (appended to {@code helpers}).
   */
  private static String dispatch(List<String> bodies, String baseName, String extra,
      StringBuilder helpers) {
    if (bodies.isEmpty()) {
      return "";
    }
    if (bodies.size() == 1) {
      return shift(bodies.get(0), extra);
    }
    StringBuilder calls = new StringBuilder();
    for (int i = 0; i < bodies.size(); i++) {
      String name = Character.toLowerCase(baseName.charAt(0)) + baseName.substring(1) + (i + 1);
      calls.append("    ").append(extra).append("this.").append(name).append("();\n");
      helpers.append("\n  private void ").append(name).append("() {\n").append(bodies.get(i))
          .append("  }\n");
    }
    return calls.toString();
  }

  private static String shift(String code, String extra) {
    if (extra.isEmpty()) {
      return code;
    }
    StringBuilder sb = new StringBuilder();
    for (String line : code.split("\n")) {
      sb.append(line.isEmpty() ? "" : extra + line).append('\n');
    }
    return sb.toString();
  }

  /**
   * A forever body: with exactly one wait at its start or end it becomes
   * {@code if (this.getTimer("..").everyMillis(ms)) { ... }} - Scratch for
   * Java's way of "do this every N seconds".
   */
  private String foreverBody(String first, String indent) {
    List<String> chain = chain(first);
    List<Integer> waits = new ArrayList<>();
    for (int i = 0; i < chain.size(); i++) {
      if ("control_wait".equals(op(blocks.path(chain.get(i))))) {
        waits.add(i);
      }
    }
    if (waits.size() == 1 && (waits.get(0) == 0 || waits.get(0) == chain.size() - 1)
        && chain.size() > 1) {
      String wait = chain.remove((int) waits.get(0));
      Expr seconds = num(value(blocks.path(wait), "DURATION"));
      String timer = "scratch" + (++timers);
      StringBuilder sb = new StringBuilder(indent).append("if (this.getTimer(\"").append(timer)
          .append("\").everyMillis((int) (").append(seconds.code()).append(" * 1000))) {\n");
      for (String id : chain) {
        sb.append(statement(id, indent + "  ", chain, false));
      }
      return sb.append(indent).append("}\n").toString();
    }
    StringBuilder sb = new StringBuilder();
    for (String id : chain) {
      sb.append(statement(id, indent, chain, false));
    }
    return sb.toString();
  }

  private static List<String> withSentinel(List<String> chain) {
    List<String> copy = new ArrayList<>(chain);
    copy.add("\u0000forever");
    return copy;
  }

  private String statements(String first, String indent) {
    List<String> chain = chain(first);
    StringBuilder sb = new StringBuilder();
    for (String id : chain) {
      sb.append(statement(id, indent, chain, true));
    }
    return sb.toString();
  }

  private List<String> chain(String first) {
    List<String> ids = new ArrayList<>();
    for (String id = first; id != null && blocks.has(id); ) {
      ids.add(id);
      id = blocks.path(id).path("next").asString(null);
    }
    return ids;
  }

  private int count(String first) {
    return chain(first).size();
  }

  /** One block as Java (one or more lines ending in a newline). */
  private String statement(String id, String indent, List<String> chain, boolean once) {
    JsonNode b = blocks.path(id);
    boolean last = chain.indexOf(id) == chain.size() - 1;
    String code;
    switch (op(b)) {
      case "motion_movesteps" -> code = call("move", num(value(b, "STEPS")));
      case "motion_turnright" -> code = call("turnRight", num(value(b, "DEGREES")));
      case "motion_turnleft" -> code = call("turnLeft", num(value(b, "DEGREES")));
      case "motion_gotoxy" -> code = call("setPosition", num(value(b, "X")), num(value(b, "Y")));
      case "motion_changexby" -> code = call("changeX", num(value(b, "DX")));
      case "motion_changeyby" -> code = call("changeY", num(value(b, "DY")));
      case "motion_setx" -> code = call("setX", num(value(b, "X")));
      case "motion_sety" -> code = call("setY", num(value(b, "Y")));
      case "motion_pointindirection" -> code = call("setDirection", num(value(b, "DIRECTION")));
      case "motion_ifonedgebounce" -> code = "this.ifOnEdgeBounce();";
      case "motion_setrotationstyle" -> code = "this.setRotationStyle(RotationStyle."
          + switch (field(b, "STYLE")) {
            case "left-right" -> "LEFT_RIGHT";
            case "don't rotate" -> "DONT";
            default -> "ALL_AROUND";
          } + ");";
      case "motion_goto" -> code = switch (menu(b, "TO")) {
        case "_mouse_" -> "this.goToMousePointer();";
        case "_random_" -> "this.goToRandomPosition();";
        default -> null;
      };
      case "motion_pointtowards" -> code = "_mouse_".equals(menu(b, "TOWARDS"))
          ? "this.pointTowardsMousePointer();" : null;
      case "motion_glidesecstoxy" -> code = waits(call("glide", num(value(b, "SECS")),
          num(value(b, "X")), num(value(b, "Y"))), last);
      case "looks_say" -> code = call("say", str(value(b, "MESSAGE")));
      case "looks_think" -> code = call("think", str(value(b, "MESSAGE")));
      case "looks_sayforsecs" -> code = waits("this.say(" + str(value(b, "MESSAGE")).code()
          + ", (int) (" + num(value(b, "SECS")).code() + " * 1000));", last);
      case "looks_thinkforsecs" -> code = waits("this.think(" + str(value(b, "MESSAGE")).code()
          + ", (int) (" + num(value(b, "SECS")).code() + " * 1000));", last);
      case "looks_show" -> code = stage ? null : "this.show();";
      case "looks_hide" -> code = stage ? null : "this.hide();";
      case "looks_switchcostumeto" -> code = call("switchCostume", str(menuValue(b, "COSTUME")));
      case "looks_nextcostume" -> code = "this.nextCostume();";
      case "looks_switchbackdropto" -> code = (stage ? "this" : "this.getStage()")
          + ".switchBackdrop(" + str(menuValue(b, "BACKDROP")).code() + ");";
      case "looks_nextbackdrop" -> code = (stage ? "this" : "this.getStage()") + ".nextBackdrop();";
      case "looks_changesizeby" -> code = call("changeSize", num(value(b, "CHANGE")));
      case "looks_setsizeto" -> code = call("setSize", num(value(b, "SIZE")));
      case "looks_gotofrontback" -> code = "front".equals(field(b, "FRONT_BACK"))
          ? "this.goToFrontLayer();" : "this.goToBackLayer();";
      case "looks_changeeffectby" -> code = switch (field(b, "EFFECT").toLowerCase(Locale.ROOT)) {
        case "color" -> call("changeTint", num(value(b, "CHANGE")));
        case "ghost" -> call("changeTransparency", num(value(b, "CHANGE")));
        default -> null;
      };
      case "looks_seteffectto" -> code = switch (field(b, "EFFECT").toLowerCase(Locale.ROOT)) {
        case "color" -> call("setTint", num(value(b, "VALUE")));
        case "ghost" -> call("setTransparency", num(value(b, "VALUE")));
        default -> null;
      };
      case "sound_play" -> code = call("playSound", str(menuValue(b, "SOUND_MENU")));
      case "sound_playuntildone" -> code = waits(call("playSound",
          str(menuValue(b, "SOUND_MENU"))), last);
      case "sound_stopallsounds" -> code = "this.stopAllSounds();";
      case "sound_setvolumeto" -> code = call("setVolume", num(value(b, "VOLUME")));
      case "sound_changevolumeby" -> code = call("changeVolume", num(value(b, "VOLUME")));
      // only "of myself": another sprite's clone needs that sprite, which the class does not know
      case "control_create_clone_of" -> {
        code = this.clones && !this.stage && "_myself_".equals(menu(b, "CLONE_OPTION"))
            ? "this.clone();" : null;
        usesClones |= code != null;
      }
      case "control_delete_this_clone" -> code = this.clones && !this.stage
          ? "this.deleteThisClone();" : null;
      case "event_broadcast" -> code = call("broadcast", str(value(b, "BROADCAST_INPUT")));
      case "event_broadcastandwait" -> code = waits(call("broadcast",
          str(value(b, "BROADCAST_INPUT"))), last);
      case "control_if" -> code = "if (" + bool(value(b, "CONDITION")).code() + ") {\n"
          + statements(input(b, "SUBSTACK"), indent + "  ") + indent + "}";
      case "control_if_else" -> code = "if (" + bool(value(b, "CONDITION")).code() + ") {\n"
          + statements(input(b, "SUBSTACK"), indent + "  ") + indent + "} else {\n"
          + statements(input(b, "SUBSTACK2"), indent + "  ") + indent + "}";
      case "control_repeat" -> {
        if (containsWait(input(b, "SUBSTACK"))) {
          code = null;
        } else {
          String counter = "i" + (loopDepth == 0 ? "" : String.valueOf(loopDepth + 1));
          loopDepth++;
          String body = statements(input(b, "SUBSTACK"), indent + "  ");
          loopDepth--;
          code = "for (int " + counter + " = 0; " + counter + " < " + num(value(b, "TIMES")).code()
              + "; " + counter + "++) {\n" + body + indent + "}";
        }
      }
      case "control_stop" -> code = switch (field(b, "STOP_OPTION")) {
        case "all" -> "Window.getInstance().exit();";
        case "this script" -> "return;";
        default -> null;
      };
      case "data_setvariableto" -> code = assign(b, "VALUE", false);
      case "data_changevariableby" -> code = assign(b, "VALUE", true);
      case "data_addtolist" -> code = list(b) == null ? null
          : list(b) + ".add(" + obj(value(b, "ITEM")) + ");";
      case "data_deletealloflist" -> code = list(b) == null ? null : list(b) + ".clear();";
      case "data_deleteoflist" -> code = list(b) == null ? null : values() + ".delete(" + list(b)
          + ", " + num(value(b, "INDEX")).code() + ");";
      case "data_insertatlist" -> code = list(b) == null ? null : values() + ".insert(" + list(b)
          + ", " + num(value(b, "INDEX")).code() + ", " + obj(value(b, "ITEM")) + ");";
      case "data_replaceitemoflist" -> code = list(b) == null ? null : values() + ".replace("
          + list(b) + ", " + num(value(b, "INDEX")).code() + ", " + obj(value(b, "ITEM")) + ");";
      case "procedures_call" -> code = procedureCall(b);
      case "sensing_resettimer" -> code = null;
      default -> code = null;
    }
    if (code == null) {
      todos++;
      return indent + "// TODO Scratch: " + describe(b) + "\n";
    }
    return indent + code.replace("\n" + indent + "}", "\n" + indent + "}") + "\n";
  }

  /** A block that waited in Scratch: fine as the last one, flagged in the middle. */
  private String waits(String code, boolean last) {
    if (last) {
      return code;
    }
    todos++;
    return code + " // TODO Scratch: this block waited; the next blocks now run at once";
  }

  private boolean containsWait(String first) {
    for (String id : chain(first)) {
      JsonNode b = blocks.path(id);
      String op = op(b);
      if (op.equals("control_wait") || op.equals("control_wait_until")
          || op.endsWith("forsecs") || op.equals("motion_glidesecstoxy")
          || op.equals("sound_playuntildone") || op.equals("event_broadcastandwait")) {
        return true;
      }
      for (String stack : new String[] {"SUBSTACK", "SUBSTACK2"}) {
        String inner = input(b, stack);
        if (inner != null && containsWait(inner)) {
          return true;
        }
      }
    }
    return false;
  }

  private String assign(JsonNode b, String input, boolean change) {
    Variable variable = variables.get(fieldId(b, "VARIABLE"));
    if (variable == null || variable.list()) {
      return null;
    }
    String target = variable.java();
    Expr value = value(b, input);
    if (variable.number()) {
      return target + (change ? " += " : " = ") + num(value).code() + ";";
    }
    if (change) {
      needsValues = true;
      return target + " = ScratchValues.str(ScratchValues.num(" + target + ") + "
          + num(value).code() + ");";
    }
    return target + " = " + str(value).code() + ";";
  }

  private String list(JsonNode b) {
    Variable variable = variables.get(fieldId(b, "LIST"));
    return variable == null || !variable.list() ? null : variable.java();
  }

  private String procedureCall(JsonNode b) {
    String proccode = b.path("mutation").path("proccode").asString("");
    String[] signature = procedures.get(proccode);
    if (signature == null) {
      return null;
    }
    List<String> ids = jsonList(b.path("mutation").path("argumentids").asString("[]"));
    List<String> args = new ArrayList<>();
    for (int i = 0; i < ids.size(); i++) {
      Expr value = value(b, ids.get(i));
      String kind = i + 2 < signature.length ? signature[i + 2] : "s";
      args.add(switch (kind) {
        case "n" -> num(value).code();
        case "b" -> bool(value).code();
        default -> str(value).code();
      });
    }
    return "this." + signature[0] + "(" + String.join(", ", args) + ");";
  }

  /** [method name, parameter list, kinds...] from a procedures_prototype. */
  private String[] procedureSignature(JsonNode prototype) {
    String proccode = prototype.path("mutation").path("proccode").asString("block");
    String words = proccode.replaceAll("%[snb]", " ");
    String name = Sb3Importer.javaName(words, false);
    name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
    List<String> names = jsonList(prototype.path("mutation").path("argumentnames").asString("[]"));
    List<String> kinds = new ArrayList<>();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("%([snb])").matcher(proccode);
    while (m.find()) {
      kinds.add(m.group(1));
    }
    StringBuilder params = new StringBuilder();
    for (int i = 0; i < names.size(); i++) {
      String kind = i < kinds.size() ? kinds.get(i) : "s";
      params.append(i == 0 ? "" : ", ").append(switch (kind) {
        case "n" -> "double ";
        case "b" -> "boolean ";
        default -> "String ";
      }).append(parameter(names.get(i)));
    }
    String[] out = new String[2 + kinds.size()];
    out[0] = name;
    out[1] = params.toString();
    for (int i = 0; i < kinds.size(); i++) {
      out[2 + i] = kinds.get(i);
    }
    return out;
  }

  private String procedure(JsonNode definition) {
    JsonNode prototype = blocks.path(input(definition, "custom_block"));
    String[] signature = procedures.get(prototype.path("mutation").path("proccode").asString(""));
    List<String> names = jsonList(prototype.path("mutation").path("argumentnames").asString("[]"));
    Map<String, String> kinds = new LinkedHashMap<>();
    for (int i = 0; i < names.size(); i++) {
      kinds.put(parameter(names.get(i)), i + 2 < signature.length ? signature[i + 2] : "s");
    }
    parameters = kinds;
    String body = statements(definition.path("next").asString(null), "    ");
    parameters = Map.of();
    return "\n  // Scratch custom block \"" + prototype.path("mutation").path("proccode")
        .asString("").replace("*/", "") + "\"\n  void " + signature[0] + "(" + signature[1]
        + ") {\n" + body + "  }\n";
  }

  private static String parameter(String scratchName) {
    String name = Sb3Importer.javaName(scratchName, false);
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  // --- expressions ---------------------------------------------------------------

  /** The value plugged into an input (a literal shadow or a reporter block). */
  private Expr value(JsonNode block, String name) {
    JsonNode input = block.path("inputs").path(name);
    if (input.isMissingNode() || input.size() < 2) {
      return new Expr("0", Type.NUM);
    }
    JsonNode slot = input.get(1);
    if (slot.isString()) {
      JsonNode inner = blocks.path(slot.asString());
      if (inner.path("shadow").asBoolean(false)) {
        return shadow(inner);
      }
      return reporter(inner);
    }
    if (slot.isArray()) {
      return primitive(slot);
    }
    return new Expr("0", Type.NUM);
  }

  /** A menu shadow's chosen value, e.g. the costume name of a costume menu. */
  private Expr menuValue(JsonNode block, String name) {
    JsonNode input = block.path("inputs").path(name);
    if (input.size() >= 2 && input.get(1).isString()) {
      JsonNode inner = blocks.path(input.get(1).asString());
      if (inner.path("shadow").asBoolean(false)) {
        for (var field : inner.path("fields").properties()) {
          return new Expr(literal(field.getValue().path(0).asString("")), Type.STR);
        }
      }
      return reporter(inner);
    }
    return new Expr("\"\"", Type.STR);
  }

  private String menu(JsonNode block, String name) {
    Expr value = menuValue(block, name);
    return value.type() == Type.STR && value.code().startsWith("\"")
        ? value.code().substring(1, value.code().length() - 1) : "";
  }

  private Expr shadow(JsonNode shadow) {
    for (var field : shadow.path("fields").properties()) {
      String text = field.getValue().path(0).asString("");
      return numeric(text) && !"text".equals(op(shadow)) ? new Expr(number(text), Type.NUM)
          : new Expr(literal(text), Type.STR);
    }
    return new Expr("0", Type.NUM);
  }

  /** [4, "10"] numbers (4-8), [10, "text"], [11, broadcast], [12, variable], [13, list]. */
  private Expr primitive(JsonNode p) {
    int kind = p.path(0).asInt(10);
    String text = p.path(1).asString("");
    return switch (kind) {
      case 4, 5, 6, 7, 8 -> new Expr(numeric(text) ? number(text) : "0", Type.NUM);
      case 12, 13 -> variableRead(p.path(2).asString(""));
      default -> new Expr(literal(text), Type.STR);
    };
  }

  private Expr variableRead(String id) {
    Variable variable = variables.get(id);
    if (variable == null) {
      todos++;
      return new Expr("0 /* TODO Scratch: unknown variable */", Type.NUM);
    }
    if (variable.list()) {
      needsValues = true;
      return new Expr("ScratchValues.join(" + variable.java() + ")", Type.STR);
    }
    return new Expr(variable.java(), variable.number() ? Type.NUM : Type.STR);
  }

  private Expr reporter(JsonNode b) {
    String self = "this";
    switch (op(b)) {
      case "operator_add": return arithmetic(b, "+");
      case "operator_subtract": return arithmetic(b, "-");
      case "operator_multiply": return arithmetic(b, "*");
      case "operator_divide": return arithmetic(b, "/");
      case "operator_mod":
        needsValues = true;
        return new Expr("ScratchValues.mod(" + num(value(b, "NUM1")).code() + ", "
            + num(value(b, "NUM2")).code() + ")", Type.NUM);
      case "operator_random": {
        Expr from = num(value(b, "FROM"));
        Expr to = num(value(b, "TO"));
        boolean whole = from.code().matches("-?\\d+") && to.code().matches("-?\\d+");
        return new Expr(whole ? "Random.randomInt(" + from.code() + ", " + to.code() + ")"
            : "Random.random(" + from.code() + ", " + to.code() + ")", Type.NUM);
      }
      case "operator_gt": return compare(b, ">");
      case "operator_lt": return compare(b, "<");
      case "operator_equals":
        needsValues = true;
        return new Expr("ScratchValues.equal(" + obj(value(b, "OPERAND1")) + ", "
            + obj(value(b, "OPERAND2")) + ")", Type.BOOL);
      case "operator_and": return new Expr("(" + bool(value(b, "OPERAND1")).code() + " && "
          + bool(value(b, "OPERAND2")).code() + ")", Type.BOOL);
      case "operator_or": return new Expr("(" + bool(value(b, "OPERAND1")).code() + " || "
          + bool(value(b, "OPERAND2")).code() + ")", Type.BOOL);
      case "operator_not": return new Expr("!" + bool(value(b, "OPERAND")).code(), Type.BOOL);
      case "operator_join": return new Expr(str(value(b, "STRING1")).code() + " + "
          + str(value(b, "STRING2")).code(), Type.STR);
      case "operator_letter_of":
        needsValues = true;
        return new Expr("ScratchValues.letter(" + num(value(b, "LETTER")).code() + ", "
            + str(value(b, "STRING")).code() + ")", Type.STR);
      // parenthesized: a join ("a" + b) must be measured as a whole
      case "operator_length": return new Expr("(" + str(value(b, "STRING")).code()
          + ").length()", Type.NUM);
      case "operator_contains":
        needsValues = true;
        return new Expr("ScratchValues.contains(" + str(value(b, "STRING1")).code() + ", "
            + str(value(b, "STRING2")).code() + ")", Type.BOOL);
      case "operator_round": return new Expr("Math.round(" + num(value(b, "NUM")).code() + ")",
          Type.NUM);
      case "operator_mathop": {
        String x = num(value(b, "NUM")).code();
        String code = switch (field(b, "OPERATOR")) {
          case "abs" -> "Math.abs(" + x + ")";
          case "floor" -> "Math.floor(" + x + ")";
          case "ceiling" -> "Math.ceil(" + x + ")";
          case "sqrt" -> "Math.sqrt(" + x + ")";
          case "sin" -> "Math.sin(Math.toRadians(" + x + "))";
          case "cos" -> "Math.cos(Math.toRadians(" + x + "))";
          case "tan" -> "Math.tan(Math.toRadians(" + x + "))";
          case "asin" -> "Math.toDegrees(Math.asin(" + x + "))";
          case "acos" -> "Math.toDegrees(Math.acos(" + x + "))";
          case "atan" -> "Math.toDegrees(Math.atan(" + x + "))";
          case "ln" -> "Math.log(" + x + ")";
          case "log" -> "Math.log10(" + x + ")";
          case "e ^" -> "Math.exp(" + x + ")";
          case "10 ^" -> "Math.pow(10, " + x + ")";
          default -> null;
        };
        if (code != null) {
          return new Expr(code, Type.NUM);
        }
        break;
      }
      case "motion_xposition": return new Expr(self + ".getX()", Type.NUM);
      case "motion_yposition": return new Expr(self + ".getY()", Type.NUM);
      case "motion_direction": return new Expr(self + ".getDirection()", Type.NUM);
      case "looks_size": return new Expr(self + ".getSize()", Type.NUM);
      case "looks_costumenumbername":
        return "name".equals(field(b, "NUMBER_NAME"))
            ? new Expr(self + ".getCurrentCostumeName()", Type.STR)
            : new Expr("(" + self + ".getCurrentCostumeIndex() + 1)", Type.NUM);
      case "looks_backdropnumbername": {
        String stageRef = stage ? "this" : "this.getStage()";
        return "name".equals(field(b, "NUMBER_NAME"))
            ? new Expr(stageRef + ".getCurrentBackdropName()", Type.STR)
            : new Expr("(" + stageRef + ".getCurrentBackdropIndex() + 1)", Type.NUM);
      }
      case "sound_volume": return new Expr(self + ".getVolume()", Type.NUM);
      case "sensing_mousex": return new Expr(self + ".getMouseX()", Type.NUM);
      case "sensing_mousey": return new Expr(self + ".getMouseY()", Type.NUM);
      case "sensing_mousedown": return new Expr(self + ".isMouseDown()", Type.BOOL);
      case "sensing_timer": return new Expr("(Timer.millis() / 1000.0)", Type.NUM);
      case "sensing_keypressed": {
        String key = keyCode(menu(b, "KEY_OPTION"));
        if (key != null) {
          return new Expr(self + ".isKeyPressed(KeyCode." + key + ")", Type.BOOL);
        }
        break;
      }
      case "sensing_touchingobject": {
        if (stage) {
          break;
        }
        String target = menu(b, "TOUCHINGOBJECTMENU");
        if (target.equals("_mouse_")) {
          return new Expr("this.isTouchingMousePointer()", Type.BOOL);
        }
        if (target.equals("_edge_")) {
          return new Expr("this.isTouchingEdge()", Type.BOOL);
        }
        if (spriteClasses.containsKey(target)) {
          return new Expr("this.isTouchingSprite(" + spriteClasses.get(target) + ".class)",
              Type.BOOL);
        }
        break;
      }
      case "sensing_distanceto":
        if (!stage && "_mouse_".equals(menu(b, "DISTANCETOMENU"))) {
          return new Expr("this.distanceToMousePointer()", Type.NUM);
        }
        break;
      case "data_variable": return variableRead(fieldId(b, "VARIABLE"));
      case "data_listcontents": return variableRead(fieldId(b, "LIST"));
      case "data_itemoflist": {
        String list = list(b);
        if (list != null) {
          needsValues = true;
          return new Expr("ScratchValues.item(" + list + ", " + num(value(b, "INDEX")).code()
              + ")", Type.OBJ);
        }
        break;
      }
      case "data_lengthoflist": {
        String list = list(b);
        if (list != null) {
          return new Expr(list + ".size()", Type.NUM);
        }
        break;
      }
      case "data_listcontainsitem": {
        String list = list(b);
        if (list != null) {
          needsValues = true;
          return new Expr("ScratchValues.contains(" + list + ", " + obj(value(b, "ITEM")) + ")",
              Type.BOOL);
        }
        break;
      }
      case "argument_reporter_string_number": {
        String name = parameter(field(b, "VALUE"));
        String kind = parameters.getOrDefault(name, "s");
        return new Expr(name, kind.equals("n") ? Type.NUM : kind.equals("b") ? Type.BOOL
            : Type.STR);
      }
      case "argument_reporter_boolean":
        return new Expr(parameter(field(b, "VALUE")), Type.BOOL);
      default:
        break;
    }
    todos++;
    return new Expr("0 /* TODO Scratch: " + describe(b).replace("*/", "") + " */", Type.NUM);
  }

  private Expr arithmetic(JsonNode b, String operator) {
    return new Expr("(" + num(value(b, "NUM1")).code() + " " + operator + " "
        + num(value(b, "NUM2")).code() + ")", Type.NUM);
  }

  private Expr compare(JsonNode b, String operator) {
    Expr left = value(b, "OPERAND1");
    Expr right = value(b, "OPERAND2");
    if (left.type() == Type.STR && !left.code().matches("\"-?[0-9.]+\"")
        || right.type() == Type.STR && !right.code().matches("\"-?[0-9.]+\"")) {
      needsValues = true;
      return new Expr("(ScratchValues.compare(" + obj(left) + ", " + obj(right) + ") "
          + operator + " 0)", Type.BOOL);
    }
    return new Expr("(" + num(left).code() + " " + operator + " " + num(right).code() + ")",
        Type.BOOL);
  }

  private Expr num(Expr e) {
    return switch (e.type()) {
      case NUM -> e;
      case STR -> e.code().matches("\"-?[0-9]+(\\.[0-9]+)?\"")
          ? new Expr(e.code().substring(1, e.code().length() - 1), Type.NUM)
          : withValues("ScratchValues.num(" + e.code() + ")", Type.NUM);
      case BOOL -> new Expr("(" + e.code() + " ? 1 : 0)", Type.NUM);
      case OBJ -> withValues("ScratchValues.num(" + e.code() + ")", Type.NUM);
    };
  }

  private Expr str(Expr e) {
    return switch (e.type()) {
      case STR -> e;
      case NUM, OBJ -> withValues("ScratchValues.str(" + e.code() + ")", Type.STR);
      case BOOL -> new Expr("String.valueOf(" + e.code() + ")", Type.STR);
    };
  }

  private Expr bool(Expr e) {
    return e.type() == Type.BOOL ? e : withValues("ScratchValues.bool(" + e.code() + ")",
        Type.BOOL);
  }

  private String obj(Expr e) {
    return e.code();
  }

  private Expr withValues(String code, Type type) {
    needsValues = true;
    return new Expr(code, type);
  }

  private String call(String method, Expr... args) {
    StringBuilder sb = new StringBuilder("this.").append(method).append('(');
    for (int i = 0; i < args.length; i++) {
      sb.append(i == 0 ? "" : ", ").append(args[i].code());
    }
    return sb.append(");").toString();
  }

  private String values() {
    needsValues = true;
    return "ScratchValues";
  }

  // --- block access ----------------------------------------------------------------

  private static String op(JsonNode block) {
    return block.path("opcode").asString("");
  }

  private static String input(JsonNode block, String name) {
    JsonNode input = block.path("inputs").path(name);
    return input.size() >= 2 && input.get(1).isString() ? input.get(1).asString() : null;
  }

  private static String field(JsonNode block, String name) {
    return block.path("fields").path(name).path(0).asString("");
  }

  private static String fieldId(JsonNode block, String name) {
    return block.path("fields").path(name).path(1).asString("");
  }

  /** "move (10) steps"-like text of a block, for TODO lines. */
  private String describe(JsonNode b) {
    StringBuilder sb = new StringBuilder(op(b).replaceFirst("^[a-z]+_", "").replace('_', ' '));
    for (var field : b.path("fields").properties()) {
      sb.append(" [").append(field.getValue().path(0).asString("")).append(']');
    }
    for (var input : b.path("inputs").properties()) {
      JsonNode slot = input.getValue().path(1);
      if (slot.isArray()) {
        sb.append(" (").append(slot.path(1).asString("")).append(')');
      } else if (slot.isString() && blocks.path(slot.asString()).path("shadow").asBoolean(false)) {
        for (var field : blocks.path(slot.asString()).path("fields").properties()) {
          sb.append(" (").append(field.getValue().path(0).asString("")).append(')');
        }
      }
    }
    return sb.toString().replace("\n", " ");
  }

  private static String keyCode(String key) {
    String k = key.toLowerCase(Locale.ROOT);
    return switch (k) {
      case "space" -> "SPACE";
      case "left arrow" -> "LEFT";
      case "right arrow" -> "RIGHT";
      case "up arrow" -> "UP";
      case "down arrow" -> "DOWN";
      case "enter" -> "ENTER";
      case "any" -> null;
      default -> k.matches("[a-z]") ? k.toUpperCase(Locale.ROOT)
          : k.matches("[0-9]") ? "DIGIT_" + k : null;
    };
  }

  private static boolean numeric(String text) {
    return text.matches("\\s*-?([0-9]+\\.?[0-9]*|\\.[0-9]+)\\s*");
  }

  /**
   * A Scratch number as a Java literal: leading zeros dropped ("010" would be
   * octal 8, "08" does not compile) and integers beyond int range written as
   * doubles (an int literal that large does not compile).
   */
  private static String number(String text) {
    String t = text.strip();
    boolean negative = t.startsWith("-");
    if (negative) {
      t = t.substring(1);
    }
    int dot = t.indexOf('.');
    String whole = (dot < 0 ? t : t.substring(0, dot)).replaceFirst("^0+", "");
    if (whole.isEmpty()) {
      whole = "0";
    }
    String out;
    if (dot < 0) {
      out = new java.math.BigInteger(whole).bitLength() < 32 ? whole : whole + ".0";
    } else {
      String fraction = t.substring(dot + 1);
      out = whole + "." + (fraction.isEmpty() ? "0" : fraction);
    }
    return negative ? "-" + out : out;
  }

  static String literal(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  private static String commentOut(String code) {
    StringBuilder sb = new StringBuilder();
    for (String line : code.split("\n")) {
      sb.append("  // ").append(line.strip()).append('\n');
    }
    return sb.toString();
  }

  private static List<String> jsonList(String json) {
    List<String> out = new ArrayList<>();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"")
        .matcher(json);
    while (m.find()) {
      out.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
    }
    return out;
  }

  /** The helper class for Scratch's loose values, written once into a project that needs it. */
  static final String VALUES_CLASS = """
      import java.util.List;

      // Scratch's values are loose: "10" and 10 are equal, lists start at 1.
      // The .sb3 import uses these helpers where Java is stricter.
      final class ScratchValues {

        private ScratchValues() {}

        static double num(Object value) {
          if (value instanceof Number n) {
            return n.doubleValue();
          }
          if (value instanceof Boolean b) {
            return b ? 1 : 0;
          }
          try {
            return Double.parseDouble(String.valueOf(value).trim());
          } catch (NumberFormatException e) {
            return 0;
          }
        }

        static String str(Object value) {
          if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf(d.longValue());
          }
          return String.valueOf(value);
        }

        static String str(double value) {
          return str((Object) value);
        }

        static boolean bool(Object value) {
          if (value instanceof Boolean b) {
            return b;
          }
          String text = String.valueOf(value);
          return !(text.isEmpty() || text.equals("0") || text.equalsIgnoreCase("false"));
        }

        static int compare(Object a, Object b) {
          String x = String.valueOf(a).trim();
          String y = String.valueOf(b).trim();
          try {
            return Double.compare(Double.parseDouble(x), Double.parseDouble(y));
          } catch (NumberFormatException e) {
            return x.compareToIgnoreCase(y);
          }
        }

        static boolean equal(Object a, Object b) {
          return compare(str(a), str(b)) == 0;
        }

        static double mod(double a, double b) {
          double m = a % b;
          return m != 0 && (m < 0) != (b < 0) ? m + b : m;
        }

        static String letter(double index, String text) {
          int i = (int) index - 1;
          return i >= 0 && i < text.length() ? String.valueOf(text.charAt(i)) : "";
        }

        static boolean contains(String text, String part) {
          return text.toLowerCase().contains(part.toLowerCase());
        }

        static boolean contains(List<Object> list, Object item) {
          for (Object value : list) {
            if (equal(value, item)) {
              return true;
            }
          }
          return false;
        }

        static Object item(List<Object> list, double index) {
          int i = (int) index - 1;
          return i >= 0 && i < list.size() ? list.get(i) : "";
        }

        static void delete(List<Object> list, double index) {
          int i = (int) index - 1;
          if (i >= 0 && i < list.size()) {
            list.remove(i);
          }
        }

        static void insert(List<Object> list, double index, Object item) {
          int i = Math.max(0, Math.min(list.size(), (int) index - 1));
          list.add(i, item);
        }

        static void replace(List<Object> list, double index, Object item) {
          int i = (int) index - 1;
          if (i >= 0 && i < list.size()) {
            list.set(i, item);
          }
        }

        static String join(List<Object> list) {
          StringBuilder sb = new StringBuilder();
          for (Object value : list) {
            sb.append(sb.length() == 0 ? "" : " ").append(str(value));
          }
          return sb.toString();
        }
      }
      """;
}
