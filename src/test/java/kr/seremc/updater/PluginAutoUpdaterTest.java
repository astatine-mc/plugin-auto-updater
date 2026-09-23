package kr.seremc.updater;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static org.junit.jupiter.api.Assertions.*;

public class PluginAutoUpdaterTest {

  @Test
  void testYamlParsingWithGlobalMinecraftVersion() {
    String yamlContent = """
        minecraft-version: "1.21.1"
        paper:
          server-directory: "../lobby"
        plugins:
          luckperms:
            enabled: true
            platform: paper
            target-file: LuckPerms-Bukkit-5.5.84.jar
            source: luckperms
          custom-modrinth:
            enabled: true
            platform: paper
            target-file: CustomModrinth.jar
            source: modrinth
            project: custom-project
            minecraft-version: "1.20.4"
          inherited-modrinth:
            enabled: true
            platform: velocity
            target-file: Inherited.jar
            source: modrinth
            project: inherited-project
        """;

    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Map<String, Object> root = yaml.load(new StringReader(yamlContent));

    assertNotNull(root);
    assertEquals("1.21.1", String.valueOf(root.get("minecraft-version")));

    Map<?, ?> paper = (Map<?, ?>) root.get("paper");
    assertEquals("../lobby", paper.get("server-directory"));

    Map<?, ?> plugins = (Map<?, ?>) root.get("plugins");
    assertEquals(3, plugins.size());

    Map<?, ?> lp = (Map<?, ?>) plugins.get("luckperms");
    assertEquals("paper", lp.get("platform"));
    assertEquals("LuckPerms-Bukkit-5.5.84.jar", lp.get("target-file"));
    assertEquals("luckperms", lp.get("source"));
    assertNull(lp.get("minecraft-version")); // should inherit global

    Map<?, ?> custom = (Map<?, ?>) plugins.get("custom-modrinth");
    assertEquals("1.20.4", custom.get("minecraft-version")); // overridden
  }

  @Test
  void testLuckPermsDedicatedApiLive() throws Exception {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://metadata.luckperms.net/data/all"))
        .timeout(Duration.ofSeconds(10))
        .header("User-Agent", "Seremc-PluginAutoUpdater-Test/1.0")
        .GET()
        .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    assertEquals(200, response.statusCode());

    String body = response.body();
    assertTrue(body.contains("\"version\""), "Metadata must contain version field");
    assertTrue(body.contains("\"downloads\""), "Metadata must contain downloads field");
    assertTrue(body.contains("https://download.luckperms.net/"), "Downloads must use download.luckperms.net");
  }

  @Test
  void testFindActiveJar() {
    Path lobbyPlugins = Paths.get("/home/ubuntu/projectA/lobby/plugins");
    if (java.nio.file.Files.isDirectory(lobbyPlugins)) {
      Optional<Path> found = PluginAutoUpdater.findActiveJar(lobbyPlugins, "luckperms", "official");
      assertTrue(found.isPresent(), "Should find existing LuckPerms jar in lobby/plugins");
      assertEquals("LuckPerms-Bukkit-5.5.84.jar", found.get().getFileName().toString());
    }

    Path proxyPlugins = Paths.get("/home/ubuntu/projectA/Proxy/plugins");
    if (java.nio.file.Files.isDirectory(proxyPlugins)) {
      Optional<Path> foundTab = PluginAutoUpdater.findActiveJar(proxyPlugins, "tab", "tab");
      assertTrue(foundTab.isPresent(), "Should find TAB jar in Proxy/plugins");
      assertEquals("TAB v6.1.3.jar", foundTab.get().getFileName().toString());
    }
  }
}
