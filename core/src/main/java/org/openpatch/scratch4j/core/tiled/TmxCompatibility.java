package org.openpatch.scratch4j.core.tiled;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks a Tiled map against what Scratch for Java loads and converts it.
 * Tiled's defaults (base64 + zlib, Tiled 1.9's {@code class}) are not what
 * every library version reads; the editor saves the compatible form, and this
 * tells a student what a map made in Tiled needs.
 */
public final class TmxCompatibility {

  /** A problem: a stable key (for EN/DE texts), its details, and whether convert() fixes it. */
  public record Issue(String key, String detail, boolean fixable) {}

  private TmxCompatibility() {}

  /** The library version that loads Tiled's own formats (base64, .tsx, groups, flips, class). */
  public static final String TILED_FORMATS_VERSION = "5.6.0";

  private static final java.util.Set<String> LOADED_SINCE_5_6 = java.util.Set.of(
      "tiled.encoding", "tiled.external", "tiled.group", "tiled.flipped", "tiled.class",
      "tiled.spacing");

  /**
   * The problems for a project on {@code libraryVersion} (null: unknown, so
   * every older library's limits count). From 5.6.0 on the library reads
   * base64/zlib/gzip data (not zstd), .tsx tilesets, groups, flipped tiles,
   * Tiled 1.9's class and tileset margins/spacing itself.
   */
  public static List<Issue> check(TmxDocument doc, String libraryVersion) {
    List<Issue> all = check(doc);
    if (libraryVersion == null || org.openpatch.scratch4j.core.project.LibraryCheck.isNewer(
        TILED_FORMATS_VERSION, libraryVersion)) {
      return all;
    }
    return all.stream().filter(i -> !LOADED_SINCE_5_6.contains(i.key())
        || i.key().equals("tiled.encoding") && i.detail().endsWith("zstd")).toList();
  }

  public static List<Issue> check(TmxDocument doc) {
    List<Issue> issues = new ArrayList<>();
    if (!"orthogonal".equals(doc.orientation)) {
      issues.add(new Issue("tiled.orientation", doc.orientation, false));
    }
    if (doc.infinite) {
      issues.add(new Issue("tiled.infinite", "", false));
    }
    Element map = doc.mapElement();
    for (Element layer : all(map, "layer")) {
      Element data = TmxDocument.first(layer, "data");
      if (data != null && !"csv".equals(data.getAttribute("encoding"))) {
        issues.add(new Issue("tiled.encoding", layer.getAttribute("name") + ": "
            + (data.getAttribute("encoding").isEmpty() ? "xml" : data.getAttribute("encoding"))
            + (data.getAttribute("compression").isEmpty() ? ""
                : "/" + data.getAttribute("compression")), true));
      }
    }
    for (TmxDocument.Tileset t : doc.tilesets) {
      if (t.externalSource != null) {
        issues.add(new Issue("tiled.external", t.externalSource, true));
      }
      if (t.image == null) {
        issues.add(new Issue("tiled.collection", t.name, false));
      }
      if (t.margin != 0 || t.spacing != 0) {
        issues.add(new Issue("tiled.spacing", t.name, false));
      }
    }
    for (TmxDocument.Layer layer : doc.layers) {
      if (!layer.group.isEmpty()) {
        issues.add(new Issue("tiled.group", layer.group + "/" + layer.name, true));
      }
      if (layer instanceof TmxDocument.TileLayer tiles) {
        for (long gid : tiles.gids) {
          if ((gid & 0xE0000000L) != 0) {
            issues.add(new Issue("tiled.flipped", layer.name, true));
            break;
          }
        }
      }
    }
    for (Element object : all(map, "object")) {
      if (object.hasAttribute("class") && !object.hasAttribute("type")) {
        issues.add(new Issue("tiled.class", object.getAttribute("name"), true));
      }
      if (object.hasAttribute("template")) {
        issues.add(new Issue("tiled.template", object.getAttribute("template"), false));
      }
    }
    for (String tag : new String[] {"layer", "objectgroup"}) {
      for (Element layer : all(map, tag)) {
        if (layer.hasAttribute("class") && !layer.hasAttribute("type")) {
          issues.add(new Issue("tiled.class", layer.getAttribute("name"), true));
        }
      }
    }
    return issues;
  }

