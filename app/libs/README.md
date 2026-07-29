# Samsung S Pen Remote SDK

Place Samsung's official S Pen Remote SDK AAR in this directory as:

`penremote-v1.0.0.aar` or `penremote-v1.0.0.jar`

The app packages the AAR automatically when it is present. Non-Samsung builds remain supported and
fall back to Android's standard stylus `KeyEvent`/`MotionEvent` APIs.
