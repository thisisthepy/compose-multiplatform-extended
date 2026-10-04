# compose-multiplatform-extended

Compose Gradle 플러그인을 확장해, 같은 `compose.desktop` 설정으로 데스크톱 애플리케이션을 JVM 앱, GraalVM native-image 실행 파일 하나, Kotlin/Native 실행 파일 하나 중 어느 쪽으로든 내보냅니다.

English: [README.md](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/README.md)

Compose Gradle 플러그인의 저장소인
[JetBrains/compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) 을 thisisthepy
가 포크한 저장소입니다. 작업은 `extended` 브랜치에 있습니다. 저장소 루트는 업스트림 그대로 둡니다.
포크가 더한 것은 `gradle-plugins/` 아래의 플러그인에 있거나 `extended/` 아래에 있습니다. Compose 라이브러리의 포크인
[compose-multiplatform-core-extended](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/README_ko.md)
와 짝을 이룹니다.

## 기능

- 애플리케이션 하나, 출력 셋: `output = ApplicationOutput.Jvm`, `NativeImage`, `KotlinNative`. 이름, 버전, 제작사, 아이콘, 리소스는 `nativeDistributions` 에 한 번만 적습니다.
- `packageNativeImage`: 애플리케이션을 GraalVM native-image 실행 파일 하나로 만듭니다. 옆에 Java 런타임이 없습니다 (macOS arm64, Linux x64, Windows x64).
- `runNativeImageAgent`: 이미지를 빌드할 때 쓰는 reachability 메타데이터를 기록합니다.
- AWT 없는 창: `windowing = ApplicationWindowing.AwtFree` 를 쓰면 창이 JDK 의 AWT 가 아니라 extended 창 모듈에서 옵니다.
- macOS, Linux, Windows 용 Kotlin/Native 데스크톱 패키징: Info.plist 와 아이콘을 갖춘 `.app`, 서명, 공증, 유니버설 바이너리. AppImage 와 deb. exe 폴더, zip, msi.
- 모든 출력의 실행 태스크, 그리고 배율이 적용된 화면에서 DPI 를 인식하는 Windows 애플리케이션.
- 업스트림의 `compose` DSL 과 공개 API 는 그대로입니다. 기존 Compose 프로젝트가 그대로 동작하고, 새 설정은 추가만 합니다.

## 왜 쓰는가

업스트림의 `compose.desktop` 은 Java 런타임을 옆에 담아 애플리케이션을 내보냅니다. 내려받는 크기가 더 크고,
런타임도 계속 갱신해야 합니다. 이 포크에서는 같은 프로젝트로 네이티브 실행 파일 하나도 만들 수 있고,
AWT 없이 만들기 때문에 애플리케이션이 쓰지 않는 것은 실행 파일에 들어가지 않습니다.

## 설치

아직 공개 저장소에 없습니다. 플러그인을 로컬 Maven 저장소에 게시하고, `mavenLocal()` 을 Gradle Plugin Portal 보다 앞에 둡니다.

```sh
cd gradle-plugins
./gradlew --no-daemon :compose:publishToMavenLocal \
    -Pdeploy.version=1.11.1-extended-dev -Pcompose.version=1.11.1
```

`settings.gradle.kts` 와 `build.gradle.kts` 는 영어 README 의 Install 절과 같습니다. 플러그인은 당분간
JetBrains 의 ID 를 그대로 씁니다. `org.thisisthepy.compose` 로 바꾸는 일은 계획입니다.

## 출하하는 세 가지 방법

| 출력 | 결과 | 태스크 | 자세히 |
|---|---|---|---|
| JVM | Java 런타임을 옆에 둔 애플리케이션 (dmg, deb, msi 등) | `packageDistributionForCurrentOS` | 업스트림 그대로 |
| GraalVM native image | 런타임 없는 실행 파일 하나 | `packageNativeImage` | 아래 "실행 파일 하나" |
| Kotlin/Native | 타깃마다 실행 파일 하나, OS 에 맞게 패키징 | `packageKotlinNative` | [`gradle-plugin-kn`](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/gradle-plugin-kn/README.md) |

`packageApplication` 은 `output` 설정이 가리키는 출력을 실행합니다.

### Windows 는 MSVC Build Tools 가 필요하다

모든 `mingwX64` 타깃은 최종 MSVC 실행 파일로 나갑니다. Kotlin/Native 는 MinGW 오브젝트를 만들고, 이를
MSVC 링커가 받아들이도록 고쳐 씁니다. 그다음 MSVC 링커가 Skia 와 창 C 계층(둘 다 MSVC 빌드)과 함께
링크합니다. MinGW 만 쓰는 경로는 없습니다. 소유자가 2026-10-05 에 정한 일입니다.

