import SwiftUI

@main
struct TimescapeDemoApp: App {
    @StateObject private var library = MediaLibrary()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(library)
        }
    }
}
