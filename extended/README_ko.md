# compose-multiplatform-extended

Compose Gradle 플러그인의 저장소인
[JetBrains/compose-multiplatform](https://github.com/JetBrains/compose-multiplatform) 을 thisisthepy
가 포크한 저장소입니다. 작업은 `extended` 브랜치에 있습니다. 저장소 루트는 업스트림 그대로 둡니다.
포크가 더한 것은 `gradle-plugins/` 아래의 플러그인에 있거나 `extended/` 아래에 있습니다.

English: [README.md](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/README.md)

## 더한 것

| 항목 | 상태 | 위치 |
|---|---|---|
| `packageNativeImage`: Compose 데스크톱 애플리케이션을 GraalVM native-image 실행 파일 하나로 (macOS arm64, Linux x64, Windows x64) | 구현 | [`extended/native-image`](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/native-image/README.md) |
| `runNativeImageAgent`: 이미지를 빌드할 때 쓰는 reachability 메타데이터 | 구현 | 같은 곳 |
| Windows: DPI 를 인식하는 실행 파일, 링크 전에 오래된 MSVC 툴셋을 잡는 검사 | 계획 | [#8](https://github.com/thisisthepy/compose-multiplatform-extended/pull/8) |
| 실제 화면에서의 Windows 실행 (probe 는 화면 밖에 렌더링합니다) | 계획 | [`extended/native-image`](https://github.com/thisisthepy/compose-multiplatform-extended/blob/extended/extended/native-image/README.md#not-yet) |
| 플러그인 ID `org.thisisthepy.compose` | 계획 | 브랜치 [`chore/thisisthepy-coordinates`](https://github.com/thisisthepy/compose-multiplatform-extended/tree/chore/thisisthepy-coordinates) |

함께 쓰는 Compose 라이브러리와 이미지가 링크하는 정적 skiko 아카이브는
[thisisthepy/compose-multiplatform-core-extended](https://github.com/thisisthepy/compose-multiplatform-core-extended/blob/extended/extended/README_ko.md)
에서 옵니다.

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
