# PluginAutoUpdater

Velocity에서 실행되는 외부 플러그인 업데이트 관리자입니다. 매주 금요일 10:00 `Asia/Seoul`에 등록된 항목을 확인합니다.

## 설치와 등록

`mvn -f plugin-auto-updater/pom.xml package`를 실행하면 `Proxy/plugins/plugin-auto-updater-1.0.0-SNAPSHOT.jar`가 빌드되어 프록시 플러그인 폴더에 자동으로 배치됩니다.

설정은 [예시 설정](examples/config.yml)을 `Proxy/plugins/pluginautoupdater/config.yml`로 복사한 뒤 YAML 형식으로 플러그인을 등록합니다.

```yaml
# 서버 기본 마인크래프트 버전 (고정값)
# 개별 플러그인에서 minecraft-version을 지정하지 않으면 이 버전을 기본값으로 사용합니다.
minecraft-version: "1.21.1"

# Paper(로비) 서버 디렉터리 경로
paper:
  server-directory: "../lobby"

# 자동 업데이트 대상 플러그인 목록
plugins:
  luckperms:
    enabled: true
    platform: paper                        # paper 또는 velocity
    target-file: LuckPerms-Bukkit-5.5.84.jar # plugins/ 폴더에 위치한 현재 활성 JAR 파일명
    source: luckperms                      # LuckPerms 전용 공식 다운로드 API 사용

  # viaversion:
  #   enabled: true
  #   platform: paper
  #   target-file: ViaVersion.jar
  #   source: modrinth
  #   project: viaversion
  #   channel: release                     # release, beta, alpha (기본값: release)
  #   # minecraft-version: "1.21.1"        # 생략 시 글로벌 minecraft-version 사용
```

### 주요 설정 항목

- `minecraft-version`: 콘피그 최상단에서 서버의 기본 마인크래프트 버전을 고정합니다. 개별 플러그인에서 `minecraft-version`을 생략하면 이 고정 버전이 자동으로 적용됩니다.
- `platform`: `paper` (또는 bukkit) 또는 `velocity`
- `target-file`: `plugins/` 폴더에 실제로 배치되어 있는 현재 JAR 파일명 (경로 제외)
- `source`:
  - `luckperms`: LuckPerms 전용 공식 API(`metadata.luckperms.net/data/all`) 및 전용 다운로드 서버(`download.luckperms.net`)에서 최신 버전을 자동으로 다운로드합니다.
  - `modrinth`: Modrinth 공식 API에서 플랫폼 및 마인크래프트 버전에 맞는 최신 채널 버전을 조회하고 SHA-512 체크섬 검증 후 다운로드합니다.
  - `spigot-check`: Spigot 공식 API로 새 버전 존재 여부만 확인하고 알림 로그를 출력합니다. (JAR 자동 다운로드는 비활성화)
- `channel`: `release`, `beta`, `alpha` (기본값: `release`)

새 플러그인을 수동으로 넣은 경우에도 이 등록 항목이 필요합니다. JAR 이름만으로는 같은 이름의 다른 플러그인·유료 Spigot 리소스·비공식 재배포본을 구분할 수 없으므로 자동 추측하지 않습니다.

## 명령어

- `/pluginupdater check` (또는 `/updater check`): 등록된 모든 플러그인의 최신 버전을 즉시 비동기로 조회하고 필요한 경우 `update/` 폴더에 스테이징합니다.
- `/pluginupdater reload` (또는 `/updater reload`): `config.yml` 설정을 다시 불러옵니다.
- `/pluginupdater status` (또는 `/updater status`): 현재 고정된 마인크래프트 버전, 등록된 플러그인 목록 및 상태를 확인합니다.

권한: `pluginupdater.admin` (콘솔은 기본 허용)

## 적용 과정

1. 스케줄러(매주 금요일 10:00 KST) 또는 관리자 명령어(`/pluginupdater check`)로 업데이트를 조회합니다.
2. Modrinth(제공 SHA-512 검증) 또는 LuckPerms 전용 API에서 최신 JAR을 `Proxy/update/` 또는 `lobby/update/`에 내려받고 플러그인 메타데이터(`plugin.yml` 또는 `velocity-plugin.json`)를 확인합니다.
3. `pending.tsv`에 대기 정보를 기록합니다. **실행 중인 활성 `plugins/` 폴더는 이 시점에 절대 변경하지 않습니다.**
4. 다음 서버 재시작 시 `start.sh`가 Java 실행 직전에 `apply-pending-updates.sh`를 호출하여 SHA-512를 재확인하고, 기존 활성 JAR을 `update-backup/<UTC-배치>/`로 백업한 뒤 새 JAR을 이동 적용합니다.
5. 교체 중 실패하면 이미 이동한 JAR을 백업에서 복구하고 서버 시작을 중단합니다.

## 운영 주의

- 현재 자동 서버 재시작 명령은 내리지 않습니다. 재시작 시점은 운영자가 결정합니다.
- `custom-nickname`처럼 이 작업 공간에서 직접 빌드하는 JAR은 등록하지 않습니다.
- `update/`와 `plugins/`를 같은 서버 폴더 안에 둬야 원자적 이름 변경(`mv`)으로 안전하게 교체됩니다.
