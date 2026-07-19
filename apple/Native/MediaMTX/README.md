# MediaMTX iOS bridge

`build-ios-device.sh` stages the verified MediaMTX source tree in a temporary
directory, adds the mobile entry point, and compiles an arm64 iOS C archive.
It does not modify or duplicate the authoritative Go checkout.

```sh
apple/Native/MediaMTX/build-ios-device.sh
apple/Native/MediaMTX/build-ios-simulator.sh
apple/Native/MediaMTX/build-xcframework.sh
```

Override `MEDIAMTX_SOURCE_DIR` when the verified checkout is elsewhere. The
generated device and arm64 Simulator archives and C headers are written beneath
`apple/Build/MediaMTX` and are intentionally ignored by Git.

`build-xcframework.sh` produces the module-enabled artifact consumed by Swift.

The ABI currently exposes in-process start/stop and a complete-line log callback.
Swift parses those lines into the same lifecycle events used by Android.
Configuration ownership and an iOS Simulator archive slice are the next bridge
steps.
