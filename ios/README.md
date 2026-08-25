# toComic iOS

This directory contains the iOS source version of toComic, built with SwiftUI and targeting iOS 26 Liquid Glass.

## Requirements

- macOS with Xcode 26 or newer
- iOS 26 simulator or device

## Build

1. Open `ExpoPlanner.xcodeproj` (historical project filename) with Xcode.
2. Select the `ExpoPlanner` scheme (historical scheme name).
3. Run on an iOS 26 simulator or device.

## Notes

- Windows cannot build or sign iOS apps. This directory is source only; IPA generation must happen on macOS.
- Top bars, bottom tabs, and sheets use `.glassEffect(.regular)` on iOS 26 and fall back to `.ultraThinMaterial` on older systems.
- Booth cards use `ultraThinMaterial` backgrounds with rounded 8pt cards to keep scrolling smooth.
- Full ChinaJoy 2026 hall and booth data is generated in `Data/ChinaJoyData.swift` from the Android data source.
