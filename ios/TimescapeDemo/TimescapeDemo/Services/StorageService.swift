import Foundation

actor StorageService {
    private let url: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(fileName: String = "AppSnapshot.json") {
        let directory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        url = directory.appendingPathComponent(fileName)
        encoder.outputFormatting = [.prettyPrinted]
        decoder.dateDecodingStrategy = .iso8601WithFractionalSeconds
        encoder.dateEncodingStrategy = .iso8601WithFractionalSeconds
    }

    func load() async throws -> AppSnapshot {
        if !FileManager.default.fileExists(atPath: url.path) {
            return AppSnapshot(flows: [], backgrounds: [], appBackground: nil, selectedFlowIndex: 0, cardFontSize: 17)
        }
        let data = try Data(contentsOf: url)
        return try decoder.decode(AppSnapshot.self, from: data)
    }

    func save(_ snapshot: AppSnapshot) async throws {
        let data = try encoder.encode(snapshot)
        try data.write(to: url, options: [.atomic])
    }
}

private extension JSONEncoder {
    static let iso8601WithFractionalSeconds: JSONEncoder.DateEncodingStrategy = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return .custom { date, encoder in
            var container = encoder.singleValueContainer()
            container.encode(formatter.string(from: date))
        }
    }()
}

private extension JSONDecoder {
    static let iso8601WithFractionalSeconds: JSONDecoder.DateDecodingStrategy = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            return formatter.date(from: string) ?? Date()
        }
    }()
}
