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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static org.junit.jupiter.api.Assertions.*;

public class PluginAutoUpdaterTest {

  @Test
  void testYamlParsingWithMultiServer() {
    String yamlContent = """
        minecraft-version: "1.21.1"
        servers:
          proxy:
            directory: "."
            platform: velocity
          lobby:
            directory: "../lobby"
            platform: paper
          survival:
            directory: "../survival"
            platform: paper
        plugins:
          luckperms:
            enabled: true
            servers:
              - lobby
              - survival
            source: luckperms
          custom-modrinth:
            enabled: true
            server: lobby
            target-file: CustomModrinth.jar
            source: modrinth
            project: custom-project
            minecraft-version: "1.20.4"
          proxy-tab:
            enabled: true
            server: proxy
            source: modrinth
            project: tab
        """;

    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Map<String, Object> root = yaml.load(new StringReader(yamlContent));

    assertNotNull(root);
    assertEquals("1.21.1", String.valueOf(root.get("minecraft-version")));

    Map<?, ?> servers = (Map<?, ?>) root.get("servers");
    assertEquals(3, servers.size());
    assertTrue(servers.containsKey("proxy"));
    assertTrue(servers.containsKey("lobby"));
    assertTrue(servers.containsKey("survival"));

    Map<?, ?> plugins = (Map<?, ?>) root.get("plugins");
    assertEquals(3, plugins.size());

    Map<?, ?> lp = (Map<?, ?>) plugins.get("luckperms");
    List<?> lpServers = (List<?>) lp.get("servers");
    assertEquals(2, lpServers.size());
    assertTrue(lpServers.contains("lobby"));
    assertTrue(lpServers.contains("survival"));
    assertEquals("luckperms", lp.get("source"));

    Map<?, ?> custom = (Map<?, ?>) plugins.get("custom-modrinth");
    assertEquals("lobby", custom.get("server"));
    assertEquals("1.20.4", custom.get("minecraft-version"));
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
