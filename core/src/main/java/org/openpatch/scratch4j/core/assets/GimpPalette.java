package org.openpatch.scratch4j.core.assets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A colour palette in GIMP's {@code .gpl} format, which Lospec, Aseprite,
 * Krita and Inkscape exchange:
 *
 * <pre>
 * GIMP Palette
 * Name: PICO-8
 * Columns: 8
 * # a comment
 *   0   0   0	black
 *  29  43  83	dark-blue
 * </pre>
 *
 * Reading is lenient (any whitespace, CRLF, missing names; a line that is not
 * a colour is skipped) and only the header line is required. Without a
 * {@code Name:} line, Lospec's {@code #Palette Name:} comment names it. An alpha column
 * ({@code Channels: RGBA}, as Aseprite writes) is read past: palettes here are
 * opaque. {@link #format()} writes the canonical form.
 *
 * @param name the {@code Name:} line, empty when there is none
 * @param columns the {@code Columns:} line, 0 when there is none (lay out freely)
 */
public record GimpPalette(String name, int columns, List<Entry> entries) {

  public static final String EXTENSION = ".gpl";

  private static final String HEADER = "GIMP Palette";
  private static final Pattern ENTRY =
      Pattern.compile("(\\d+)\\s+(\\d+)\\s+(\\d+)(?:\\s+(.*))?");

  /** One colour, 0-255 per channel; {@code name} is empty when the file has none. */
  public record Entry(int red, int green, int blue, String name) {

    public Entry {
      red = clamp(red);
      green = clamp(green);
      blue = clamp(blue);
      name = name == null ? "" : name.strip();
    }

    /** {@code #rrggbb}, lower case. */
    public String hex() {
      return String.format(Locale.ROOT, "#%02x%02x%02x", red, green, blue);
    }

    /** The colour as {@code 0xRRGGBB}. */
    public int rgb() {
      return red << 16 | green << 8 | blue;
    }
  }

  public GimpPalette {
    name = name == null ? "" : name.strip();
    columns = Math.max(0, columns);
    entries = List.copyOf(entries);
  }

  /** Parses {@code .gpl} text; throws when the {@code GIMP Palette} header is missing. */
  public static GimpPalette parse(String text) {
    String[] lines = text.replace("﻿", "").split("\\r?\\n|\\r");
    int first = 0;
    while (first < lines.length && lines[first].isBlank()) {
      first++;
    }
    if (first == lines.length || !lines[first].strip().equalsIgnoreCase(HEADER)) {
      throw new IllegalArgumentException("Not a GIMP palette: the first line must be \""
          + HEADER + "\"");
    }
    String name = "";
    String commentName = "";
    int columns = 0;
    boolean alpha = false;
    List<Entry> entries = new ArrayList<>();
    for (int i = first + 1; i < lines.length; i++) {
      String line = lines[i].strip();
      if (line.startsWith("#")) {
        // Lospec's downloads name the palette in a comment
        if (line.toLowerCase(Locale.ROOT).startsWith("#palette name:")) {
          commentName = line.substring(14);
        }
        continue;
      }
      if (line.isEmpty()) {
        continue;
      }
      String lower = line.toLowerCase(Locale.ROOT);
      if (lower.startsWith("name:")) {
        name = line.substring(5);
      } else if (lower.startsWith("columns:")) {
        columns = parseInt(line.substring(8).strip(), 0);
      } else if (lower.startsWith("channels:")) {
        alpha = line.substring(9).strip().equalsIgnoreCase("RGBA");
      } else {
        Entry entry = parseEntry(line, alpha);
        if (entry != null) {
          entries.add(entry);
        }
      }
    }
    return new GimpPalette(name.isBlank() ? commentName : name, columns, entries);
  }

  /** Reads a {@code .gpl} file (UTF-8). */
  public static GimpPalette read(Path file) throws IOException {
    try {
      return parse(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      throw new IOException(file.getFileName() + ": " + e.getMessage(), e);
    }
  }

  /** The canonical {@code .gpl} text. */
  public String format() {
    StringBuilder out = new StringBuilder(HEADER).append('\n');
    if (!name.isEmpty()) {
      out.append("Name: ").append(name).append('\n');
    }
    if (columns > 0) {
      out.append("Columns: ").append(columns).append('\n');
    }
    out.append("#\n");
    for (Entry entry : entries) {
      out.append(String.format(Locale.ROOT, "%3d %3d %3d", entry.red(), entry.green(),
          entry.blue()));
      if (!entry.name().isEmpty()) {
        out.append('\t').append(entry.name());
      }
      out.append('\n');
    }
    return out.toString();
  }

  /** Writes {@link #format()} to {@code file} (UTF-8), creating its folder. */
  public void write(Path file) throws IOException {
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(file, format(), StandardCharsets.UTF_8);
  }

  /** {@code R G B [A] [name]}, or null when the line does not start with three numbers. */
  private static Entry parseEntry(String line, boolean alpha) {
    Matcher m = ENTRY.matcher(line);
    if (!m.matches()) {
      return null;
    }
    String name = m.group(4) == null ? "" : m.group(4);
    if (alpha) {
      name = name.replaceFirst("^\\d+(\\s+|$)", "");
    }
    return new Entry(parseInt(m.group(1), 0), parseInt(m.group(2), 0),
        parseInt(m.group(3), 0), name);
  }

  private static int parseInt(String text, int fallback) {
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static int clamp(int value) {
    return Math.max(0, Math.min(255, value));
  }
}