> [user] "Msvc 있어야 하는게 뭐 어때서? 나는 물어보는거잖아. Mingw 우회 구현을 넣지 마. Extended는 Mingw 타겟도 전부 최종 msvc 앱으로 나가도록 하면 되는거지 그냥."

MSVC 툴셋 v14.51 이상, `clang-cl`, Windows SDK 가 있는 Visual Studio Build Tools 를 설치합니다. 빌드는 시작할 때
이들을 찾고, 하나라도 없으면 설치 방법을 알려 주며 멈춥니다.

### macOS 서명과 공증

Kotlin/Native `.app` 은 `signDistributableNative...` 가 서명합니다. `nativeDistributions.macOS.signing { identity = "..." }`
를 정하면 Developer ID 로, 아니면 ad hoc 으로 서명합니다(빌드한 기계에서 실행하기에는 충분합니다).
`notarizeDmgNative...` 는 `macOS.notarization { appleID, password, teamID }` 로 dmg 를 제출하고 티켓을
붙입니다. 자격 증명이 없으면 "Skipping notarization" 을 출력하고 성공하므로, Apple 자격 증명이 없는 CI 도 통과합니다.
태스크 전체와 Linux, Windows 선행 조건은
[`gradle-plugin-kn`](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/gradle-plugin-kn/README.md)
에 있습니다.

## 실행 파일 하나: packageNativeImage

지금 `compose.desktop` 이 만드는 것은 Java 런타임을 옆에 담은 애플리케이션입니다.
`packageNativeImage` 는 대신 GraalVM native image 를 만듭니다. 파일 하나이고, 런타임이 없고, 시작할 때
풀어 놓는 것도 없습니다. AWT, JAWT, Skia 는 정적 아카이브에서 링크해 넣습니다. 단일 실행 파일은 이들을
별도 파일로 열 수 없기 때문입니다. 플랫폼별 구성 요소와 측정 결과는 디렉터리별 README 에 있습니다.
[extended/native-image/README.md](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/native-image/README.md).

### 요구 사항

- **GraalVM: Liberica NIK 25 Full.** 이미지는 JDK 의 AWT 를 정적으로 링크하는데, 그럴 수 있는 것은
  JDK 의 정적 아카이브를 함께 배포하는 배포판뿐입니다. 아카이브가 없으면 태스크가 이 이유를 말하고
  멈춥니다. NIK 25.0.4.1 로 측정했습니다.
- **정적 skiko 아카이브.** compose-multiplatform-core-extended 의
  `extended/skiko/build-skiko-static-jvm.sh` 로 같은 시스템에서 빌드합니다. 단일 실행 파일은 Skia 를
  파일에서 불러올 수 없습니다.
- **호스트가 곧 타깃입니다.** native-image 는 실행 중인 시스템용으로 빌드합니다. 그래서 Linux 는 Linux
  에서, Windows 는 Windows 에서 빌드합니다.
- **Linux x64:**
  [Linux probe 워크플로](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/.github/workflows/native-image-linux-probe.yml)
  가 설치하는 X11, GL, fontconfig, freetype, dbus 개발 패키지.
- **Windows x64:**
  - x64 Native Tools Command Prompt 에서 (또는 `vcvars64.bat` 다음에) 실행합니다. 태스크가 MSVC
    라이브러리를 `LIB` 에서 찾고 `cl` 과 `dumpbin` 을 부르기 때문입니다.
  - MSVC 툴셋 14.51 이상. Skia 아카이브가 부르는 런타임 헬퍼 중 14.43 에 없는 것이 있어
    (`__std_find_first_of_trivial_pos_1`), 오래된 툴셋에서는 링크가 실패합니다. 지금은 그 실패가 빠진
    심볼 이름만 알려 줍니다. #8 이 병합되면 링크 전에 태스크가 이유를 말합니다.
  - skiko 아카이브를 위해 PATH 에 `clang-cl`. skiko 가 Windows 바인딩을 이것으로 컴파일하기
    때문입니다. `winget install LLVM.LLVM` 이나 Visual Studio 의 "C++ Clang Compiler for Windows"
    구성 요소로 설치합니다.

### 설정

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.compose") version "2.2.20"
    id("org.jetbrains.compose") version "1.11.1-extended-dev"
}

dependencies {
    implementation(compose.desktop.currentOs)
}

