# Android app

## Debug backend target

Debug builds can point to a local backend without editing source files.

Physical phone via `adb reverse`:

```bash
adb reverse tcp:8000 tcp:8000
./gradlew installDebug -PsmartTourismDebugApiTarget=phone
```

Android emulator:

```bash
./gradlew installDebug -PsmartTourismDebugApiTarget=emulator
```

Custom host:

```bash
./gradlew installDebug -PsmartTourismDebugApiBaseUrl=http://192.168.1.20:8000/
```
