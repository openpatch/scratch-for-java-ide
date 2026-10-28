package org.openpatch.scratch4j.ui;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * A web page export, served on this computer only so a browser can open it:
 * the page loads its files with fetch, which a page opened as a file may not.
 * One server per folder, for as long as Studio runs.
 */
final class PagePreview {

  private static final Map<Path, String> SERVED = new ConcurrentHashMap<>();

  private static final Map<String, String> TYPES = Map.ofEntries(
      Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
      Map.entry("wasm", "application/wasm"), Map.entry("txt", "text/plain; charset=utf-8"),
      Map.entry("json", "application/json"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
      Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("ogg", "audio/ogg"),
      Map.entry("wav", "audio/wav"), Map.entry("mp3", "audio/mpeg"), Map.entry("ttf", "font/ttf"),
      Map.entry("otf", "font/otf"), Map.entry("svg", "image/svg+xml"));

  private PagePreview() {}

  /** The page's address on this computer, starting a server for its folder the first time. */
  static String url(Path folder) throws IOException {
    Path root = folder.toAbsolutePath().normalize();
    String known = SERVED.get(root);
    if (known != null) {
      return known;
    }
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", exchange -> {
      try (exchange) {
        String path = URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
        Path file = root.resolve(path.substring(1).isEmpty() ? "index.html" : path.substring(1)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
          exchange.sendResponseHeaders(404, -1);
          return;
        }
        String name = file.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        exchange.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(extension, "application/octet-stream"));
        exchange.getResponseHeaders().set("Cache-Control", "no-store"); // a new export shows at once
        exchange.sendResponseHeaders(200, Files.size(file));
        try (OutputStream out = exchange.getResponseBody()) {
          Files.copy(file, out);
        }
      }
    });
    server.setExecutor(Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, "scratch4j-page-preview");
      thread.setDaemon(true);
      return thread;
    }));
    server.start();
    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    SERVED.put(root, url);
    return url;
  }
}
