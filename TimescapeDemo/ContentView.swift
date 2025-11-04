import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var library: MediaLibrary

    var body: some View {
        TabView {
            NavigationStack {
                CardRailView()
                    .environmentObject(library)
                    .navigationTitle("Timescape")
                    .toolbar { toolbarContent }
            }
            .tabItem {
                Label("Moments", systemImage: "sparkles")
            }

            NavigationStack {
                ImageLibraryView()
                    .environmentObject(library)
                    .navigationTitle("Images")
                    .toolbar { toolbarContent }
            }
            .tabItem {
                Label("Gallery", systemImage: "photo.on.rectangle")
            }
        }
        .tint(Color.accentColor)
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .navigationBarTrailing) {
            Text("iOS 16")
                .font(.callout.smallCaps())
                .foregroundStyle(.secondary)
        }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
            .environmentObject(MediaLibrary())
    }
}
