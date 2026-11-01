# Mirra

Mirra is an experimental Android screen-casting app for devices that lack built-in Miracast support, such as Google Pixel phones.

It discovers nearby Wi-Fi Direct devices and attempts to stream your screen to Miracast-compatible receivers using a Shizuku-backed Wi-Fi Display source and a foreground screen-capture service.

Download the latest release: https://github.com/BlueCartoonCat/mirra/releases

https://github.com/user-attachments/assets/95bbd5f3-42d3-47d0-b984-bf395b3c6e4d

## Requirements

- Android 9 (API 28) or later, with Wi-Fi Direct. Playback audio capture requires Android 10 or later.
- A Miracast receiver in screen-mirroring mode.
- [Shizuku](https://shizuku.rikka.app/), started using wireless debugging or ADB and authorized for Mirra. Root is not required for this setup.
- Wi-Fi and the Nearby devices and Location permissions needed for Wi-Fi Direct discovery.

To build the app, install Android Studio or Android SDK Platform 36, Android SDK Build Tools, and JDK 17. Set `JAVA_HOME` to JDK 17 and configure the Android SDK in Android Studio or with `ANDROID_HOME`.
## Casting

1. Start Shizuku and put the receiver into its wireless display or Miracast mode.
2. Open Mirra, allow Wi-Fi Direct permissions, and select **Find displays**.
3. Select a display and confirm. Grant Shizuku access when prompted.
4. Choose whether to share device audio, grant audio permission if requested, and approve Android's screen-sharing prompt. Choose the entire screen if you want to switch between apps.
5. Use **Stop casting** or Android's screen-sharing controls to end the session.

Mirra requests audio-recording permission to capture eligible device playback, not microphone input. Android and individual apps can block capture of protected or opted-out content; that content may appear black or be silent. Some receivers or Android versions may not support the current audio/video implementation. Screen and audio capture only run after user consent and display Android foreground-service notifications.

## Contributing

Bug reports are most useful with the phone and Android version, receiver model, reproduction steps and relevant **redacted** app logs. Contributions should avoid committing IDE state, generated builds, signing keys or personal device information.

## License

Mirra is licensed under the [Apache License, Version 2.0](LICENSE).
