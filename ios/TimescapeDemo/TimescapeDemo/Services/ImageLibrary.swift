import Foundation
import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import UIKit

struct PickedImage: Transferable {
    let data: Data
    let uti: String

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(importedContentType: .image) { data in
            let uti = UTType.image.identifier
            return PickedImage(data: data, uti: uti)
        }
    }
}

actor ImageLibrary {
    private let directory: URL

    init() {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        directory = base.appendingPathComponent("Backgrounds", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    func store(items: [PickedImage]) async -> [BackgroundReference] {
        guard !items.isEmpty else { return [] }
        var references: [BackgroundReference] = []
        for item in items {
            let id = UUID()
            let fileURL = directory.appendingPathComponent("\(id.uuidString).png")
            do {
                if let image = UIImage(data: item.data), let png = image.pngData() {
                    try png.write(to: fileURL, options: [.atomic])
                } else {
                    try item.data.write(to: fileURL, options: [.atomic])
                }
                references.append(BackgroundReference(id: id, kind: .stored, resource: fileURL.lastPathComponent))
            } catch {
                continue
            }
        }
        return references
    }

    func url(for reference: BackgroundReference) -> URL? {
        switch reference.kind {
        case .asset, .bundled:
            return Bundle.main.url(forResource: reference.resource, withExtension: nil)
        case .stored:
            return directory.appendingPathComponent(reference.resource)
        }
    }
}
