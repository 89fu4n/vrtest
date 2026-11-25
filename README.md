# Orion Lite (native Android)

Phone mixed reality for a cheap VR viewer on Android:
camera passthrough, one picture per eye with lens correction, gyro head tracking,
MediaPipe hand tracking (pinch = tap), and real web windows (Browser, YouTube, Instagram, Maps…) floating in your room.

## Controls
- **Pinch on a window** = tap. Pinch and move = scroll / drag.
- **Bar under each window:** ◀ back · 🎤 voice search (say "youtube.com" or anything to Google it) · ⌨ type text · drag the middle to move it · ✕ close
- **Home bar:** drag to move, 🎯 recenter
- **Tap the screen** = recenter · **Double-tap** = settings (eye separation, lens correction, zoom, camera FOV)

## Build
Push this folder to GitHub → the "Build APK" action builds it → download from the Actions tab.
Or open it in Android Studio and press Run.

`BUILD_WORKFLOW_COPY.yml` is a visible copy of `.github/workflows/build-apk.yml`, in case your computer hides the `.github` folder.

## Code map
- `MainActivity.kt`: camera, permissions, windows, voice, typing, settings
- `MRRenderer.kt`: OpenGL drawing, stereo, lens distortion, hand cursor and pinch logic
- `HandTracker.kt`: MediaPipe hand landmarks → cursor + pinch
- `HeadTracker.kt`: gyro → head rotation
- `WebWindow.kt`: a WebView drawn into a texture + the bar under it
- `assets/home.html`: the home grid. Add your own app tiles here.
