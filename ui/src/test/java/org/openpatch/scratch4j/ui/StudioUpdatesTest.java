package org.openpatch.scratch4j.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StudioUpdatesTest {

  @Test
  void comparesPreviewAndStableReleases() {
    assertTrue(StudioUpdates.isNewer("0.1.0-alpha.2", "0.1.0-alpha.1"));
    assertTrue(StudioUpdates.isNewer("0.1.0-alpha.10", "0.1.0-alpha.2"));
    assertTrue(StudioUpdates.isNewer("0.1.0", "0.1.0-alpha.10"));
    assertTrue(StudioUpdates.isNewer("0.2.0-alpha.1", "0.1.0"));
    assertFalse(StudioUpdates.isNewer("0.1.0-alpha.2", "0.1.0"));
    assertFalse(StudioUpdates.isNewer("0.1.0-alpha.1", "0.1.0-alpha.1"));
    assertFalse(StudioUpdates.isNewer("0.1.0", "development"));
  }

  @Test
  void updateChannelIncludesPreviewsOnlyForPreviewInstallations() throws Exception {
    String releases = """
        [
          {"tag_name":"v0.2.0-beta.1","html_url":"https://github.com/openpatch/scratch-for-java-ide/releases/tag/v0.2.0-beta.1","draft":false,"prerelease":true},
          {"tag_name":"v9.0.0","html_url":"https://github.com/openpatch/scratch-for-java-ide/releases/tag/v9.0.0","draft":true,"prerelease":false},
          {"tag_name":"v0.1.0","html_url":"https://github.com/openpatch/scratch-for-java-ide/releases/tag/v0.1.0","draft":false,"prerelease":false}
        ]
        """;
    assertEquals("0.2.0-beta.1", StudioUpdates.newestPublished(releases, true).version());
    assertEquals("0.1.0", StudioUpdates.newestPublished(releases, false).version());
  }
}
