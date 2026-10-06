package org.openpatch.scratch4j.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Shader previews render off-screen; needs a display ({@code xvfb-run}) and -Dscratch4j.gltest. */
@EnabledIfSystemProperty(named = "scratch4j.gltest", matches = "true")
class ShaderRenderIT {

  @TempDir Path tmp;

  @Test
  void rendersFramesAndReportsErrors() throws Exception {
    Path allJar = LibraryJarSource.allJar(
        Path.of(System.getProperty("user.dir"), "target", "bundled"));
    var renderer = new ShaderRenderer(Path.of(System.getProperty("java.home"), "bin", "java"),
        List.of(allJar), tmp.resolve("cache"));
    Path frag = tmp.resolve("red.frag");
    Files.writeString(frag, """
        #define PROCESSING_TEXTURE_SHADER
        varying vec4 vertTexCoord;
        uniform sampler2D texture;
        uniform float amount;
        uniform float time;
        void main() {
          vec4 col = texture2D(texture, vertTexCoord.st);
          gl_FragColor = vec4(amount, col.g * 0.0, fract(time), 1.0);
        }
        """);
    var result = renderer.render(new ShaderRenderer.Request(frag, null, null, 40, 20, 4, 2,
        Map.of("amount", new double[] {1.0})));
    assertThat(result.failure()).isNull();
    assertThat(result.errors()).isEmpty();
    assertThat(result.uniforms()).contains("amount", "time");
    assertThat(result.frames()).hasSize(4); // it uses time: animated
    var first = ImageIO.read(result.frames().get(0).toFile());
    assertThat(first.getWidth()).isEqualTo(40);
    assertThat(first.getRGB(10, 10) & 0xffffff).isEqualTo(0xff0000);
    Path secondFrag = tmp.resolve("green.frag");
    Files.writeString(secondFrag, "void main() { gl_FragColor = vec4(0.0, 1.0, 0.0, 1.0); }");
    var secondResult = renderer.render(new ShaderRenderer.Request(secondFrag, null, null, 40,
        20, 1, 1, Map.of()));
    assertThat(secondResult.ok()).isTrue();
    assertThat(ImageIO.read(secondResult.frames().get(0).toFile()).getRGB(10, 10) & 0xffffff)
        .isEqualTo(0x00ff00);
    Path demo = Path.of("../core/src/main/resources/org/openpatch/scratch4j/core/templates/demo-shader");
    var pixelate = renderer.render(new ShaderRenderer.Request(demo.resolve("pixelate.frag"),
        demo.resolve("default.vert"), demo.resolve("cat.png"), 40, 40, 1, 1,
        Map.of("pixels", new double[] {20, 10})));
    assertThat(pixelate.ok()).isTrue();
    assertThat(ImageIO.read(pixelate.frames().get(0).toFile()).getRGB(20, 20) >>> 24)
        .isGreaterThan(0);
    // frame 1 of 4 over 2 seconds: time 0.5
    var second = ImageIO.read(result.frames().get(1).toFile());
    assertThat(second.getRGB(10, 10) & 0xff).isBetween(120, 135);
    // live: one frame at any time, time runs on (fract(2.25) = 0.25, not looped to 0.25 of 2s)
    Path liveFile = tmp.resolve("live/frame.png");
    var live = renderer.live(new ShaderRenderer.Request(frag, null, null, 40, 20, 1, 1,
        Map.of("amount", new double[] {1.0})), 2.25, liveFile);
    assertThat(live.ok()).isTrue();
    assertThat(ImageIO.read(liveFile.toFile()).getRGB(10, 10) & 0xff).isBetween(60, 68);
    var later = renderer.live(new ShaderRenderer.Request(frag, null, null, 40, 20, 1, 1,
        Map.of("amount", new double[] {0.0})), 7.75, liveFile);
    assertThat(later.ok()).isTrue();
    int rgb = ImageIO.read(liveFile.toFile()).getRGB(10, 10);
    assertThat(rgb & 0xff).isBetween(188, 194); // fract(7.75) = 0.75
    assertThat(rgb >> 16 & 0xff).as("uniforms change without compiling again").isZero();

    // cached: the same request does not run again
    long before = Files.getLastModifiedTime(result.frames().get(0)).toMillis();
    assertThat(renderer.render(new ShaderRenderer.Request(frag, null, null, 40, 20, 4, 2,
        Map.of("amount", new double[] {1.0}))).frames()).hasSize(4);
    assertThat(Files.getLastModifiedTime(result.frames().get(0)).toMillis()).isEqualTo(before);

    // like the game: without #version, texture() and textureSize() work (Processing's
    // rewrite for modern GLSL, the uniform texture becomes texMap)
    Path modern = tmp.resolve("modern.frag");
    Files.writeString(modern, """
        uniform sampler2D texture;
        varying vec4 vertTexCoord;
        void main() {
          vec2 texture_size = vec2(textureSize(texture, 0));
          vec4 c = texture(texture, vertTexCoord.st + 0.5 / texture_size);
          gl_FragColor = vec4(c.rgb, 1.0);
        }
        """);
    var modernResult = renderer.render(new ShaderRenderer.Request(modern, null, null, 40, 20,
        1, 1, Map.of()));
    assertThat(modernResult.errors()).isEmpty();
    assertThat(modernResult.ok()).isTrue();
    assertThat(modernResult.uniforms()).contains("texture");
    // a warning is no error: the shader still draws, the line is the file's
    Files.writeString(modern, """
        varying vec4 vertTexCoord;
        void main() {
          float size;
          gl_FragColor = vec4(size, vertTexCoord.s, 0.0, 1.0);
        }
        """);
    var warned = renderer.render(new ShaderRenderer.Request(modern, null, null, 40, 20, 1, 1,
        Map.of()));
    assertThat(warned.ok()).isTrue();
    assertThat(warned.errors()).isEmpty();
    if (!warned.warnings().isEmpty()) { // drivers differ in what they warn about
      assertThat(warned.warnings().get(0).line()).isEqualTo(4);
    }

    Files.writeString(frag, "void main() {\n  gl_FragColor = vec4(oops);\n}\n");
    var broken = renderer.render(new ShaderRenderer.Request(frag, null, null, 40, 20, 1, 1,
        Map.of()));
    assertThat(broken.ok()).isFalse();
    assertThat(broken.errors()).isNotEmpty();
    assertThat(broken.errors().get(0).kind()).isEqualTo("frag");
    assertThat(broken.errors().get(0).line()).isEqualTo(2);
  }
}