  /**
   * Fixes what can be fixed: layer groups are flattened (their layers keep
   * their order), flipped tiles are drawn unflipped. CSV, embedded tilesets and
   * {@code type} are written by {@link TmxDocument#save()} anyway.
   */
  public static void convert(TmxDocument doc) {
    for (TmxDocument.Layer layer : doc.layers) {
      if (!layer.group.isEmpty() && layer.element != null) {
        // move the layer out of its group, to where the group is
        Node group = layer.element.getParentNode();
        while (group != null && group.getParentNode() != doc.mapElement()) {
          group = group.getParentNode();
        }
        doc.mapElement().insertBefore(layer.element, group);
        // names stay: code refers to them (getObjectsFromLayer("Objects"))
        layer.group = "";
      }
      if (layer instanceof TmxDocument.TileLayer tiles) {
        for (int i = 0; i < tiles.gids.length; i++) {
          tiles.gids[i] &= 0x1FFFFFFFL;
        }
      }
    }
    for (Element group : TmxDocument.children(doc.mapElement(), "group")) {
      doc.mapElement().removeChild(group);
    }
  }

  private static final Map<String, String[]> TEXTS = Map.ofEntries(
      Map.entry("tiled.orientation", new String[] {
          "The map is {0}: Scratch for Java draws orthogonal maps only.",
          "Die Karte ist {0}: Scratch for Java zeichnet nur orthogonale Karten."}),
      Map.entry("tiled.infinite", new String[] {
          "The map is infinite: untick Infinite in Tiled (Map > Map Properties).",
          "Die Karte ist unendlich: entferne in Tiled das Häkchen bei Unendlich "
              + "(Karte > Karteneigenschaften)."}),
      Map.entry("tiled.encoding", new String[] {
          "Tile layer {0} is not stored as CSV.",
          "Die Kachelebene {0} ist nicht als CSV gespeichert."}),
      Map.entry("tiled.external", new String[] {
          "The tileset {0} is an external .tsx file.",
          "Der Kachelsatz {0} ist eine externe .tsx-Datei."}),
      Map.entry("tiled.collection", new String[] {
          "The tileset {0} is a collection of images; use one tileset image.",
          "Der Kachelsatz {0} ist eine Bildersammlung; nutze ein Kachelsatz-Bild."}),
      Map.entry("tiled.spacing", new String[] {
          "The tileset {0} has margin or spacing, which older libraries ignore.",
          "Der Kachelsatz {0} hat Rand oder Abstand, den ältere Bibliotheken ignorieren."}),
      Map.entry("tiled.group", new String[] {
          "{0} is inside a group layer.",
          "{0} liegt in einer Gruppenebene."}),
      Map.entry("tiled.flipped", new String[] {
          "Layer {0} has flipped tiles, which older libraries cannot load.",
          "Die Ebene {0} hat gespiegelte Kacheln, die ältere Bibliotheken nicht laden können."}),
      Map.entry("tiled.class", new String[] {
          "{0} uses Tiled 1.9\u2019s class instead of type.",
          "{0} nutzt Klasse aus Tiled 1.9 statt Typ."}),
      Map.entry("tiled.template", new String[] {
          "An object uses the template {0}, which is not loaded.",
          "Ein Objekt nutzt die Vorlage {0}, die nicht geladen wird."}));

  /** The issue as a sentence for students. */
  public static String describe(Issue issue,
      org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language language) {
    String[] texts = TEXTS.get(issue.key());
    if (texts == null) return issue.key() + " " + issue.detail();
    String text = texts[language
        == org.openpatch.scratch4j.core.lint.DiagnosticsExplanations.Language.DE ? 1 : 0];
    return text.replace("{0}", issue.detail());
  }

  /** Whether the map at {@code file} loads in every Scratch for Java version as it is. */
  public static List<Issue> check(Path file) throws IOException {
    return check(TmxDocument.open(file));
  }

  private static List<Element> all(Element root, String tag) {
    List<Element> out = new ArrayList<>();
    var list = root.getElementsByTagName(tag);
    for (int i = 0; i < list.getLength(); i++) {
      out.add((Element) list.item(i));
    }
    return out;
  }
}
