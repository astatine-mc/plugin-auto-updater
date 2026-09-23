package kr.seremc.updater;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.slf4j.Logger;

/**
 * Downloads only explicitly registered third-party Modrinth plugins into an inactive update
 * directory. It never deletes or overwrites an active plugin file; the server start script applies
 * verified pending files after the JVM has stopped.
 */
@Plugin(id = "pluginautoupdater", name = "PluginAutoUpdater", version = "1.0.0", authors = {"Seremc"})
public final class PluginAutoUpdater {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDirectory;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private Path proxyDirectory;
  private Properties configuration;

  @Inject
  public PluginAutoUpdater(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  /** Creates the editable registry and schedules the weekly background check after Velocity starts. */
  @Subscribe
  public void initialize(ProxyInitializeEvent event) {
    try {
      Files.createDirectories(dataDirectory);
      proxyDirectory = dataDirectory.getParent().getParent().toAbsolutePath().normalize();
      Path configFile = dataDirectory.resolve("config.properties");
      if (Files.notExists(configFile)) Files.writeString(configFile, defaultConfiguration());
      configuration = load(configFile);
      long delay = Math.max(1, Duration.between(ZonedDateTime.now(KST), nextFridayTen()).toSeconds());
      proxy.getScheduler().buildTask(this, this::checkAllSafely)
          .delay(delay, TimeUnit.SECONDS).repeat(7, TimeUnit.DAYS).schedule();
      logger.info("PluginAutoUpdater 준비 완료: 다음 자동 조회는 {} (KST)", nextFridayTen());
    } catch (IOException exception) {
      throw new IllegalStateException("PluginAutoUpdater 설정을 준비하지 못했습니다.", exception);
    }
  }

  private ZonedDateTime nextFridayTen() {
    ZonedDateTime now = ZonedDateTime.now(KST);
    ZonedDateTime result = now.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.FRIDAY))
        .withHour(10).withMinute(0).withSecond(0).withNano(0);
    return result.isAfter(now) ? result : result.plusWeeks(1);
  }

  private void checkAllSafely() {
    try {
      configuration = load(dataDirectory.resolve("config.properties"));
      for (String key : configuration.stringPropertyNames()) {
        if (key.startsWith("plugin.")) checkEntry(key.substring("plugin.".length()), configuration.getProperty(key));
      }
    } catch (Exception exception) {
      logger.error("플러그인 자동 업데이트 조회 실패", exception);
    }
  }

  /** Parses one operator-owned registry entry. Unsupported sources are logged and never downloaded. */
  private void checkEntry(String id, String raw) {
    try {
      Entry entry = Entry.parse(id, raw);
      if (entry.source.equals("modrinth")) stageModrinth(entry);
      else if (entry.source.equals("spigot-check")) checkSpigot(entry);
      else logger.warn("{}: 지원하지 않는 source '{}'", id, entry.source);
    } catch (Exception exception) {
      logger.warn("{} 업데이트 확인 실패: {}", id, exception.getMessage());
    }
  }

  private void checkSpigot(Entry entry) throws IOException, InterruptedException {
    String version = get("https://api.spigotmc.org/legacy/update.php?resource=" + encode(entry.project));
    logger.info("{}: Spigot 최신 버전은 {}입니다. Spigot JAR 자동 다운로드는 비활성화되어 있습니다.", entry.id, version.trim());
  }

  private void stageModrinth(Entry entry) throws Exception {
    String query = "?loaders=" + encode("[\"" + entry.platform + "\"]")
        + "&game_versions=" + encode("[\"" + entry.gameVersion + "\"]") + "&include_changelog=false";
    Object parsed = Json.parse(get("https://api.modrinth.com/v2/project/" + encodePath(entry.project) + "/version" + query));
    if (!(parsed instanceof List<?> versions)) throw new IOException("Modrinth version 응답 형식이 올바르지 않습니다.");
    Optional<Map<String, Object>> candidate = versions.stream().filter(Map.class::isInstance).map(value -> castObject(value))
        .filter(version -> entry.channel.equals(string(version.get("version_type"))))
        .filter(version -> "listed".equals(string(version.get("status"))))
        .max(Comparator.comparing(version -> instant(string(version.get("date_published")))));
    if (candidate.isEmpty()) { logger.info("{}: 호환되는 Modrinth {} 버전이 없습니다.", entry.id, entry.channel); return; }
    Map<String, Object> version = candidate.get();
    String remoteVersion = string(version.get("version_number"));
    Path active = serverDirectory(entry.platform).resolve("plugins").resolve(entry.targetFile);
    String localVersion = pluginVersion(active).orElse("");
    if (remoteVersion.equals(localVersion) || remoteVersion.equals(readStagedVersion(entry.id))) return;
    Map<String, Object> file = primaryJar(version);
    String url = string(file.get("url"));
    String expectedHash = string(castObject(file.get("hashes")).get("sha512"));
    if (url.isBlank() || expectedHash.isBlank()) throw new IOException("Modrinth SHA-512 또는 JAR URL이 없습니다.");
    Path updateDirectory = serverDirectory(entry.platform).resolve("update");
    Files.createDirectories(updateDirectory.resolve(".download"));
    Path temporary = Files.createTempFile(updateDirectory.resolve(".download"), entry.id + "-", ".jar");
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).header("User-Agent", "Seremc-PluginAutoUpdater/1.0").GET().build();
      HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(temporary));
      if (response.statusCode() != 200) throw new IOException("JAR 다운로드 HTTP " + response.statusCode());
      if (!expectedHash.equalsIgnoreCase(sha512(temporary))) throw new IOException("다운로드 JAR SHA-512 불일치");
      validateJar(temporary, entry.platform);
      moveAtomically(temporary, updateDirectory.resolve(entry.targetFile));
      writePending(entry, remoteVersion, expectedHash);
      writeStagedVersion(entry.id, remoteVersion);
      logger.info("{} {} 업데이트를 다음 재시작용 update 폴더에 준비했습니다.", entry.id, remoteVersion);
    } finally { Files.deleteIfExists(temporary); }
  }

  private Path serverDirectory(String platform) throws IOException {
    if (platform.equals("velocity")) return proxyDirectory;
    if (platform.equals("paper")) {
      String configured = configuration.getProperty("paper.server-directory", "../lobby");
      Path result = proxyDirectory.resolve(configured).normalize();
      if (!Files.isDirectory(result.resolve("plugins"))) throw new IOException("Paper 서버 폴더가 아닙니다: " + result);
      return result;
    }
    throw new IOException("platform은 paper 또는 velocity여야 합니다.");
  }

  private Map<String, Object> primaryJar(Map<String, Object> version) throws IOException {
    Object values = version.get("files");
    if (!(values instanceof List<?> files)) throw new IOException("Modrinth 파일 목록이 없습니다.");
    for (Object value : files) { Map<String, Object> file = castObject(value); if (Boolean.TRUE.equals(file.get("primary")) && string(file.get("filename")).endsWith(".jar")) return file; }
    for (Object value : files) { Map<String, Object> file = castObject(value); if (string(file.get("filename")).endsWith(".jar")) return file; }
    throw new IOException("Modrinth 버전에 플러그인 JAR이 없습니다.");
  }

  private void writePending(Entry entry, String version, String hash) throws IOException {
    Path pending = serverDirectory(entry.platform).resolve("update").resolve("pending.tsv");
    Files.createDirectories(pending.getParent());
    Map<String, String> lines = new LinkedHashMap<>();
    if (Files.exists(pending)) for (String line : Files.readAllLines(pending)) { String[] values = line.split("\\t", 2); if (values.length == 2) lines.put(values[0], line); }
    lines.put(entry.id, entry.id + "\t" + entry.targetFile + "\t" + hash + "\t" + version);
    Path temp = Files.createTempFile(pending.getParent(), ".pending-", ".tmp");
    Files.write(temp, lines.values(), StandardCharsets.UTF_8); moveAtomically(temp, pending);
  }

  private Optional<String> pluginVersion(Path jar) {
    if (!Files.isRegularFile(jar)) return Optional.empty();
    try (ZipFile archive = new ZipFile(jar.toFile())) {
      var paper = archive.getEntry("plugin.yml");
      if (paper != null) try (InputStream input = archive.getInputStream(paper)) {
        for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) if (line.trim().startsWith("version:")) return Optional.of(line.substring(line.indexOf(':') + 1).trim().replace("\"", "").replace("'", ""));
      }
      var velocity = archive.getEntry("velocity-plugin.json");
      if (velocity != null) try (InputStream input = archive.getInputStream(velocity)) { return Optional.ofNullable(string(castObject(Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8))).get("version"))); }
    } catch (Exception ignored) { }
    return Optional.empty();
  }

  private String readStagedVersion(String id) throws IOException { return load(dataDirectory.resolve("staged.properties")).getProperty(id, ""); }
  private void writeStagedVersion(String id, String version) throws IOException { Properties state = load(dataDirectory.resolve("staged.properties")); state.setProperty(id, version); try (var output = Files.newOutputStream(dataDirectory.resolve("staged.properties"))) { state.store(output, "Prepared update versions; no credentials belong here."); } }
  private String get(String url) throws IOException, InterruptedException { HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "Seremc-PluginAutoUpdater/1.0").GET().build(); HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode()); return response.body(); }
  private static String sha512(Path file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-512"); try (InputStream input = Files.newInputStream(file)) { input.transferTo(new java.io.OutputStream() { @Override public void write(int b) { digest.update((byte) b); } @Override public void write(byte[] bytes, int offset, int length) { digest.update(bytes, offset, length); } }); } return java.util.HexFormat.of().formatHex(digest.digest()); }
  private static void validateJar(Path file, String platform) throws IOException { try (ZipFile archive = new ZipFile(file.toFile())) { if (archive.getEntry(platform.equals("paper") ? "plugin.yml" : "velocity-plugin.json") == null) throw new IOException("대상 플랫폼 플러그인 메타데이터가 없는 JAR"); } }
  private static void moveAtomically(Path from, Path to) throws IOException { try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ignored) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); } }
  private static Properties load(Path file) throws IOException { Properties result = new Properties(); if (Files.exists(file)) try (InputStream input = Files.newInputStream(file)) { result.load(input); } return result; }
  private static String string(Object value) { return value instanceof String result ? result : ""; }
  @SuppressWarnings("unchecked") private static Map<String, Object> castObject(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of(); }
  private static Instant instant(String value) { try { return Instant.parse(value); } catch (Exception ignored) { return Instant.EPOCH; } }
  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
  private static String encodePath(String value) { return value.replaceAll("[^A-Za-z0-9._-]", ""); }
  private static String defaultConfiguration() { return "# Add one known source per plugin. A JAR dropped into plugins/ is never guessed or auto-registered.\n# plugin.<safe-id>=<paper|velocity>|<exact-active-jar-name>|<modrinth|spigot-check>|<project-or-resource-id>|<minecraft-version>|<release|beta|alpha>\n# Modrinth downloads are SHA-512 verified into update/. Spigot entries only report the official latest version.\npaper.server-directory=../lobby\n# plugin.luckperms=paper|LuckPerms-Bukkit-5.5.84.jar|modrinth|luckperms|1.21.1|release\n"; }

  private record Entry(String id, String platform, String targetFile, String source, String project, String gameVersion, String channel) {
    static Entry parse(String id, String raw) {
      if (!id.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("안전하지 않은 id");
      String[] value = raw.split("\\|", -1); if (value.length != 6) throw new IllegalArgumentException("등록 형식은 6개 필드여야 합니다.");
      if (!value[1].matches("[A-Za-z0-9._+ -]+\\.jar")) throw new IllegalArgumentException("target-file은 경로 없는 .jar 파일명이어야 합니다.");
      return new Entry(id, value[0].toLowerCase(Locale.ROOT), value[1], value[2].toLowerCase(Locale.ROOT), value[3], value[4], value[5].toLowerCase(Locale.ROOT));
    }
  }

  /** Minimal JSON reader kept local so the updater does not depend on a server-provided JSON library. */
  private static final class Json {
    private final String text; private int position;
    private Json(String text) { this.text = text; }
    static Object parse(String text) { Json json = new Json(text); Object result = json.value(); json.skip(); if (json.position != text.length()) throw new IllegalArgumentException("JSON trailing data"); return result; }
    private Object value() { skip(); if (position >= text.length()) throw new IllegalArgumentException("JSON EOF"); return switch (text.charAt(position)) { case '{' -> object(); case '[' -> array(); case '"' -> quoted(); case 't' -> literal("true", Boolean.TRUE); case 'f' -> literal("false", Boolean.FALSE); case 'n' -> literal("null", null); default -> number(); }; }
    private Map<String, Object> object() { expect('{'); Map<String, Object> map = new LinkedHashMap<>(); skip(); if (take('}')) return map; do { skip(); String key = quoted(); skip(); expect(':'); map.put(key, value()); skip(); } while (take(',')); expect('}'); return map; }
    private List<Object> array() { expect('['); List<Object> list = new ArrayList<>(); skip(); if (take(']')) return list; do { list.add(value()); skip(); } while (take(',')); expect(']'); return list; }
    private String quoted() { expect('"'); StringBuilder result = new StringBuilder(); while (position < text.length()) { char character = text.charAt(position++); if (character == '"') return result.toString(); if (character != '\\') { result.append(character); continue; } if (position >= text.length()) throw new IllegalArgumentException("JSON escape EOF"); char escaped = text.charAt(position++); switch (escaped) { case '"', '\\', '/' -> result.append(escaped); case 'b' -> result.append('\b'); case 'f' -> result.append('\f'); case 'n' -> result.append('\n'); case 'r' -> result.append('\r'); case 't' -> result.append('\t'); case 'u' -> { if (position + 4 > text.length()) throw new IllegalArgumentException("JSON unicode EOF"); result.append((char) Integer.parseInt(text.substring(position, position + 4), 16)); position += 4; } default -> throw new IllegalArgumentException("JSON escape"); } } throw new IllegalArgumentException("JSON string EOF"); }
    private Object literal(String literal, Object result) { if (!text.startsWith(literal, position)) throw new IllegalArgumentException("JSON literal"); position += literal.length(); return result; }
    private Number number() { int start = position; while (position < text.length() && "-+0123456789.eE".indexOf(text.charAt(position)) >= 0) position++; String value = text.substring(start, position); try { return value.contains(".") || value.contains("e") || value.contains("E") ? Double.parseDouble(value) : Long.parseLong(value); } catch (NumberFormatException exception) { throw new IllegalArgumentException("JSON number"); } }
    private void skip() { while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++; }
    private boolean take(char expected) { if (position < text.length() && text.charAt(position) == expected) { position++; return true; } return false; }
    private void expect(char expected) { if (!take(expected)) throw new IllegalArgumentException("JSON expected " + expected); }
  }
}
