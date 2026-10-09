package com.shatteredpixel.shatteredpixeldungeon.control.protocol;

import org.junit.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.Assert.*;

/** Release identity is generated once and shared by all current entry points. */
public class ReleaseIdentityTest {
    @Test public void generatedIdentityMatchesReleaseConfigurationAndUpstreamGame() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("gradle/cli-release.properties"))) root = root.getParent();
        assertNotNull("Release configuration must be in the source checkout", root);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(root.resolve("gradle/cli-release.properties"))) { properties.load(input); }
        assertEquals(properties.getProperty("cli_version"), ReleaseIdentity.CLI_VERSION);
        assertEquals(Integer.parseInt(properties.getProperty("protocol_version")), ReleaseIdentity.PROTOCOL_VERSION);
        assertEquals(Integer.parseInt(properties.getProperty("audit_schema_version")), ReleaseIdentity.AUDIT_SCHEMA_VERSION);
        assertEquals(properties.getProperty("profile_directory_name"), ReleaseIdentity.PROFILE_DIRECTORY_NAME);
        assertEquals(Integer.parseInt(properties.getProperty("trace_format_version")), ReleaseIdentity.TRACE_FORMAT_VERSION);
        assertEquals(9, ControlRequest.PROTOCOL_VERSION);
        assertEquals(12, ReleaseIdentity.AUDIT_SCHEMA_VERSION);
        assertEquals("4.0.2", ReleaseIdentity.GAME_VERSION);
        assertEquals(922, ReleaseIdentity.GAME_VERSION_CODE);
        assertEquals(1, ReleaseIdentity.TRACE_FORMAT_VERSION);
        String build = Files.readString(root.resolve("build.gradle"));
        assertTrue(build.contains("appVersionName = '" + ReleaseIdentity.GAME_VERSION + "'"));
        assertTrue(build.contains("appVersionCode = " + ReleaseIdentity.GAME_VERSION_CODE));
    }
}
