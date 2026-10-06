package org.openpatch.scratch4j.core.project;

/** The Scratch for Java flavour a project uses. */
public enum LibraryFlavour {
  /** {@code scratch-&lt;v&gt;-all.jar}: {@code java.util.List} everywhere. */
  STANDARD("standard"),
  /** {@code scratch-&lt;v&gt;-nrw-all.jar}: NRW Abitur {@code List}; the project must contain the Abiturklassen {@code List.java}. */
  NRW("nrw");

  private final String id;

  LibraryFlavour(String id) {
    this.id = id;
  }

  /** The id used in {@code .scratch4j/project.json}. */
  public String id() {
    return id;
  }

  /** Parses the id from project.json; unknown ids fall back to {@link #STANDARD}. */
  public static LibraryFlavour fromId(String id) {
    for (LibraryFlavour f : values()) {
      if (f.id.equals(id)) {
        return f;
      }
    }
    return STANDARD;
  }
}
