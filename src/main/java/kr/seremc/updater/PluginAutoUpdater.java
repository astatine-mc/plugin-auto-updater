package kr.seremc.updater;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Downloads only explicitly registered third-party plugins into an inactive update directory
 * across multiple backend server directories (lobby, survival, etc.) as well as the proxy itself.
 * It retains and overwrites the existing file name prior to the update so file names remain consistent.
 * The server start scripts apply verified pending files after each JVM has stopped.
 */
@Plugin(id = "pluginautoupdater", name = "PluginAutoUpdater", version = "1.0.0", authors = {"Seremc"})
public final class PluginAutoUpdater {
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDirectory;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private Path proxyDirectory;
  private String paperDirectorySetting = "../lobby";
  private String globalMinecraftVersion = "1.21.1";
  private final Map<String, ServerDefinition> serverDefinitions = new LinkedHashMap<>();
  private List<Entry> entries = new ArrayList<>();

  @Inject
  public PluginAutoUpdater(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  /** Creates the registry and schedules the weekly background check after Velocity starts. */
  @Subscribe
  public void initialize(ProxyInitializeEvent event) {
    try {
      Files.createDirectories(dataDirectory);
      proxyDirectory = dataDirectory.getParent().getParent().toAbsolutePath().normalize();
      loadConfiguration();

      // /pluginupdater 명령어 등록
      CommandMeta meta = proxy.getCommandManager().metaBuilder("pluginupdater")
          .aliases("updater")
          .plugin(this)
          .build();
      proxy.getCommandManager().register(meta, new PluginUpdaterCommand());

      // 매주 금요일 10:00 KST 스케줄러 등록
      long delay = Math.max(1, Duration.between(ZonedDateTime.now(KST), nextFridayTen()).toSeconds());
      proxy.getScheduler().buildTask(this, () -> checkAllSafely(null))
          .delay(delay, TimeUnit.SECONDS).repeat(7, TimeUnit.DAYS).schedule();

      logger.info("PluginAutoUpdater 준비 완료: 마인크래프트 고정 버전={}, 등록 서버={}개, 등록 플러그인={}개, 다음 자동 조회={}",
          globalMinecraftVersion.isBlank() ? "미지정" : globalMinecraftVersion,
          serverDefinitions.size(),
          entries.size(),
          nextFridayTen());
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

  public synchronized void checkAllSafely(CommandSource feedback) {
    try {
      loadConfiguration();
      if (feedback != null) {
        feedback.sendPlainMessage("[PluginAutoUpdater] 플러그인 업데이트 조회를 시작합니다. (MC 버전: "
            + (globalMinecraftVersion.isBlank() ? "미지정" : globalMinecraftVersion)
            + ", 대상 서버: " + serverDefinitions.keySet() + ")");
      }

      Map<String, Path> downloadCache = new HashMap<>();
      int processed = 0;
      for (Entry entry : entries) {
        if (!entry.enabled()) {
          logger.debug("{}: 설정에서 비활성화되어 건너뜁니다.", entry.id());
          continue;
        }
        checkEntry(entry, downloadCache, feedback);
        processed++;
      }

      // 다운로드 캐시 정리
      for (Path cached : downloadCache.values()) {
        try { Files.deleteIfExists(cached); } catch (Exception ignored) {}
      }

      if (feedback != null) {
        feedback.sendPlainMessage("[PluginAutoUpdater] 총 " + processed + "개 플러그인 규칙 확인 완료.");
      }
    } catch (Exception exception) {
      logger.error("플러그인 자동 업데이트 조회 실패", exception);
      if (feedback != null) {
        feedback.sendPlainMessage("[PluginAutoUpdater] 플러그인 업데이트 조회 중 오류: " + exception.getMessage());
      }
    }
  }

  private void checkEntry(Entry entry, Map<String, Path> downloadCache, CommandSource feedback) {
    for (String serverName : entry.targetServers()) {
      ServerDefinition server = serverDefinitions.get(serverName);
      if (server == null) {
        logger.warn("{}: 등록되지 않은 서버 이름 '{}' 입니다. (servers 설정 확인 필요)", entry.id(), serverName);
        if (feedback != null) {
          feedback.sendPlainMessage("[" + entry.id() + "] 등록되지 않은 서버: " + serverName);
        }
        continue;
      }

      Path serverDir;
      try {
        serverDir = resolveServerDirectory(server);
      } catch (Exception ex) {
        logger.warn("[{}/{}] 서버 디렉터리 접근 실패: {}", server.name(), entry.id(), ex.getMessage());
        if (feedback != null) {
          feedback.sendPlainMessage("[" + server.name() + "/" + entry.id() + "] 서버 디렉터리 접근 실패: " + ex.getMessage());
        }
        continue;
      }

      try {
        if (entry.source().equals("modrinth")) {
          stageModrinth(entry, server, serverDir, downloadCache, feedback);
        } else if (entry.source().equals("luckperms")) {
          stageLuckPerms(entry, server, serverDir, downloadCache, feedback);
        } else if (entry.source().equals("spigot-check")) {
          checkSpigot(entry, server, feedback);
        } else {
          logger.warn("{}: 지원하지 않는 source '{}'", entry.id(), entry.source());
          if (feedback != null) {
            feedback.sendPlainMessage("[" + entry.id() + "] 지원하지 않는 source: " + entry.source());
          }
        }
      } catch (Exception exception) {
        logger.warn("[{}/{}] 업데이트 확인 실패: {}", server.name(), entry.id(), exception.getMessage());
        if (feedback != null) {
          feedback.sendPlainMessage("[" + server.name() + "/" + entry.id() + "] 업데이트 확인 실패: " + exception.getMessage());
        }
      }
    }
  }

  private void checkSpigot(Entry entry, ServerDefinition server, CommandSource feedback) throws IOException, InterruptedException {
    if (entry.project().isBlank()) throw new IOException("Spigot 리소스 ID가 필요합니다.");
    String version = get("https://api.spigotmc.org/legacy/update.php?resource=" + encode(entry.project())).trim();
    String msg = String.format("[%s/%s] Spigot 최신 버전: %s (Spigot 공식 자동 다운로드는 비활성화되어 있습니다)", server.name(), entry.id(), version);
    logger.info(msg);
    if (feedback != null) feedback.sendPlainMessage(msg);
  }

  private void stageModrinth(Entry entry, ServerDefinition server, Path serverDir, Map<String, Path> downloadCache, CommandSource feedback) throws Exception {
    if (entry.project().isBlank()) throw new IOException("Modrinth project ID가 필요합니다.");
    StringBuilder query = new StringBuilder("?loaders=").append(encode("[\"" + server.platform() + "\"]"));
    if (!entry.gameVersion().isBlank() && !"any".equalsIgnoreCase(entry.gameVersion())) {
      query.append("&game_versions=").append(encode("[\"" + entry.gameVersion() + "\"]"));
    }
    query.append("&include_changelog=false");

    Object parsed = Json.parse(get("https://api.modrinth.com/v2/project/" + encodePath(entry.project()) + "/version" + query));
    if (!(parsed instanceof List<?> versions)) throw new IOException("Modrinth version 응답 형식이 올바르지 않습니다.");

    Optional<Map<String, Object>> candidate = versions.stream()
        .filter(Map.class::isInstance)
        .map(value -> castObject(value))
        .filter(version -> entry.channel().equalsIgnoreCase(string(version.get("version_type"))))
        .filter(version -> "listed".equals(string(version.get("status"))))
        .max(Comparator.comparing(version -> instant(string(version.get("date_published")))));

    if (candidate.isEmpty()) {
      logger.info("[{}/{}] 호환되는 Modrinth {} 버전이 없습니다. (MC 버전: {})",
          server.name(), entry.id(), entry.channel(), entry.gameVersion().isBlank() ? "전체" : entry.gameVersion());
      if (feedback != null) {
        feedback.sendPlainMessage("[" + server.name() + "/" + entry.id() + "] 호환되는 Modrinth " + entry.channel() + " 버전이 없습니다.");
      }
      return;
    }

    Map<String, Object> version = candidate.get();
    String remoteVersion = string(version.get("version_number"));
    Map<String, Object> file = primaryJar(version);
    String url = string(file.get("url"));
    String expectedHash = string(castObject(file.get("hashes")).get("sha512"));
    if (url.isBlank() || expectedHash.isBlank()) throw new IOException("Modrinth SHA-512 또는 JAR URL이 없습니다.");
    stageDownload(entry, server, serverDir, remoteVersion, url, expectedHash, downloadCache, feedback);
  }

  /**
   * LuckPerms 전용 공식 메타데이터 및 전용 다운로드 API 서버를 사용합니다.
   * 메타데이터 API: https://metadata.luckperms.net/data/all
   * 다운로드 서버: https://download.luckperms.net
   */
  private void stageLuckPerms(Entry entry, ServerDefinition server, Path serverDir, Map<String, Path> downloadCache, CommandSource feedback) throws Exception {
    String raw = get("https://metadata.luckperms.net/data/all");
    Map<String, Object> metadata = castObject(Json.parse(raw));
    String remoteVersion = string(metadata.get("version"));
    if (remoteVersion.isBlank()) throw new IOException("LuckPerms 공식 메타데이터에서 버전을 조회할 수 없습니다.");

    String artifact = server.platform().equals("velocity") ? "velocity" : "bukkit";
    Map<String, Object> downloads = castObject(metadata.get("downloads"));
    String url = string(downloads.get(artifact));
    if (url.isBlank()) throw new IOException("LuckPerms 다운로드 URL이 없습니다: platform=" + server.platform() + ", artifact=" + artifact);

    URI uri = URI.create(url);
    if (!"https".equalsIgnoreCase(uri.getScheme()) || !"download.luckperms.net".equalsIgnoreCase(uri.getHost())) {
      throw new IOException("LuckPerms 공식 다운로드 서버(download.luckperms.net) URL이 아닙니다: " + url);
    }

    stageDownload(entry, server, serverDir, remoteVersion, url, "", downloadCache, feedback);
  }

  /**
   * 검증된 JAR을 해당 서버의 update/ 비활성 폴더에 저장합니다.
   * 이때 새 원격 파일명이 아닌 '업데이트 이전의 활성 파일명'을 유지하여 덮어씌우는 방식으로 스테이징합니다.
   */
  private void stageDownload(Entry entry, ServerDefinition server, Path serverDir, String remoteVersion, String url,
                             String expectedHash, Map<String, Path> downloadCache, CommandSource feedback) throws Exception {
    Path pluginsDir = serverDir.resolve("plugins");

    // 1. 업데이트 이전의 활성 파일명 결정 (설정된 target-file 또는 해당 서버 plugins/ 내 기존 파일 자동 탐색)
    String targetFileName = entry.targetFile();
    Path active = null;

    if (!targetFileName.isBlank()) {
      Path candidate = pluginsDir.resolve(targetFileName);
      if (Files.isRegularFile(candidate)) {
        active = candidate;
      }
    }

    if (active == null) {
      Optional<Path> found = findActiveJar(pluginsDir, entry.id(), entry.project());
      if (found.isPresent()) {
        active = found.get();
        targetFileName = active.getFileName().toString();
        logger.info("[{}/{}] 업데이트 이전의 기존 파일명을 감지했습니다: {}", server.name(), entry.id(), targetFileName);
      }
    }

    if (active == null || !Files.isRegularFile(active)) {
      String msg = String.format("[%s/%s] 덮어씌울 업데이트 이전 JAR 파일이 %s/plugins/ 폴더에 존재하지 않아 건너뜁니다: %s",
          server.name(), entry.id(), server.name(), targetFileName.isBlank() ? "(기존 파일 감지 실패)" : targetFileName);
      logger.warn(msg);
      if (feedback != null) feedback.sendPlainMessage(msg);
      return;
    }

    String localVersion = pluginVersion(active).orElse("");
    if (!localVersion.isBlank() && remoteVersion.equals(localVersion)) {
      logger.debug("{}/{}: 이미 최신 버전({})이 활성화되어 있습니다.", server.name(), entry.id(), localVersion);
      if (feedback != null) {
        feedback.sendPlainMessage(String.format("[%s/%s] 이미 최신 버전(%s)이 활성화되어 있습니다.", server.name(), entry.id(), localVersion));
      }
      return;
    }

    Path updateDirectory = serverDir.resolve("update");
    Path stagedFile = updateDirectory.resolve(targetFileName);
    Path pendingTsv = updateDirectory.resolve("pending.tsv");
    String stateKey = server.name() + "." + entry.id();

    if (remoteVersion.equals(readStagedVersion(stateKey)) && Files.isRegularFile(stagedFile) && Files.isRegularFile(pendingTsv)) {
      logger.debug("{}/{}: 최신 버전({})이 이미 update/ 폴더에 대기 중입니다.", server.name(), entry.id(), remoteVersion);
      if (feedback != null) {
        feedback.sendPlainMessage(String.format("[%s/%s] 최신 버전(%s)이 이미 update/ 폴더에 대기 중입니다.", server.name(), entry.id(), remoteVersion));
      }
      return;
    }

    Files.createDirectories(updateDirectory.resolve(".download"));

    // 동일 체크 실행 내에서 같은 URL 중복 다운로드 방지 (캐시 활용)
    Path sourceJar;
    boolean isCached = downloadCache.containsKey(url) && Files.isRegularFile(downloadCache.get(url));
    if (isCached) {
      sourceJar = downloadCache.get(url);
    } else {
      sourceJar = Files.createTempFile(updateDirectory.resolve(".download"), entry.id() + "-", ".jar");
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofMinutes(2))
          .header("User-Agent", "Seremc-PluginAutoUpdater/1.0")
          .GET()
          .build();
      HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(sourceJar));
      if (response.statusCode() != 200) {
        Files.deleteIfExists(sourceJar);
        throw new IOException("JAR 다운로드 실패 (HTTP " + response.statusCode() + ")");
      }
      downloadCache.put(url, sourceJar);
    }

    try {
      String actualHash = sha512(sourceJar);
      if (!expectedHash.isBlank() && !expectedHash.equalsIgnoreCase(actualHash)) {
        throw new IOException("다운로드한 JAR의 SHA-512 해시가 일치하지 않습니다.");
      }

      validateJar(sourceJar, server.platform());

      Path stagedTemp = Files.createTempFile(updateDirectory.resolve(".download"), entry.id() + "-", ".jar");
      Files.copy(sourceJar, stagedTemp, StandardCopyOption.REPLACE_EXISTING);
      moveAtomically(stagedTemp, stagedFile);

      writePending(serverDir, entry.id(), targetFileName, remoteVersion, actualHash);
      writeStagedVersion(stateKey, remoteVersion);

      String successMsg = String.format("[%s/%s] 최신 버전(%s)을 이전 파일명(%s)으로 %s/update/ 폴더에 준비했습니다. 다음 재시작 시 덮어씌워집니다.",
          server.name(), entry.id(), remoteVersion, targetFileName, server.name());
      logger.info(successMsg);
      if (feedback != null) feedback.sendPlainMessage(successMsg);
    } catch (Exception ex) {
      if (!isCached) Files.deleteIfExists(sourceJar);
      throw ex;
    }
  }

  /**
   * plugins/ 디렉터리에서 해당 플러그인의 기존 활성 JAR 파일을 탐색합니다.
   * 1. JAR 내부 plugin.yml(name) 또는 velocity-plugin.json(id/name) 메타데이터 일치 확인
   * 2. 파일명 접두사 비교 (대소문자 및 특수문자 무시)
   */
  public static Optional<Path> findActiveJar(Path pluginsDir, String id, String project) {
    if (!Files.isDirectory(pluginsDir)) return Optional.empty();
    try (var stream = Files.list(pluginsDir)) {
      List<Path> jars = stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")).toList();

      for (Path jar : jars) {
        String metaName = pluginMetaName(jar).orElse("");
        if (!metaName.isBlank() && (metaName.equalsIgnoreCase(id) || (!project.isBlank() && metaName.equalsIgnoreCase(project)))) {
          return Optional.of(jar);
        }
      }

      String cleanId = id.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
      String cleanProject = project.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
      for (Path jar : jars) {
        String cleanFname = jar.getFileName().toString().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (cleanFname.startsWith(cleanId) || (!cleanProject.isBlank() && cleanFname.startsWith(cleanProject))) {
          return Optional.of(jar);
        }
      }
    } catch (IOException ignored) {}
    return Optional.empty();
  }

  private static Optional<String> pluginMetaName(Path jar) {
    if (!Files.isRegularFile(jar)) return Optional.empty();
    try (ZipFile archive = new ZipFile(jar.toFile())) {
      var paper = archive.getEntry("plugin.yml");
      if (paper != null) {
        try (InputStream input = archive.getInputStream(paper)) {
          for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
            if (line.trim().startsWith("name:")) {
              return Optional.of(line.substring(line.indexOf(':') + 1).trim().replace("\"", "").replace("'", ""));
            }
          }
        }
      }
      var velocity = archive.getEntry("velocity-plugin.json");
      if (velocity != null) {
        try (InputStream input = archive.getInputStream(velocity)) {
          Object parsed = Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
          Map<String, Object> map = castObject(parsed);
          String id = string(map.get("id"));
          if (!id.isBlank()) return Optional.of(id);
          return Optional.ofNullable(string(map.get("name")));
        }
      }
    } catch (Exception ignored) {}
    return Optional.empty();
  }

