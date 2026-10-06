package org.openpatch.scratch4j.core.project;

/** The starter shapes offered by the new-project wizard. */
public enum ProjectTemplate {
  /** One stage class; sprites are plain {@code Sprite} fields (imperative approach). */
  IMPERATIVE,
  /** A stage class plus one {@code Sprite} subclass per sprite (classes-first approach). */
  CLASSES_FIRST,
  /** BlueJ layout: {@code package.bluej}, {@code +libs/} with the jar, flat classes. */
  BLUEJ_STARTER,
  /** VS Code layout: {@code .vscode/settings.json} referencing {@code +libs/*.jar}. */
  VSCODE_STARTER
}
