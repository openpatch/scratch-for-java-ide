package org.openpatch.scratch4j.core.project;

/** How a project folder is laid out (teachers' existing conventions). */
public enum ProjectLayout {
  /** BlueJ: {@code package.bluej}, classes at the root, jar in {@code +libs/}. */
  BLUEJ,
  /** VS Code: {@code .vscode/settings.json} referencing {@code +libs/*.jar}. */
  VSCODE,
  /** A plain folder of classes with {@code +libs/}. */
  PLAIN
}
