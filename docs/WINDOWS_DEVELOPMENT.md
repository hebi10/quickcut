# Windows Android Studio 개발

Windows에서 Android 앱을 수정하고 실행합니다. Rust·FFmpeg·x264·x265 엔진을 바꿀 때만 GitHub Actions의 Linux 러너에서 Android용 라이브러리를 빌드해 가져옵니다. Windows에 WSL이나 Linux NDK를 설치할 필요가 없습니다.

## 평소 앱 개발

1. `D:\dev_app\quickcut`을 Android Studio에서 엽니다.
2. Gradle JDK를 준비된 JDK 21로 설정하고 SDK는 `D:\Android\Sdk`를 사용합니다.
3. Gradle 동기화 후 `app`과 실행 기기를 선택해 Run을 누릅니다. Kotlin·Compose UI·리소스 변경은 다시 Run하거나 Android Studio의 적용 기능으로 확인합니다.

Python 3.12 이상과 Git이 필요합니다. Python 실행 파일을 따로 지정하려면 `QUICKCUT_PYTHON`에 실행 파일 경로를 설정하고 Android Studio를 다시 시작합니다. 일반 앱 수정·Run은 GitHub Actions를 호출하지 않습니다.

Windows의 `preBuild`는 `scripts/native-engine-artifact.py prepare`를 실행합니다. 현재 네이티브 소스가 설치된 엔진과 일치하는지 검사한 뒤 두 ABI 라이브러리와 라이선스 고지를 생성 폴더로 복구합니다. 캐시는 `.gradle/native-engine`에 있으므로 Gradle `clean`으로 지워지지 않습니다.

## 엔진을 수정했을 때

검사 대상은 `native/**`, `third_party/licenses/**`, `scripts/build-native-smartcut.sh`, `scripts/native-smartcut-sources.json`, `scripts/collect-native-licenses.py`입니다. 라이선스 원문은 엔진 고지 생성에 사용됩니다. 추가·수정·삭제를 모두 검사하며 텍스트의 CRLF/LF 차이는 허용합니다. 앱 UI만 바뀌었다면 엔진을 다시 빌드하지 않습니다.

1. 원격 최신 코드를 확인하고 작업을 반영합니다. 엔진 변경은 `dev/native-engine/windows` 또는 `dev/native-engine/` 아래의 별도 브랜치에 커밋·push합니다. 다운로드 도구는 커밋·push하지 않습니다.
2. GitHub의 **QuickCut native engine** Actions가 해당 push의 엔진을 빌드하고 호스트 테스트를 실행합니다. 이 워크플로는 엔진 ZIP만 만듭니다.
3. 저장소의 `Update-Engine.cmd`를 실행합니다. 기본 대상은 `dev/native-engine/windows`입니다. 진행 중인 빌드는 완료될 때까지 기다리고, 성공한 엔진을 내려받아 검증·설치합니다.
4. Android Studio에서 다시 Run합니다. 내려받은 엔진과 다른 로컬 엔진 수정이 남아 있으면 사용을 막으므로 해당 변경도 빌드한 뒤 다시 가져옵니다.

PowerShell 예시:

```powershell
.\Update-Engine.cmd
.\Update-Engine.cmd -Ref dev/native-engine/my-change
.\Update-Engine.cmd -RunId 123456789
python scripts/native-engine-artifact.py status --json
```

특정 Run ID를 지정해도 워크플로·저장소·브랜치·커밋·성공 여부와 로컬 엔진 소스를 검사합니다. 다른 브랜치 실행이라면 `-Ref`도 함께 지정합니다. 실패한 최신 실행 대신 오래된 성공 엔진으로 자동 대체하지 않습니다.

`-Dispatch`는 새 원격 실행을 요청하는 명시적 옵션입니다. GitHub는 수동 실행에 워크플로가 기본 브랜치에 등록되어 있을 것을 요구하므로, 최초 등록 전에는 엔진 개발 브랜치 push로 시작합니다. 이후 등록을 마치면 다음과 같이 같은 ref를 수동 재빌드할 수 있습니다.

```powershell
.\Update-Engine.cmd -Ref dev/native-engine/windows -Dispatch
```

## 인증과 검증

GitHub CLI(`gh`)가 필요합니다. 현재 PC의 준비 경로는 `D:\dev_app\.tools\gh\bin\gh.exe`이며 다운로드 도구가 자동으로 찾습니다. 기존 `gh` 인증 또는 Git Credential Manager의 GitHub 인증을 사용합니다. 자격 정보는 하위 프로세스 메모리에서만 전달하고 파일이나 로그에 저장하지 않습니다.

다운로드 도구는 예상 워크플로의 성공 실행과 커밋을 확인합니다. 번들은 파일 목록·SHA-256·크기·ABI별 ELF·16KiB 정렬·엔진 소스 지문을 모두 통과해야 설치됩니다. 검증 실패 시 기존 엔진을 유지합니다. `manifest.json`과 설치 영수증으로 출처를 확인할 수 있습니다.

처음 준비한 이 PC의 엔진은 공식 `v0.4.21` APK에서 검증한 라이브러리입니다. `release-bootstrap`으로 구분하며 해당 릴리스의 엔진 소스와 현재 소스가 같을 때만 사용합니다. 새 Actions 실행 결과를 설치하면 그 출처의 엔진으로 교체됩니다. 새 PC에서는 먼저 성공한 Actions 엔진을 내려받아야 합니다.

## 검사 명령

```powershell
python scripts/test-native-engine-artifact.py
python scripts/test-update-native-engine.py
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Linux에서 기존 소스 빌드를 수행하는 경로는 그대로 유지합니다. Windows 전용 준비 작업은 Linux Gradle 실행에서 건너뜁니다.
