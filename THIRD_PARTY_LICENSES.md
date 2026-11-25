# Third-Party Licenses

EasyPi Mobile includes third-party open-source software. Those components are
licensed under their own terms, and your use of them must comply with those
licenses. EasyPi proprietary terms apply only to EasyPi-owned code and content
and do not replace or override third-party license obligations.

The embedded Chameleon VPN client is EasyPi-owned code from the `chameleon/`
directory of the [EasyPi](https://github.com/NewNetGuard/EasyPi) repository.
It is covered by that directory's own license, `chameleon/LICENSE` (EasyPi
Source Available License 1.2).

## Native library (Go)

The native library (`libgojni.so`) is built with Go and links the following
modules. The exact versions are pinned by EasyPi's Chameleon `go.mod`.

| Component | License |
|---|---|
| [Go standard library](https://go.dev/LICENSE) | BSD-3-Clause |
| [golang.org/x/crypto](https://pkg.go.dev/golang.org/x/crypto) | BSD-3-Clause |
| [golang.org/x/mobile](https://pkg.go.dev/golang.org/x/mobile) (gomobile binding runtime) | BSD-3-Clause |
| [golang.org/x/net](https://pkg.go.dev/golang.org/x/net) | BSD-3-Clause |
| [golang.org/x/sys](https://pkg.go.dev/golang.org/x/sys) | BSD-3-Clause |
| [golang.org/x/text](https://pkg.go.dev/golang.org/x/text) | BSD-3-Clause |
| [github.com/refraction-networking/utls](https://github.com/refraction-networking/utls) | BSD-3-Clause |
| [github.com/klauspost/compress](https://github.com/klauspost/compress) (`zstd`, `huff0`, `fse`) | BSD-3-Clause; the module's LICENSE also carries Apache-2.0 and MIT sections for some of its packages |
| [github.com/andybalholm/brotli](https://github.com/andybalholm/brotli) | MIT |

## Android libraries

The application links the following libraries and their transitive
dependencies, all under the Apache License 2.0:

| Component | License |
|---|---|
| [AndroidX](https://developer.android.com/jetpack/androidx): Activity, Annotation, Arch Core, Autofill, Collection, Compose (Animation, Foundation, Material, Material 3, Material Icons, Runtime, UI), Concurrent, Core, CustomView, Emoji2, Graphics, Interpolator, Lifecycle, Profile Installer, Room, SavedState, SQLite, Startup, Tracing, VersionedParcelable, WorkManager | Apache-2.0 |
| [Kotlin standard library](https://github.com/JetBrains/kotlin) | Apache-2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 |
| [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Apache-2.0 |
| [JetBrains Java Annotations](https://github.com/JetBrains/java-annotations) | Apache-2.0 |
| [Guava ListenableFuture](https://github.com/google/guava) | Apache-2.0 |

## Build tools

The Gradle Wrapper (`gradle/wrapper/gradle-wrapper.jar`, `gradlew`,
`gradlew.bat`) is part of [Gradle](https://gradle.org) and is licensed under
the Apache License 2.0. It is used only to build the project and is not
included in the APK.

## Regenerating this list

```powershell
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath
go version -m <path to the extracted libgojni.so>
```
