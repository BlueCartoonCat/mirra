# Mirra

Mirra is an experimental screen-casting app for devices that lack built-in Miracast support, such as Google Pixel phones.

It discovers nearby Wi-Fi Direct devices and attempts to stream your screen to Miracast-compatible receivers using a Shizuku-backed Wi-Fi Display source.

Download the latest release: https://github.com/BlueCartoonCat/mirra/releases

## Casting modes

- **Current screen:** Mirrors your phone using Android screen capture.
  
https://github.com/user-attachments/assets/95bbd5f3-42d3-47d0-b984-bf395b3c6e4d

- **External screen:** Uses [scrcpy](https://github.com/Genymobile/scrcpy) to create a separate virtual display. Open apps on the receiver while continuing to use your phone.

https://github.com/user-attachments/assets/f86375b5-c952-4f21-be88-7ea1778300ac

## Features

### scrcpy support

External-screen casting uses the bundled [scrcpy](https://github.com/Genymobile/scrcpy) server, started through Shizuku without a connected computer.

**scrcpy settings** lets you choose the virtual display's resolution, density, maximum frame rate, video bitrate, default app and mouse input mode.

### Resolution overrides

Choose which resolutions and frame rates Mirra may use from **Set resolutions**. Standard modes go up to 1080p at 60 fps, depending on the phone and receiver.

An experimental override can bypass the receiver's advertised H.264 limits and try 1440p, 4K, 8K, or one custom width, height and frame rate.

These modes still need a compatible phone encoder. See **Hacks and Quirks** below for how the override works and its limitations.

### Mouse and keyboard controller

Use your phone as a touchpad for the external screen, with tap to click, double-tap to right-click, long-press to drag, two-finger scrolling and pinch to zoom.

Send text, use navigation controls, launch apps on the external screen or turn off the phone display. Mouse input can use a virtual HID mouse on Android 15 or later, or injected touch with a cursor.

### Remote and gamepad controllers

Open a directional remote with navigation buttons or a multi-touch gamepad with two sticks, a directional pad, face buttons, shoulders and triggers. The gamepad's **Edit** mode lets you move and resize controls.

Both controllers require Android 15 or later and virtual HID support. Triggers are full-press controls, not pressure sensitive.

### Optional device audio

Share supported playback audio, or cast without sound.

Current-screen audio capture requires Android 10 or later; scrcpy audio requires Android 11 or later and device support. External-screen audio may include playback from the whole phone, not just the external display.

Mirra requests audio-recording permission for device playback, not microphone input.

Android and individual apps can block capture of protected or opted-out content; that content may appear black or be silent.

## How is this possible?

Android exposes much of Wi-Fi Direct through the public
`WifiP2pManager` API, including peer discovery and connection management.
Advertising a device as a Wi-Fi Display source, however, requires hidden APIs
and the privileged `android.permission.CONFIGURE_WIFI_DISPLAY` permission,
which an ordinary app cannot grant itself.

Mirra binds to a **Shizuku user service** over Binder. The service runs under
the shell identity, creates its own Wi-Fi P2P channel, and calls the hidden
`WifiP2pManager.setWfdInfo()` API through reflection. This advertises the
phone as an available Wi-Fi Display source on RTSP control port `7236`.
Mirra then negotiates a Miracast session with the receiver over RTSP.

For external-screen mode, Mirra uses scrcpy to create a display. Mirra
receives the video through a local socket bridge and feeds it into the
Miracast pipeline. Separate scrcpy sockets carry controls and optional audio.

## Requirements

- Android 9 or later, with Wi-Fi Direct.
- A Miracast receiver in screen-mirroring mode.
- [Shizuku](https://shizuku.rikka.app/), started using wireless debugging or ADB and authorized for Mirra. Root is not required for this setup.
- Wi-Fi and the Nearby devices and Location permissions needed for Wi-Fi Direct discovery.

Compatibility has only been tested on Pixel devices running the latest Pixel update. Other phones, receivers and apps may behave differently.

Desktop windowing may need experimental desktop settings in Developer options; Mirra warns when these are not enabled.

## How to cast?

1. Start Shizuku and put the receiver into its wireless display or Miracast mode.
2. Open Mirra, allow Wi-Fi Direct permissions, and select **Find displays**.
3. Select a display, choose a connection style if prompted, and confirm. Grant Shizuku access when prompted.
4. Choose **Cast phone screen** or **Create an external screen**, then choose whether to share device audio. Grant audio permission if requested. For current-screen casting, approve Android's screen-sharing prompt and choose the entire screen if you want to switch between apps.
5. For an external screen, open **Mouse and keyboard** to control it, launch apps, or switch to the remote or gamepad. Use the on-screen back chevron to leave a controller.
6. Use **Stop casting** to end the session. Current-screen casting can also be stopped through Android's screen-sharing controls.

## Troubleshooting

If a receiver will not connect, try the other connection style or clear saved Wi-Fi Direct groups as described below.

If playback fails after changing resolutions, disable the experimental override and try standard video modes.

## Hacks and Quirks

### Saved Wi-Fi Direct groups

Android can keep saved Wi-Fi Direct groups that interfere with new connections, especially with Windows receivers. Open **Wi-Fi Direct settings** from Mirra's menu, manually remove saved groups and retry. Mirra does not delete them or ordinary Wi-Fi networks automatically.

### Resolution overrides

The experimental override ignores the receiver's advertised H.264 level limits and sends the stream anyway. For 1440p, 4K, 8K and custom modes, Mirra advertises a compatible standard mode but sends the requested stream instead. This is a workaround, not proper negotiation of those resolutions.

Some TVs accept the stream despite their advertised limits. Others may show a black screen, disconnect or refuse playback.

### Direct and shared connections

The direct approach, labeled **Forced host** in the app, asks the phone to host the Wi-Fi Direct group. This can help with Windows receivers. **Shared** lets the phone and receiver negotiate which device hosts and is often better for TVs. Neither works with every receiver.

You can remember the choice for each receiver. Long-press a device to clear it.

### Back gestures in controllers

Controller activities use non-focusable windows so input can stay directed at the external display. On some devices, the system Back gesture does not work correctly with this activity setup and can leave the app unresponsive (ANR).

Use the controller's on-screen back chevron instead of the system Back gesture.

## Contributions and bug reports

Found a bug or have an idea? [Open an issue](https://github.com/BlueCartoonCat/mirra/issues). For bugs, include your phone, Android version, receiver model and steps to reproduce the problem.

## License and acknowledgments

Mirra is licensed under the [Apache License, Version 2.0](LICENSE).

Mirra uses [scrcpy](https://github.com/Genymobile/scrcpy) by Genymobile and [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps, both licensed under Apache 2.0. Thanks to their developers for making this project possible.