  private Path resolveServerDirectory(ServerDefinition server) throws IOException {
    if ("velocity".equals(server.platform()) || "proxy".equalsIgnoreCase(server.name())) {
      if (".".equals(server.directory()) || server.directory().isBlank()) {
        return proxyDirectory;
      }
      return proxyDirectory.resolve(server.directory()).normalize();
    }
    Path result = proxyDirectory.resolve(server.directory()).normalize();
    if (!Files.isDirectory(result.resolve("plugins"))) {
      throw new IOException("서버 폴더에 plugins/ 디렉터리가 존재하지 않습니다: " + result);
    }
    return result;
  }

  private Map<String, Object> primaryJar(Map<String, Object> version) throws IOException {
    Object values = version.get("files");
    if (!(values instanceof List<?> files)) throw new IOException("Modrinth 파일 목록이 없습니다.");
    for (Object value : files) {
      Map<String, Object> file = castObject(value);
      if (Boolean.TRUE.equals(file.get("primary")) && string(file.get("filename")).endsWith(".jar")) return file;
    }
    for (Object value : files) {
      Map<String, Object> file = castObject(value);
      if (string(file.get("filename")).endsWith(".jar")) return file;
    }
    throw new IOException("Modrinth 버전에 플러그인 JAR이 없습니다.");
  }

