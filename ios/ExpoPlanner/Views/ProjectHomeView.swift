import SwiftUI

extension View {
    @ViewBuilder
    func liquidGlass() -> some View {
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular)
        } else {
            self.background(.ultraThinMaterial)
        }
    }
}

struct ProjectCard: View {
    let project: ExpoProject

    var body: some View {
        HStack(spacing: 14) {
            Text(project.year)
                .font(.system(.body, design: .monospaced, weight: .bold))
                .padding(.horizontal, 14)
                .frame(height: 38)
                .background(.thinMaterial, in: Capsule())

            VStack(alignment: .leading, spacing: 4) {
                Text(project.title)
                    .font(.headline)
                    .lineLimit(1)
                Text(project.subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            Image(systemName: "chevron.right")
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .strokeBorder(.quaternary, lineWidth: 1)
        )
    }
}

struct ProjectHomeView: View {
    @EnvironmentObject private var projectStore: ProjectStore
    @State private var selectedProject: ExpoProject?
    @State private var showAddProject = false

    private var projects: [ExpoProject] {
        ProjectData.projects + projectStore.customProjects
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(spacing: 14) {
                    ForEach(projects) { project in
                        Button {
                            selectedProject = project
                        } label: {
                            ProjectCard(project: project)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(14)
            }
            .background(Color(red: 0.96, green: 0.96, blue: 0.93))
            .navigationTitle("展会项目")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showAddProject = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .liquidGlass()
                }
            }
            .toolbarBackground(.visible, for: .navigationBar)
        }
        .sheet(item: $selectedProject) { project in
            ProjectPlannerView(project: project)
        }
        .sheet(isPresented: $showAddProject) {
            AddProjectSheet { project in
                projectStore.customProjects.append(project)
                projectStore.save()
            }
        }
    }
}
