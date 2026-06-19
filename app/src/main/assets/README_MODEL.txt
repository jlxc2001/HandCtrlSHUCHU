MediaPipe hand model location for this project:

app/src/main/assets/models/hand_landmarker.task

Do not place the model directly at app/src/main/assets/hand_landmarker.task.
Some MediaPipe Android builds require the modelAssetPath to contain a slash, so the Java code uses:

BaseOptions.builder().setModelAssetPath("models/hand_landmarker.task")
