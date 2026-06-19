@echo off
setlocal
cd /d "%~dp0"
if not exist app\src\main\assets\models mkdir app\src\main\assets\models
powershell -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task' -OutFile 'app/src/main/assets/models/hand_landmarker.task'"
if exist app\src\main\assets\models\hand_landmarker.task (
  echo OK: app\src\main\assets\models\hand_landmarker.task
  dir app\src\main\assets\models\hand_landmarker.task
) else (
  echo FAIL: model not downloaded
  exit /b 1
)
