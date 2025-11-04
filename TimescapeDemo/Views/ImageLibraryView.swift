import PhotosUI
import SwiftUI
import UIKit

struct ImageLibraryView: View {
    @EnvironmentObject private var library: MediaLibrary
    @State private var selections: [PhotosPickerItem] = []

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 20), count: 3)

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 0.02, green: 0.04, blue: 0.1), Color(red: 0.1, green: 0.02, blue: 0.18)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            if library.userImages.isEmpty {
                VStack(spacing: 18) {
                    Image(systemName: "sparkles.rectangle.stack")
                        .font(.system(size: 46))
                        .foregroundStyle(.white.opacity(0.6))
                    Text("Drop in your own shots to remix the rail.")
                        .font(.system(.title3, design: .rounded).weight(.medium))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white.opacity(0.7))
                        .padding(.horizontal, 32)
                }
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 20) {
                        ForEach(library.userImages) { image in
                            ZStack(alignment: .topTrailing) {
                                image.image
                                    .resizable()
                                    .scaledToFill()
                                    .frame(height: 120)
                                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                                            .strokeBorder(image.accent.opacity(0.65), lineWidth: 1.2)
                                            .shadow(color: image.accent.opacity(0.6), radius: 10, x: 0, y: 6)
                                            .blendMode(.plusLighter)
                                    )
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                                            .fill(.ultraThinMaterial.opacity(0.25))
                                    )

                                Button {
                                    library.removeImage(image)
                                } label: {
                                    Image(systemName: "xmark")
                                        .font(.footnote.weight(.bold))
                                        .foregroundStyle(.white)
                                        .frame(width: 24, height: 24)
                                        .background(image.accent.opacity(0.6), in: Circle())
                                }
                                .padding(8)
                            }
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 24)
                }
            }
        }
        .toolbar { libraryToolbar }
        .onChange(of: selections) { _ in
            Task { await importSelections() }
        }
    }

    @ToolbarContentBuilder
    private var libraryToolbar: some ToolbarContent {
        ToolbarItem(placement: .navigationBarTrailing) {
            PhotosPicker(selection: $selections, matching: .images) {
                Label("Add", systemImage: "plus.app")
            }
        }
    }

    private func importSelections() async {
        let items = selections
        guard !items.isEmpty else { return }

        for item in items {
            if let data = try? await item.loadTransferable(type: Data.self),
               let image = UIImage(data: data) {
                await MainActor.run {
                    library.addImage(image)
                }
            }
        }

        await MainActor.run { selections.removeAll() }
    }
}

struct ImageLibraryView_Previews: PreviewProvider {
    static var previews: some View {
        NavigationStack {
            ImageLibraryView()
                .environmentObject(MediaLibrary())
        }
    }
}
