# AzCode (Android)

[简体中文](zh-CN.md) | [English](en.md) | [日本語](ja.md) | [Français](fr.md) | [Deutsch](de.md) | [Русский](ru.md) | [Español](es.md)

AzCode는 Android 기기에서 로컬로 동작하는 자동화 어시스턴트입니다. 자연어로 작업을 설명하면 내장 Agent 루프가 화면을 읽고 컨트롤을 탭하고 스와이프하며 명령을 실행해 작업을 완료합니다. 모든 데이터는 기기에 저장되며 클라우드 동기화를 사용하지 않습니다.

- 앱 이름: **AzCode**
- GUI 형태 레이블: **magic**
- CLI 형태 레이블: **Listen**
- 현재 버전: **1.9.15** (versionCode 70)
- 요구 사항: Android 8.0 이상 (minSdk 26), targetSdk 34

단독으로 실행할 수 있으며, `127.0.0.1:8848`에만 바인딩되는 기능 브리지를 켜면 `adb forward`를 통해 Listen CLI나 다른 클라이언트가 동일한 기기 기능을 재사용할 수 있습니다.

## 기능 개요

| 기능 | 설명 |
| --- | --- |
| 자연어 작업 | 채팅 입력, Agent 다단계 계획과 실행, 실시간 도구 호출 표시 |
| 화면 조작 | 노드 읽기, 텍스트 또는 좌표 탭, 스와이프, 시스템 내비게이션, Unicode 텍스트 입력 |
| 앱 실행 | 패키지명 또는 앱 이름으로 실행, shell과 접근성 기반 대체 경로 |
| 명령 실행 | Termux, 내장 shell, Shizuku, Root 4개 채널을 권한 모드에 따라 자동 선택 |
| 다중 모델 | 여러 제공자 계정, OpenAI 호환, Anthropic, Gemini 프로토콜 지원 |
| 사고 깊이 | 끄기, 빠름, 표준, 깊음 4단계, 모델별 파라미터 호환 처리 |
| 세션 관리 | 독립 세션, 기록 드로어, 자동 제목, 컨텍스트 이어가기와 압축 |
| 첨부 파일 | 이미지, 오디오, PDF, Office, 텍스트, 코드 |
| 음성 입력 | 시스템 오프라인 인식과 Xunfei 온라인 인식 전환 |
| 플로팅 어시스턴트 | 기본 디지털 어시스턴트 진입, 투명 글로우, 페이지 내 질문, 키보드 입력 |
| 장기 기억 | 민감 토큰, 사용 습관, 스킬 방법, 기기 로컬 개인화 |
| 스킬과 플러그인 | 내장 스킬, 사용자 스킬, GitHub SKILL.md 설치, 인기 플러그인 스캔 |
| GitHub | 파일 읽기/쓰기, 브랜치와 커밋 조회, Issue와 Pull Request 관리 |
| 이미지 생성 | 여러 제공자의 텍스트-이미지 생성, 채팅에서 표시하고 저장 |
| Office 문서 | GenOffice MCP로 docx / xlsx / pptx 생성 및 미리보기 |
| 입력기 | 내장 AzCode IME를 Unicode 텍스트 입력 대체 수단으로 사용 |
| 기능 브리지 | 루프백 HTTP API와 Listen CLI |
| 로그 | 충돌과 실행 로그를 저장하고 설정에서 보기, 복사, 삭제 |

## 빠른 시작

### 설치

GitHub Releases의 `android-v*`에서 APK를 내려받거나 GitHub Actions의 `azcode-magic-apk` 아티팩트를 사용합니다. debug와 release는 동일한 서명 키를 사용하므로 서로 덮어 설치할 수 있습니다.

### 초기 설정

1. 설정 > 기기 기능 > 접근성을 열고 시스템 설정에서 **AzCode Screen Control**을 켭니다.
2. 설정 > 모델 관리에서 제공자를 추가하고 활성화한 뒤 API Key를 입력합니다. Base URL과 모델은 프리셋을 사용할 수 있습니다.
3. 메인 화면으로 돌아가 모델 선택기에서 현재 모델을 확인합니다.
4. 작업을 입력해 전송합니다. 예: "설정을 열고 Android 버전 확인".

### 권한

| 권한 | 용도 |
| --- | --- |
| 접근성 서비스 | 화면 읽기, 탭, 스와이프, 시스템 내비게이션, 텍스트 입력 |
| 마이크 | 음성 입력과 플로팅 어시스턴트 |
| 알림 | 작업 완료와 질문 알림 |
| 다른 앱 위에 표시 | 플로팅 어시스턴트 글로우 |
| 카메라 | 사진 첨부 |
| Termux RUN_COMMAND | Termux로 전체 Linux 명령 실행 |
| Shizuku | adb 권한으로 시스템 수준 명령 실행 |

### 소스에서 빌드

JDK 17과 Android SDK가 필요합니다. 저장소에 `gradle-wrapper.jar`가 없으므로 시스템 `gradle`을 사용합니다:

```bash
gradle assembleDebug
gradle assembleRelease
```

코드를 푸시하면 `.github/workflows/android.yml`이 GitHub Actions에서 Debug와 Release APK를 빌드합니다. `android-v*` 태그를 푸시하면 GitHub Release가 생성됩니다.

