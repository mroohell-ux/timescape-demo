# Timescape for iOS

This folder contains a SwiftUI implementation of the Timescape demo tailored for iOS. Open the project by creating a new Xcode "App" project (SwiftUI lifecycle) targeting iOS 17 or later and copy the contents of the `TimescapeDemo` folder into the generated target, or generate an Xcode project via Swift Package Manager using the upcoming iOS application support.

The UI mirrors the Android sample:

- Horizontally paged card flow with promotion-ready frame rates (120 fps) and liquid glass card styling.
- Flow selector chips with rename/delete affordances.
- Card editor with support for handwriting using PencilKit.
- Background library with Photos import and app-wide background selection.
- Adjustable card typography.

All persistent data is stored in `AppSnapshot.json` within the application's documents directory.