  private void writePending(Path serverDir, String id, String targetFile, String version, String hash) throws IOException {
    Path pending = serverDir.resolve("update").resolve("pending.tsv");
    Files.createDirectories(pending.getParent());
    Map<String, String> lines = new LinkedHashMap<>();
    if (Files.exists(pending)) {
      for (String line : Files.readAllLines(pending, StandardCharsets.UTF_8)) {
        String[] values = line.split("\\t", 2);
        if (values.length == 2) lines.put(values[0], line);
      }
    }
    lines.put(id, id + "\t" + targetFile + "\t" + hash + "\t" + version);
    Path temp = Files.createTempFile(pending.getParent(), ".pending-", ".tmp");
    Files.write(temp, lines.values(), StandardCharsets.UTF_8);
    moveAtomically(temp, pending);
  }

  private Optional<String> pluginVersion(Path jar) {
    if (!Files.isRegularFile(jar)) return Optional.empty();
    try (ZipFile archive = new ZipFile(jar.toFile())) {
      var paper = archive.getEntry("plugin.yml");
      if (paper != null) {
        try (InputStream input = archive.getInputStream(paper)) {
          for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
            if (line.trim().startsWith("version:")) {
              return Optional.of(line.substring(line.indexOf(':') + 1).trim().replace("\"", "").replace("'", ""));
            }
          }
        }
      }
      var velocity = archive.getEntry("velocity-plugin.json");
      if (velocity != null) {
        try (InputStream input = archive.getInputStream(velocity)) {
          return Optional.ofNullable(string(castObject(Json.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8))).get("version")));
        }
      }
    } catch (Exception ignored) { }
    return Optional.empty();
  }

  private synchronized void loadConfiguration() throws IOException {
    Path yamlFile = dataDirectory.resolve("config.yml");
    Path propertiesFile = dataDirectory.resolve("config.properties");

    if (Files.notExists(yamlFile) && Files.notExists(propertiesFile)) {
      Files.writeString(yamlFile, defaultConfiguration(), StandardCharsets.UTF_8);
    }

    if (Files.exists(yamlFile)) {
      loadYamlConfiguration(yamlFile);
    } else if (Files.exists(propertiesFile)) {
      loadLegacyPropertiesConfiguration(propertiesFile);
    }
  }

  @SuppressWarnings("unchecked")
  private void loadYamlConfiguration(Path file) throws IOException {
    LoaderOptions options = new LoaderOptions();
    Yaml yaml = new Yaml(new SafeConstructor(options));
    Map<String, Object> root;
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      Object loaded = yaml.load(reader);
      root = loaded instanceof Map<?, ?> ? (Map<String, Object>) loaded : Map.of();
    }

    // 1. 글로벌 마인크래프트 버전 고정값
    this.globalMinecraftVersion = string(firstNonNull(
        root.get("minecraft-version"),
        root.get("minecraft_version"),
        root.get("minecraftVersion"),
        root.get("mc-version"),
        root.get("mc_version")
    ));

    // 2. 관리 대상 서버(버킷) 목록 파싱
    this.serverDefinitions.clear();
    Object serversObj = root.get("servers");
    if (serversObj instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String sName = string(entry.getKey()).toLowerCase(Locale.ROOT);
        if (sName.isBlank()) continue;
        Object val = entry.getValue();
        if (val instanceof String dir) {
          String platform = ("proxy".equals(sName) || "velocity".equals(sName)) ? "velocity" : "paper";
          serverDefinitions.put(sName, new ServerDefinition(sName, dir, platform));
        } else if (val instanceof Map<?, ?> defMap) {
          String dir = string(firstNonNull(defMap.get("directory"), defMap.get("path"), defMap.get("server-directory"), "."));
          String defaultPlat = ("proxy".equals(sName) || "velocity".equals(sName)) ? "velocity" : "paper";
          String plat = string(firstNonNull(defMap.get("platform"), defaultPlat)).toLowerCase(Locale.ROOT);
          if ("bukkit".equals(plat) || "spigot".equals(plat) || "purpur".equals(plat)) plat = "paper";
          serverDefinitions.put(sName, new ServerDefinition(sName, dir, plat));
        }
      }
    }

    // 레거시 paper.server-directory 하위 호환
    Object paperObj = root.get("paper");
    if (paperObj instanceof Map<?, ?> paperMap) {
      this.paperDirectorySetting = string(firstNonNull(
          paperMap.get("server-directory"),
          paperMap.get("server_directory"),
          paperMap.get("serverDirectory")
      ));
    } else {
      String legacyPaperDir = string(root.get("paper.server-directory"));
      if (!legacyPaperDir.isBlank()) this.paperDirectorySetting = legacyPaperDir;
    }
    if (this.paperDirectorySetting.isBlank()) {
      this.paperDirectorySetting = "../lobby";
    }

    // 기본 proxy와 lobby 서버가 미등록되어 있으면 기본값 보장
    serverDefinitions.putIfAbsent("proxy", new ServerDefinition("proxy", ".", "velocity"));
    serverDefinitions.putIfAbsent("lobby", new ServerDefinition("lobby", this.paperDirectorySetting, "paper"));

    // 3. 등록된 플러그인 목록 파싱
    List<Entry> parsed = new ArrayList<>();
    Object pluginsObj = root.get("plugins");
    if (pluginsObj instanceof Map<?, ?> pluginsMap) {
      for (Map.Entry<?, ?> entry : pluginsMap.entrySet()) {
        String id = string(entry.getKey());
        if (entry.getValue() instanceof Map<?, ?> entryMap) {
          parsePluginEntry(id, (Map<String, Object>) entryMap, parsed);
        }
      }
    } else if (pluginsObj instanceof List<?> pluginsList) {
      for (Object item : pluginsList) {
        if (item instanceof Map<?, ?> itemMap) {
          Map<String, Object> map = (Map<String, Object>) itemMap;
          String id = string(firstNonNull(map.get("id"), map.get("name")));
          parsePluginEntry(id, map, parsed);
        }
      }
    }
    this.entries = parsed;
  }

  private void parsePluginEntry(String id, Map<String, Object> map, List<Entry> targetList) {
    if (id.isBlank()) return;
    if (!id.matches("[A-Za-z0-9._-]+")) {
      logger.warn("플러그인 ID '{}'에 허용되지 않는 특수문자가 있어 건너뜁니다.", id);
      return;
    }
    boolean enabled = isTrue(firstNonNull(map.get("enabled"), Boolean.TRUE));

    // 대상 서버 목록 파싱 (servers: [lobby, survival] 또는 server: lobby)
    List<String> targetServers = new ArrayList<>();
    Object sObj = firstNonNull(map.get("servers"), map.get("server"));
    if (sObj instanceof List<?> sList) {
      for (Object item : sList) {
        String s = string(item).toLowerCase(Locale.ROOT);
        if (!s.isBlank()) targetServers.add(s);
      }
    } else if (sObj != null) {
      String s = string(sObj).toLowerCase(Locale.ROOT);
      if (!s.isBlank()) targetServers.add(s);
    }

    String platform = string(firstNonNull(map.get("platform"), "")).toLowerCase(Locale.ROOT);
    if ("bukkit".equals(platform) || "spigot".equals(platform) || "purpur".equals(platform)) {
      platform = "paper";
    }

    // 대상 서버가 명시되지 않은 경우 platform 기반으로 기본 서버 배정
    if (targetServers.isEmpty()) {
      if ("velocity".equals(platform)) {
        targetServers.add("proxy");
      } else {
        targetServers.add("lobby");
      }
    } else {
      // "all" 또는 "all-paper" 와일드카드 처리
      List<String> expanded = new ArrayList<>();
      for (String s : targetServers) {
        if ("all".equals(s)) {
          expanded.addAll(serverDefinitions.keySet());
        } else if ("all-paper".equals(s) || "all-bukkit".equals(s)) {
          for (ServerDefinition def : serverDefinitions.values()) {
            if ("paper".equals(def.platform())) expanded.add(def.name());
          }
        } else {
          expanded.add(s);
        }
      }
      targetServers = expanded.stream().distinct().toList();
    }

    String targetFile = string(firstNonNull(
        map.get("target-file"),
        map.get("target_file"),
        map.get("targetFile"),
        map.get("file")
    ));
    if (!targetFile.isBlank() && !targetFile.matches("[A-Za-z0-9._+ -]+\\.jar")) {
      logger.warn("{}: target-file '{}' 은(는) 경로 없는 .jar 파일명이어야 합니다.", id, targetFile);
      return;
    }

    String source = string(firstNonNull(map.get("source"), map.get("type"))).toLowerCase(Locale.ROOT);
    String project = string(firstNonNull(
        map.get("project"),
        map.get("project-id"),
        map.get("projectId"),
        map.get("resource")
    ));
    if (project.isBlank() && "luckperms".equals(source)) {
      project = "official";
    }

    String gameVersion = string(firstNonNull(
        map.get("minecraft-version"),
        map.get("minecraft_version"),
        map.get("minecraftVersion"),
        map.get("game-version"),
        map.get("game_version")
    ));
    if (gameVersion.isBlank()) {
      gameVersion = this.globalMinecraftVersion;
    }

    String channel = string(firstNonNull(
        map.get("channel"),
        map.get("version-type"),
        map.get("versionType"),
        "release"
    )).toLowerCase(Locale.ROOT);

    targetList.add(new Entry(id, enabled, targetServers, platform, targetFile, source, project, gameVersion, channel));
  }

  private void loadLegacyPropertiesConfiguration(Path file) throws IOException {
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      properties.load(in);
    }
    this.paperDirectorySetting = properties.getProperty("paper.server-directory", "../lobby");
    this.globalMinecraftVersion = properties.getProperty("minecraft-version", "1.21.1");
    this.serverDefinitions.clear();
    this.serverDefinitions.put("proxy", new ServerDefinition("proxy", ".", "velocity"));
    this.serverDefinitions.put("lobby", new ServerDefinition("lobby", this.paperDirectorySetting, "paper"));

    List<Entry> parsed = new ArrayList<>();
    for (String key : properties.stringPropertyNames()) {
      if (key.startsWith("plugin.")) {
        String id = key.substring("plugin.".length());
        String raw = properties.getProperty(key);
        try {
          String[] values = raw.split("\\|", -1);
          if (values.length == 6) {
            String platform = values[0].toLowerCase(Locale.ROOT);
            if ("bukkit".equals(platform) || "spigot".equals(platform) || "purpur".equals(platform)) {
              platform = "paper";
            }
            List<String> targetServers = "velocity".equals(platform) ? List.of("proxy") : List.of("lobby");
            String targetFile = values[1];
            String source = values[2].toLowerCase(Locale.ROOT);
            String project = values[3];
            String gameVersion = values[4];
            if (gameVersion.isBlank() || "any".equalsIgnoreCase(gameVersion)) {
              gameVersion = this.globalMinecraftVersion;
            }
            String channel = values[5].toLowerCase(Locale.ROOT);
            parsed.add(new Entry(id, true, targetServers, platform, targetFile, source, project, gameVersion, channel));
          }
        } catch (Exception ex) {
          logger.warn("레거시 설정 파싱 실패 ({}): {}", id, ex.getMessage());
        }
      }
    }
    this.entries = parsed;
  }

  private String readStagedVersion(String key) throws IOException {
    return load(dataDirectory.resolve("staged.properties")).getProperty(key, "");
  }

  private void writeStagedVersion(String key, String version) throws IOException {
    Properties state = load(dataDirectory.resolve("staged.properties"));
    state.setProperty(key, version);
    try (var output = Files.newOutputStream(dataDirectory.resolve("staged.properties"))) {
      state.store(output, "Prepared update versions; no credentials belong here.");
    }
  }

  private String get(String url) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("User-Agent", "Seremc-PluginAutoUpdater/1.0")
        .GET()
        .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
    return response.body();
  }

  private static String sha512(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-512");
    try (InputStream input = Files.newInputStream(file)) {
      input.transferTo(new java.io.OutputStream() {
        @Override public void write(int b) { digest.update((byte) b); }
        @Override public void write(byte[] bytes, int offset, int length) { digest.update(bytes, offset, length); }
      });
    }
    return java.util.HexFormat.of().formatHex(digest.digest());
  }

  private static void validateJar(Path file, String platform) throws IOException {
    try (ZipFile archive = new ZipFile(file.toFile())) {
      if (archive.getEntry(platform.equals("paper") ? "plugin.yml" : "velocity-plugin.json") == null) {
        throw new IOException("대상 플랫폼 플러그인 메타데이터가 없는 JAR");
      }
    }
  }

  private static void moveAtomically(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static Properties load(Path file) throws IOException {
    Properties result = new Properties();
    if (Files.exists(file)) {
      try (InputStream input = Files.newInputStream(file)) {
        result.load(input);
      }
    }
    return result;
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static boolean isTrue(Object value) {
    if (value instanceof Boolean b) return b;
    if (value == null) return false;
    String s = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    return "true".equals(s) || "yes".equals(s) || "1".equals(s);
  }

  @SafeVarargs
  private static <T> T firstNonNull(T... values) {
    for (T val : values) {
      if (val != null) return val;
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castObject(Object value) {
    return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
  }

  private static Instant instant(String value) {
    try { return Instant.parse(value); } catch (Exception ignored) { return Instant.EPOCH; }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String encodePath(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "");
  }

  private static String defaultConfiguration() {
    return """
        # ==============================================================================
        # PluginAutoUpdater 설정 파일
        # ==============================================================================

        # 서버 기본 마인크래프트 버전 (고정값)
        # 개별 플러그인에서 minecraft-version을 지정하지 않으면 이 버전을 기본값으로 사용합니다.
        minecraft-version: "1.21.1"

        # ==============================================================================
        # 관리 대상 서버(버킷) 목록 정의
        # Velocity 루트(Proxy/) 기준 상대 경로 또는 절대 경로를 지정합니다.
        # ==============================================================================
        servers:
          proxy:
            directory: "."
            platform: velocity

          lobby:
            directory: "../lobby"
            platform: paper

          # 추후 버킷 서버 추가 시 예시:
          # survival:
          #   directory: "../survival"
          #   platform: paper

        # ==============================================================================
        # 자동 업데이트 대상 플러그인 목록
        # [주의] 이 서버에서 직접 빌드하는 커스텀 플러그인(custom-nickname 등)은 등록하지 마세요.
        # ==============================================================================
        plugins:
          # 1. 여러 버킷 서버에 공통 적용 (servers에 목록 지정 또는 all-paper)
          # target-file 생략 시 각 서버의 plugins/ 폴더에 설치된 기존 파일명을 자동 감지하여 덮어씌웁니다.
          luckperms:
            enabled: true
            servers:
              - lobby
              # - survival
            source: luckperms                      # luckperms 전용 다운로드 API 사용

          # 2. 특정 버킷 서버에만 적용 (server: lobby)
          # viaversion:
          #   enabled: true
          #   server: lobby
          #   source: modrinth
          #   project: viaversion
          #   channel: release

          # 3. 프록시 서버에만 적용 (server: proxy)
          # tab:
          #   enabled: true
          #   server: proxy
          #   source: modrinth
          #   project: tab
        """;
  }

  public record ServerDefinition(String name, String directory, String platform) {}

  public record Entry(
      String id,
      boolean enabled,
      List<String> targetServers,
      String platform,
      String targetFile,
      String source,
      String project,
      String gameVersion,
      String channel
  ) {}

  private final class PluginUpdaterCommand implements SimpleCommand {
    @Override
    public void execute(Invocation invocation) {
      CommandSource source = invocation.source();
      String[] args = invocation.arguments();

      if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
        source.sendPlainMessage("===== PluginAutoUpdater 상태 =====");
        source.sendPlainMessage("고정 Minecraft 버전: " + (globalMinecraftVersion.isBlank() ? "(미설정)" : globalMinecraftVersion));
        source.sendPlainMessage("등록된 서버 목록 (" + serverDefinitions.size() + "개):");
        for (ServerDefinition s : serverDefinitions.values()) {
          source.sendPlainMessage(String.format(" - %s [%s] 경로: %s", s.name(), s.platform(), s.directory()));
        }
        source.sendPlainMessage("등록된 플러그인 규칙 (" + entries.size() + "개):");
        for (Entry e : entries) {
          source.sendPlainMessage(String.format(" - %s -> 대상 서버=%s source=%s target=%s enabled=%s",
              e.id(), e.targetServers(), e.source(),
              e.targetFile().isBlank() ? "(기존 파일명 자동 유지)" : e.targetFile(), e.enabled()));
        }
        source.sendPlainMessage("명령어: /pluginupdater <check|reload|status>");
        return;
      }

      if ("reload".equalsIgnoreCase(args[0])) {
        try {
          loadConfiguration();
          source.sendPlainMessage("[PluginAutoUpdater] 설정을 다시 불러왔습니다. (서버: "
              + serverDefinitions.size() + "개, 플러그인: " + entries.size() + "개, MC 버전: " + globalMinecraftVersion + ")");
        } catch (Exception ex) {
          source.sendPlainMessage("[PluginAutoUpdater] 설정 다시 불러오기 실패: " + ex.getMessage());
        }
        return;
      }

      if ("check".equalsIgnoreCase(args[0])) {
        source.sendPlainMessage("[PluginAutoUpdater] 즉시 업데이트 조회를 비동기로 시작합니다...");
        proxy.getScheduler().buildTask(PluginAutoUpdater.this, () -> checkAllSafely(source)).schedule();
        return;
      }

      source.sendPlainMessage("알 수 없는 명령어입니다. 사용법: /pluginupdater <check|reload|status>");
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
      return invocation.source().hasPermission("pluginupdater.admin");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
      String[] args = invocation.arguments();
      if (args.length <= 1) {
        String input = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> list = new ArrayList<>();
        for (String sub : List.of("check", "reload", "status")) {
          if (sub.startsWith(input)) list.add(sub);
        }
        return list;
      }
      return Collections.emptyList();
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
