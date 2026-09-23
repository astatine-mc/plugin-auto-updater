# PluginAutoUpdater

Velocity에서 실행되는 외부 플러그인 업데이트 관리자입니다. 매주 금요일 10:00 `Asia/Seoul`에 등록된 항목을 확인합니다.

## 설치와 등록

`mvn -f plugin-auto-updater/pom.xml package`를 실행하면 `Proxy/plugins/plugin-auto-updater-1.0.0-SNAPSHOT.jar`가 생성됩니다. 현재 빌드 결과는 이미 그 위치에 배치되어 있습니다.

설정은 [예시 설정](examples/config.properties)을 `Proxy/plugins/pluginautoupdater/config.properties`로 복사한 뒤 한 줄씩 등록합니다.

```properties
plugin.luckperms=paper|LuckPerms-Bukkit-5.5.84.jar|modrinth|luckperms|1.21.1|release
```

필드 순서는 `플랫폼|정확한 현재 JAR 파일명|출처|프로젝트 ID|Minecraft 버전|채널`입니다.

- `platform`: `paper` 또는 `velocity`
- `source`: 완전 자동 다운로드는 `modrinth`; `spigot-check`는 버전 확인·로그 알림만 수행
- `channel`: 안정판은 `release`를 사용한다. `beta`, `alpha`는 명시적으로 선택한 경우에만 쓴다.

새 플러그인을 수동으로 넣은 경우에도 이 등록 한 줄이 필요합니다. JAR 이름만으로는 같은 이름의 다른 플러그인·유료 Spigot 리소스·비공식 재배포본을 구분할 수 없으므로 자동 추측하지 않습니다.

## 적용 과정

1. Modrinth API에서 플랫폼과 Minecraft 버전에 맞는 최신 채널을 찾는다.
2. JAR을 `Proxy/update/` 또는 `lobby/update/`에 내려받고 Modrinth SHA-512와 플러그인 메타데이터를 확인한다.
3. `pending.tsv`에 대기 정보를 기록한다. 활성 `plugins/`는 이 시점에 변경하지 않는다.
4. 다음 재시작에서 `start.sh`가 Java 실행 전에 SHA-512를 재확인하고, 기존 JAR을 `update-backup/<UTC-배치>/`로 옮긴 뒤 새 JAR을 이동한다.
5. 배치 중 실패하면 이미 이동한 JAR을 백업에서 복구하고 서버 시작을 중단한다.

`scripts/apply-pending-updates.sh`를 서버별 `plugin-updater/` 폴더에 배치하고, 각 `start.sh`에서 Java를 시작하기 직전에 호출한다.

Spigot은 공식 업데이트 API가 최신 버전 확인만 제공하므로 기본 자동 교체 대상이 아니다. 제작자가 Modrinth 또는 GitHub Releases도 제공하면 해당 공식 배포처를 등록해 자동 다운로드 대상으로 사용한다.

## 운영 주의

- 현재 자동 재시작 명령은 내리지 않는다. 재시작 시점은 운영자가 결정한다.
- `custom-nickname`처럼 이 작업 공간에서 빌드하는 JAR은 등록하지 않는다.
- `update/`와 `plugins/`를 같은 서버 폴더 안에 둬야 안전한 이름 변경으로 교체된다.
- 업데이트가 처음 준비되기 전에는 해당 서버를 재시작하지 않아도 된다. 준비 후 계획된 재시작에 적용된다.
