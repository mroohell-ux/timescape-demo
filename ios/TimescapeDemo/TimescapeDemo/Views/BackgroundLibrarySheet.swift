import SwiftUI
import PhotosUI
import UIKit

struct BackgroundLibrarySheet: View {
    @Binding var photosSelection: [PhotosPickerItem]
    var backgrounds: [BackgroundReference]
    var onPhotosPicked: ([PhotosPickerItem]) async -> Void
    var onSetAppBackground: (BackgroundReference?) -> Void
    var onRemoveBackground: (BackgroundReference) -> Void
    var currentAppBackground: BackgroundReference?

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                PhotosPicker(selection: $photosSelection, matching: .images, photoLibrary: .shared()) {
                    Label("Import Photos", systemImage: "photo.on.rectangle")
                        .font(.headline)
                        .padding()
                        .frame(maxWidth: .infinity)
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)

                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 12)], spacing: 12) {
                        ForEach(backgrounds, id: \.id) { background in
                            BackgroundPreviewCell(
                                reference: background,
                                isSelected: background == currentAppBackground,
                                onSetAppBackground: { onSetAppBackground(background) },
                                onRemove: { onRemoveBackground(background) }
                            )
                        }
                    }
                    .padding(.horizontal)
                }

                Button("Reset App Background") {
                    onSetAppBackground(nil)
                }
                .frame(maxWidth: .infinity)
                .padding()
            }
            .padding()
            .navigationTitle("Background Library")
        }
        .task(id: photosSelection) {
            await onPhotosPicked(photosSelection)
        }
    }
}

private struct BackgroundPreviewCell: View {
    var reference: BackgroundReference
    var isSelected: Bool
    var onSetAppBackground: () -> Void
    var onRemove: () -> Void
    @State private var image: UIImage?

    var body: some View {
        VStack(spacing: 8) {
            ZStack(alignment: .topTrailing) {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Color.white.opacity(0.04))
                    .overlay(
                        Group {
                            if let image {
                                Image(uiImage: image)
                                    .resizable()
                                    .scaledToFill()
                            } else {
                                ProgressView()
                            }
                        }
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .aspectRatio(1, contentMode: .fit)
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(isSelected ? Color.accentColor : Color.white.opacity(0.1), lineWidth: isSelected ? 3 : 1)
                    )
                Button(role: .destructive, action: onRemove) {
                    Image(systemName: "trash")
                        .padding(8)
                        .background(Color.black.opacity(0.55), in: Circle())
                        .foregroundStyle(.white)
                }
                .padding(8)
            }
            Button(isSelected ? "Using" : "Set Background", action: onSetAppBackground)
                .font(.caption)
                .buttonStyle(.borderedProminent)
        }
        .task(id: reference.id) {
            image = await BackgroundImageLoader.shared.image(for: reference)
        }
    }
}