## 기기 기능과 명령 실행

| 모드 | 채널 | 권한 | 전제 조건 | 타임아웃 |
| --- | --- | --- | --- | --- |
| NORMAL + Termux | Termux `RUN_COMMAND` | Termux uid | Termux 설치, RUN_COMMAND 허용, `allow-external-apps=true` | 90초 |
| NORMAL + 내장 shell | `/system/bin/sh -c` | 앱 uid | 없음 | 60초 |
| SHIZUKU | Shizuku binder | adb uid 2000 | Shizuku 실행 및 허용 | 60초 |
| ROOT | `su -c` | uid 0 | 기기 루팅 | 60초 |

NORMAL 모드는 Termux를 먼저 시도하고 사용할 수 없으면 내장 shell로 대체합니다. 다른 앱의 비공개 디렉터리 접근이나 시스템 설정 변경에는 Shizuku 또는 Root가 필요합니다.

## 로컬 데이터와 저장소

AzCode는 SQLite, Room, 서버 데이터베이스를 사용하지 않습니다. 데이터 계층은 앱 전용 `SharedPreferences`와 JSON 파일로 구성됩니다:

| 경로 | 내용 |
| --- | --- |
| `SharedPreferences` | 모델 제공자, 세션 커서, 스킬, 명령 기억, GitHub / GenOffice 설정, 음성과 권한 모드 |
| `files/sessions/<id>.json` | 단일 세션의 채팅 기록과 Agent 컨텍스트 |
| `files/memory.json` | 장기 기억 항목 |
| `files/logs/YYYY-MM-DD.log` | 일별 순환 로그, 최대 10개 파일, 파일당 512 KB |
| `files/genoffice/` | 생성된 Office 문서 |
| `cache/az_images/`, `cache/captures/` | 이미지 캐시와 촬영 임시 파일 |

세션은 임시 파일과 rename으로 기록합니다. Agent 컨텍스트는 최대 40개 메시지를 유지하고 그 이전 내용은 요약으로 압축합니다. 앱을 삭제하면 모든 로컬 데이터가 제거됩니다.

## 기능 브리지와 Listen CLI

브리지는 `127.0.0.1:8848`에만 바인딩되며 외부에서 접근할 수 없고 인증도 없습니다. 원격 접근에는 `adb forward`가 필요합니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/health` | 접근성, Shizuku, Root, 권한 모드 상태 |
| GET | `/screen` | 현재 화면의 표시 노드, 최대 200개 |
| POST | `/tap` | `{"x":123,"y":456}` 또는 `{"text":"확인"}` |
| POST | `/swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"duration":300}` |
| POST | `/global` | `{"action":"back"}`, back / home / recents / notifications 지원 |
| POST | `/shell` | `{"cmd":"pm list packages"}`, 현재 권한 모드로 실행 |
| POST | `/notify` | `{"title":"..","body":".."}` |
| POST | `/agent` | `{"task":"..","session":"선택 id"}`, `answer`와 `events` 반환 |

adb로 기기를 연결한 상태에서:

```bash
curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/main/tools/install.sh | bash
listen run "설정을 열고 Android 버전 확인"
listen screen
listen tap --text "확인"
```

## 개인정보와 보안

- 모델 API Key, GitHub 토큰, GenOffice 토큰, Xunfei 자격 증명은 앱 전용 저장소에만 보관되며 앱은 환경 변수를 읽지 않습니다.
- 기기 로컬 개인화 스냅샷은 기기에서 생성되고 클라우드 대화에 주입되지 않습니다.
- 브리지는 루프백에만 바인딩되며 원격 접근은 adb 포워딩에 의존합니다.
- Xunfei 온라인 인식은 음성을 Xunfei 오픈 플랫폼으로 전송합니다. 내장 시스템 인식은 기기 내 오프라인 처리를 우선합니다.
- Shizuku와 Root는 시스템 수준 명령을 실행할 수 있으며 실수는 기기나 데이터를 손상시킬 수 있습니다. 소유하거나 명시적으로 허가받은 기기에서만 사용하세요.

## 알려진 제한

- 화면 읽기와 UI 조작은 접근성 서비스에 의존하며 일부 제조사 시스템은 백그라운드 동작을 제한합니다.
- 시스템 수준 shell은 Shizuku 또는 Root에 의존하며 NORMAL 모드는 앱 샌드박스 제한을 받습니다.
- 오디오와 이미지 입력은 선택한 모델의 멀티모달 능력에 의존합니다.
- 이미지 생성은 제공자 API에 의존하며 지원 크기와 수량이 다릅니다.
- 클라우드 동기화가 없으며 데이터는 앱 삭제 시 제거됩니다.

## 링크

- 전체 문서: 루트 `README.md`
- 제품 설명: `PRODUCT.md`
- 디자인 시스템: `DESIGN.md`
- CLI 도구: `tools/listen`과 `tools/install.sh`

## 라이선스

MIT. 접근성과 Shizuku 실행 모듈은 [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)(MIT)를 참고해 수정했습니다. Shizuku API는 [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)를 사용합니다.
