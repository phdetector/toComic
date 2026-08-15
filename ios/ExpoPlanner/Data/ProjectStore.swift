import Foundation

final class ProjectStore: ObservableObject {
    @Published var customProjects: [ExpoProject] = []

    private let fileURL: URL

    init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        fileURL = directory.appendingPathComponent("expo_projects.json")
        load()
    }

    func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode([ExpoProject].self, from: data) else {
            return
        }
        customProjects = decoded
    }

    func save() {
        try? FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        if let data = try? JSONEncoder().encode(customProjects) {
            try? data.write(to: fileURL, options: .atomic)
        }
    }
}
