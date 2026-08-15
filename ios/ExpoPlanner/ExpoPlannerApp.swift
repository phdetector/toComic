import SwiftUI

@main
struct ExpoPlannerApp: App {
    @StateObject private var projectStore = ProjectStore()

    var body: some Scene {
        WindowGroup {
            ProjectHomeView()
                .environmentObject(projectStore)
        }
    }
}
