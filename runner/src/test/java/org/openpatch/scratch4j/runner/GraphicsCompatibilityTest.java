package org.openpatch.scratch4j.runner;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GraphicsCompatibilityTest {

  @AfterEach
  void off() {
    GraphicsCompatibility.setSoftware(false);
  }

  @Test
  void recognisesTheOpenGlFailuresTheLibraryAndLwjglPrint() {
    for (String line : new String[] {
        // what Scratch for Java 7 (LWJGL/GLFW) prints
        "Scratch for Java could not open its window: no OpenGL 3.3 core context",
        "It needs OpenGL 3.3 (4.1 on macOS). Update the graphics driver, or run on another computer.",
        "[LWJGL] GLFW_VERSION_UNAVAILABLE error",
        "[LWJGL] GLFW_API_UNAVAILABLE error",
        "java.lang.IllegalStateException: GLFW could not start (no display?)",
        "java.lang.UnsatisfiedLinkError: Failed to locate library: liblwjgl_opengl.so"}) {
      assertThat(GraphicsCompatibility.isGraphicsFailure(line)).as(line).isTrue();
    }
    for (String line : new String[] {
        "java.lang.NullPointerException: Cannot invoke \"Player.move(int)\"",
        "Could not load costume \"bunny\" - did you mean bunny1_stand?",
        "MESA-EGL: warning: DRI3 error: Could not get DRI3 device",
        "\tat org.lwjgl.glfw.GLFW.glfwInit(GLFW.java:12)", ""}) {
      assertThat(GraphicsCompatibility.isGraphicsFailure(line)).as(line).isFalse();
    }
  }

  @Test
  void asksMesaForItsSoftwareRendererOnlyWhenOn() {
    ProcessBuilder plain = new ProcessBuilder("java");
    var before = java.util.Map.copyOf(plain.environment());
    assertThat(GraphicsCompatibility.apply(plain).environment()).isEqualTo(before);
    GraphicsCompatibility.setSoftware(true);
    ProcessBuilder software = GraphicsCompatibility.apply(new ProcessBuilder("java"));
    assertThat(software.environment()).containsEntry("LIBGL_ALWAYS_SOFTWARE", "1")
        .containsEntry("GALLIUM_DRIVER", "llvmpipe");
  }
}