compose.desktop.application {
    mainClass = "hello.MainKt"
    nativeImage {
        graalvmHome = "/path/to/liberica-nik-25-full"      // 또는 GRAALVM_HOME
        skikoStaticDirectory = file("/path/to/work/out/macos-arm64")
    }
}
```

`nativeImage { }` 블록의 속성은 다음과 같습니다.

| 속성 | 뜻 | 기본값 |
|---|---|---|
| `graalvmHome` | 이미지를 빌드할 GraalVM | `GRAALVM_HOME` |
| `skikoStaticDirectory` | `build-skiko-static-jvm.sh` 의 출력: `libskiko-static.a` (Windows 에서는 `skiko-static.lib`) 와 `skia/` | 필수 |
| `imageName` | 실행 파일 이름 | 애플리케이션의 패키지 이름 |
| `metadataDirectory` | `runNativeImageAgent` 가 쓰고 `packageNativeImage` 가 읽는 메타데이터 위치 | `src/main/native-image` |
| `buildArgs` | 플러그인 자신의 인자 뒤에 붙는 native-image 인자 | 없음 |

[`extended/native-image/hello`](https://github.com/thisisthepy/compose-multiplatform-extended/tree/extended/extended/native-image/hello)
가 이렇게 설정한 완전한 프로젝트입니다.

### 실행

```sh
./gradlew runNativeImageAgent    # GraalVM 의 JVM 에서 애플리케이션을 실행하고 src/main/native-image 에 기록
./gradlew packageNativeImage     # build/compose/native-image/main/<name>
```

`runNativeImageAgent` 는 GraalVM 자신의 `java` 로 tracing agent 를 붙여 애플리케이션을 실행하고, 본
것을 `metadataDirectory` 에 합쳐 넣습니다. agent 는 정상 종료할 때 기록하므로, 애플리케이션을 강제로
끝내지 말고 닫아야 합니다. 새 클래스나 리소스에 닿는 변경을 했다면 다시 실행합니다. 이 디렉터리는
소스와 함께 둡니다. `packageNativeImage` 가 여기서 빌드하기 때문입니다.

`packageNativeImage` 는 실행 파일 하나만 쓰고 옆에 아무것도 두지 않습니다. 빌드 타입마다가 아니라
애플리케이션마다 한 번 등록됩니다. GraalVM 은 빌드 타입과 상관없이 이미지를 최적화하고, ProGuard 의
출력은 이미지를 분석할 대상이 아니기 때문입니다.

## Gradle 플러그인

### 지금: org.jetbrains.compose

`extended` 에서 플러그인은 JetBrains 의 ID 와 좌표를 그대로 씁니다. 그래서 Gradle Plugin Portal 보다
로컬 Maven 저장소에서 먼저 가져와야 합니다 (위의 `settings.gradle.kts`).

- 플러그인 ID: `org.jetbrains.compose` (`gradle-plugins/compose/build.gradle.kts`)
- 아티팩트: `org.jetbrains.compose:compose-gradle-plugin`
- 버전: `-Pdeploy.version` 으로 준 값입니다. `gradle-plugins/gradle.properties` 의 기본값은
  `9999.0.0-SNAPSHOT` 입니다. probe 와 `hello` 예제는 `1.11.1-extended-dev` 를 씁니다.

probe 워크플로와 같은 방법으로 로컬 저장소에 배포합니다.

```sh
cd gradle-plugins
./gradlew --no-daemon :compose:publishToMavenLocal \
    -Pdeploy.version=1.11.1-extended-dev -Pcompose.version=1.11.1
```

`-Pcompose.version` 은 플러그인의 별칭 (`compose.desktop.currentOs` 등) 이 가리키는 Compose
버전입니다. 아직 공개 저장소에 올린 것은 없습니다.

### 계획: org.thisisthepy.compose

**상태: 계획.**
[`chore/thisisthepy-coordinates`](https://github.com/thisisthepy/compose-multiplatform-extended/tree/chore/thisisthepy-coordinates)
브랜치는 플러그인을 이 포크 자신의 이름으로 배포합니다. JetBrains 의 `org.jetbrains.compose` 옆에 두어도
어느 한쪽이 다른 쪽을 대체하지 않게 하기 위해서입니다. 구현 클래스와 `compose { }` DSL 은 JetBrains 것
그대로입니다.

- 플러그인 ID: `org.thisisthepy.compose` (마커
  `org.thisisthepy.compose:org.thisisthepy.compose.gradle.plugin`)
- 아티팩트: `org.thisisthepy.compose:compose-gradle-plugin`
- 버전: `<upstream version>-ext.<N>`. 예: `1.11.1-ext.1`. 로컬 빌드는 `-ext.<N>-dev`
- 별칭은 compose-multiplatform-core-extended 의 `org.thisisthepy.compose.*` 라이브러리를 가리킵니다

이 변경이 들어오면 `plugins { }` 줄은 다음과 같아집니다.

```kotlin
plugins {
    id("org.thisisthepy.compose") version "1.11.1-ext.1-dev"
}
```
