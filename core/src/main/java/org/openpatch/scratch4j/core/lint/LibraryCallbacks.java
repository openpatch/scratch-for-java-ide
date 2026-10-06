package org.openpatch.scratch4j.core.lint;

import org.openpatch.scratch4j.core.api.ApiIndex;
import org.openpatch.scratch4j.core.api.ApiMethod;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * The methods the library calls on a sprite or a stage by itself: {@code run()}
 * every frame and the {@code when...} events, with their parameter types
 * (simple names). They differ between library versions (4.x has
 * {@code whenKeyPressed(int)}, 5.x {@code whenKeyPressed(KeyCode)}), so they
 * are read from the project's library jar when there is one.
 */
public record LibraryCallbacks(Map<String, List<List<String>>> sprite,
    Map<String, List<List<String>>> stage) {

  private static final String SPRITE = "org/openpatch/scratch/Sprite.class";
  private static final String STAGE = "org/openpatch/scratch/Stage.class";

  /** The callbacks of the library version the IDE ships (its API index). */
  public static LibraryCallbacks bundled() {
    Map<String, List<List<String>>> sprite = new LinkedHashMap<>();
    Map<String, List<List<String>>> stage = new LinkedHashMap<>();
    for (ApiMethod m : ApiIndex.load().methods()) {
      if (!"void".equals(m.returnType()) || !isCallbackName(m.methodName())) continue;
      List<String> types = m.params().stream()
          .map(p -> p.strip().replaceFirst("\\s+\\w+$", "")).toList();
      if (m.className().equals("Sprite")) {
        sprite.computeIfAbsent(m.methodName(), n -> new ArrayList<>()).add(types);
      } else if (m.className().equals("Stage")) {
        stage.computeIfAbsent(m.methodName(), n -> new ArrayList<>()).add(types);
      }
    }
    return new LibraryCallbacks(sprite, stage);
  }

  /** The callbacks of the first jar with Sprite and Stage, or {@link #bundled()}. */
  public static LibraryCallbacks of(List<Path> jars) {
    for (Path jar : jars) {
      if (!jar.toString().endsWith(".jar") || !Files.isRegularFile(jar)) continue;
      try (ZipFile zip = new ZipFile(jar.toFile())) {
        var sprite = zip.getEntry(SPRITE);
        var stage = zip.getEntry(STAGE);
        if (sprite == null || stage == null) continue;
        return new LibraryCallbacks(read(zip.getInputStream(sprite).readAllBytes()),
            read(zip.getInputStream(stage).readAllBytes()));
      } catch (IOException | IllegalArgumentException e) {
        // an unreadable jar: try the next one
      }
    }
    return bundled();
  }

  private static Map<String, List<List<String>>> read(byte[] classFile) {
    ClassModel model = ClassFile.of().parse(classFile);
    Map<String, List<List<String>>> callbacks = new LinkedHashMap<>();
    for (MethodModel method : model.methods()) {
      String name = method.methodName().stringValue();
      var type = method.methodTypeSymbol();
      if (method.flags().has(AccessFlag.PUBLIC) && !method.flags().has(AccessFlag.STATIC)
          && type.returnType().equals(ConstantDescs.CD_void) && isCallbackName(name)) {
        callbacks.computeIfAbsent(name, n -> new ArrayList<>())
            .add(type.parameterList().stream().map(ClassDesc::displayName).toList());
      }
    }
    return callbacks;
  }

  static boolean isCallbackName(String name) {
    return name.equals("run") || name.matches("when[A-Z]\\w*");
  }

  /** The callbacks of a sprite ({@code true}) or a stage. */
  Map<String, List<List<String>>> of(boolean forSprite) {
    return forSprite ? sprite : stage;
  }
}
