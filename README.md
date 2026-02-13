# Android Remote Agent

Kotlin Android app (minSdk 26 / targetSdk 34) implementing:

- AccessibilityService UI tree extraction + gesture control
- Embedded HTTP API server on `:8080` (NanoHTTPD)
- MediaProjection screenshot (`GET /screen`)
- Foreground service to keep agent alive
- Compose UI showing status / IP / port

## Build

```bash
cd /root/clawd/projects/android-remote
./gradlew assembleDebug
```

> Note: this environment may not contain Java/Android SDK. On a normal Android dev machine (JDK 17 + Android SDK 34), the project is ready to build.

## API

- `GET /screen`
- `GET /ui`
- `POST /tap` body: `{ "x": 100, "y": 200 }` or `{ "nodeId": "0.1.2" }`
- `POST /swipe` body: `{ "x1":100,"y1":100,"x2":500,"y2":1000,"durationMs":300 }`
- `POST /input` body: `{ "text": "hello" }`
- `POST /key` body: `{ "keyCode": "HOME" }` (`HOME/BACK/RECENTS/NOTIFICATIONS/QUICK_SETTINGS`)
- `POST /launch` body: `{ "packageName": "com.example.app" }`
- `POST /notification` body: `{ "title": "t", "body": "b" }`
- `GET /info`

## Runtime setup

1. Open app
2. Tap **开启 Accessibility Service** and enable service
3. Tap **授予截屏权限并启动服务**
4. Use `http://<device-ip>:8080`
