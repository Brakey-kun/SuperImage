# Agent rules for SuperVideo

- After every code change (before yielding), run `.\gradlew.bat packageExecutables` from the repo root and make sure it succeeds. It refreshes `executables/` with `SuperVideo-release.apk`, `SuperVideo-debug.apk`, `SuperVideo-windows-x64/` (SuperVideo.exe) and `SuperVideo-windows-x64.zip`. A failing native/desktop build is a blocking error, not something to skip.
- `executables/` is git-ignored; never commit build outputs.
- Prerequisites on this machine: JDK 17 (JAVA_HOME), Android SDK + NDK 27.2.12479018, system CMake ≥ 3.22 (`cmake.dir` in local.properties), Ninja, MSYS2 UCRT64 gcc (`supervideo.mingwBin`), WSL Ubuntu only for `tools/convert_models.sh`.
- Model conversion outputs (`native/models/*.mnn`) are committed; regenerate only via `tools/convert_models.sh`.
- Native/integration tests: `.\gradlew.bat :desktop:prepareAppResources :core:test -Dsupervideo.nativeDir=<repo>/desktop/build/appResources` (they are skipped without the property; CPU inference makes the pipeline tests take ~15 min).
