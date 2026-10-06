package org.openpatch.scratch4j.ui;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Version and published-release lookup for the About window. */
final class StudioUpdates {
  private static final URI RELEASES = URI.create(
      "https://api.github.com/repos/openpatch/scratch-for-java-ide/releases?per_page=20");
  private static final Pattern VERSION = Pattern.compile(
      "v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?");

  record Release(String version, URI page) {}

  private StudioUpdates() {}

  static String currentVersion() {
    String version = StudioUpdates.class.getPackage().getImplementationVersion();
    return version == null || version.isBlank() ? "development" : version;
  }

  static Release latestRelease(String installedVersion) throws IOException {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();
    HttpRequest request = HttpRequest.newBuilder(RELEASES)
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "scratch4j-studio")
        .build();
    try {
      HttpResponse<String> response = client.send(request,
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IOException("GitHub returned HTTP " + response.statusCode());
      }
      return newestPublished(response.body(), installedVersion.contains("-")
          || installedVersion.equals("development"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Update check interrupted", e);
    } catch (IllegalArgumentException | tools.jackson.core.JacksonException e) {
      throw new IOException("Invalid release information from GitHub", e);
    }
  }

  static Release newestPublished(String response, boolean includePreviews) throws IOException {
    JsonNode releases;
    try {
      releases = JsonMapper.shared().readTree(response);
    } catch (tools.jackson.core.JacksonException e) {
      throw new IOException("Invalid release information from GitHub", e);
    }
    if (releases == null || !releases.isArray()) {
      throw new IOException("Invalid release information from GitHub");
    }
    Release newest = null;
    for (JsonNode release : releases) {
      if (release.path("draft").asBoolean(false)
          || (!includePreviews && release.path("prerelease").asBoolean(false))) continue;
      String tag = release.path("tag_name").asText("");
      String page = release.path("html_url").asText("");
      if (!VERSION.matcher(tag).matches()
          || !page.startsWith("https://github.com/openpatch/scratch-for-java-ide/releases/tag/")) {
        continue;
      }
      String version = tag.replaceFirst("^v", "");
      if (newest == null || isNewer(version, newest.version())) {
        newest = new Release(version, URI.create(page));
      }
    }
    if (newest == null) throw new IOException("No published IDE release found on GitHub");
    return newest;
  }

  static boolean isNewer(String candidate, String current) {
    Matcher newer = VERSION.matcher(candidate);
    Matcher installed = VERSION.matcher(current);
    if (!newer.matches() || !installed.matches()) return false;
    for (int i = 1; i <= 3; i++) {
      int difference = new BigInteger(newer.group(i)).compareTo(new BigInteger(installed.group(i)));
      if (difference != 0) return difference > 0;
    }
    String newerPre = newer.group(4);
    String installedPre = installed.group(4);
    if (newerPre == null) return installedPre != null;
    if (installedPre == null) return false;
    List<String> left = List.of(newerPre.split("\\."));
    List<String> right = List.of(installedPre.split("\\."));
    for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
      String a = left.get(i);
      String b = right.get(i);
      boolean aNumber = a.matches("\\d+");
      boolean bNumber = b.matches("\\d+");
      int difference = aNumber && bNumber ? new BigInteger(a).compareTo(new BigInteger(b))
          : aNumber ? -1 : bNumber ? 1 : a.compareTo(b);
      if (difference != 0) return difference > 0;
    }
    return left.size() > right.size();
  }
}
