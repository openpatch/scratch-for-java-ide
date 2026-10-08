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
  void recognisesTheOpenGlFailuresProcessingAndJoglPrint() {
    for (String line : new String[] {
        "Framebuffer objects are not supported by this hardware (or driver) Read "
            + "http://wiki.processing.org/w/OpenGL_Issues for help.",
        "GLProfile: device could not be initialized: WindowsGraphicsDevice[type .windows]",
        "com.jogamp.opengl.GLException: Profile GL2ES2 is not available on null",
        "com.jogamp.opengl.GLException: Unable to create temp OpenGL context(0)",
        "Caused by: com.jogamp.opengl.GLException: Caught GLException: Error swapping buffers, "
            + "eglError 0x300d, jogamp.opengl.egl.EGLDrawable[realize",
        "java.lang.UnsatisfiedLinkError: Can't load library: /tmp/jogamp_0000/libgluegen_rt.so",
        "RuntimeException: Renderer cannot find a JOGL surface"}) {
      assertThat(GraphicsCompatibility.isGraphicsFailure(line)).as(line).isTrue();
    }
    for (String line : new String[] {
        "java.lang.NullPointerException: Cannot invoke \"Player.move(int)\"",
        "Could not load costume \"bunny\" - did you mean bunny1_stand?",
        "MESA-EGL: warning: DRI3 error: Could not get DRI3 device",
        "\tat com.jogamp.opengl.GLException.<init>(GLException.java:12)", ""}) {
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
