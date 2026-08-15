import Foundation

final class PlannerStore: ObservableObject {
    @Published var snapshot: PlannerSnapshot = PlannerSnapshot()

    private let fileURL: URL

    init(projectId: String) {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        fileURL = directory.appendingPathComponent("planner_\(projectId).json")
        load()
    }

    func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode(PlannerSnapshot.self, from: data) else {
            snapshot = PlannerSnapshot()
            return
        }
        snapshot = decoded
    }

    func save() {
        try? FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        if let data = try? JSONEncoder().encode(snapshot) {
            try? data.write(to: fileURL, options: .atomic)
        }
    }
}
