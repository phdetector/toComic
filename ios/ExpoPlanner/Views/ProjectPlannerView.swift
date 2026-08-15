import SwiftUI

enum PlannerTab: String, CaseIterable, Identifiable {
    case plan = "计划"
    case overview = "总览"

    var id: String { rawValue }
}

struct ProjectPlannerView: View {
    let project: ExpoProject
    @Environment(\.dismiss) private var dismiss
    @StateObject private var store: PlannerStore
    @State private var tab = PlannerTab.overview
    @State private var query = ""
    @State private var searching = false
    @State private var showHallPicker = false
    @State private var showAddBooth = false
    @State private var showFileMenu = false

    init(project: ExpoProject) {
        self.project = project
        _store = StateObject(wrappedValue: PlannerStore(projectId: project.id))
    }

    private var allHalls: [Hall] {
        let custom = store.snapshot.customBooths
            .map(\.hallId)
            .uniqued()
            .map { Hall(id: $0, title: "", subtitle: "自定义展馆", accent: 0xFF8D867A) }
        return project.halls + custom
    }

    private var items: [BoothUi] {
        let booths = project.booths + store.snapshot.customBooths.map(\.asBooth)
        return booths
            .map { BoothUi(booth: $0, state: store.snapshot.states[$0.id] ?? BoothState()) }
            .filter { tab == .overview || $0.state.planned }
            .filter { item in
                guard !query.isEmpty else { return true }
                return [item.displayCode, item.booth.hallId, item.booth.boothCode, item.displayName, item.state.note]
                    .contains { $0.localizedCaseInsensitiveContains(query) }
            }
            .sorted { left, right in
                if tab == .plan {
                    return left.state.planOrder < right.state.planOrder
                }
                return naturalLess(left.displayCode, right.displayCode)
            }
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            if items.isEmpty {
                Spacer()
                Text(tab == .plan ? "收藏展台后会出现在这里" : "没有匹配的展台")
                    .foregroundStyle(.secondary)
                Spacer()
            } else {
                List {
                    ForEach(items) { item in
                        BoothCardView(
                            item: item,
                            onTogglePlan: {
                                withAnimation(.spring(response: 0.3, dampingFraction: 1)) {
                                    togglePlan(item.booth.id)
                                }
                            },
                            onSave: { name, note in
                                saveName(name, note: note, id: item.booth.id)
                            }
                        )
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                    }
                    .onMove { source, destination in
                        moveItems(from: source, to: destination)
                    }
                }
                .listStyle(.plain)
                .environment(\.editMode, tab == .plan ? .constant(.active) : .constant(.inactive))
            }

            bottomTabs
        }
        .background(Color(red: 0.96, green: 0.96, blue: 0.93))
        .sheet(isPresented: $showHallPicker) {
            HallPickerSheet(halls: allHalls) { hall in
                currentHallId = hall.id
            }
        }
        .sheet(isPresented: $showAddBooth) {
            AddBoothSheet(defaultHallId: currentHallId ?? "") { hallId, code, name, note, planned in
                addBooth(hallId: hallId, code: code, name: name, note: note, planned: planned)
            }
        }
        .sheet(isPresented: $showFileMenu) {
            FileSheet(
                onImport: { /* fileImporter placeholder */ },
                onExport: { /* fileExporter placeholder */ }
            )
        }
    }

    @State private var currentHallId: String?

    private var topBar: some View {
        HStack(spacing: 10) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
            }
            .liquidGlass()

            if searching {
                TextField("搜索展馆号, 名称, 备注", text: $query)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                Button {
                    searching = false
                    query = ""
                } label: {
                    Image(systemName: "xmark")
                }
                .liquidGlass()
            } else {
                Button {
                    showHallPicker = true
                } label: {
                    Text(currentHallId ?? "未设置展馆")
                        .font(.system(.body, design: .monospaced, weight: .bold))
                        .padding(.horizontal, 14)
                        .frame(height: 38)
                        .background(.thinMaterial, in: Capsule())
                }

                Spacer()

                Button { showAddBooth = true } label: { Image(systemName: "plus") }
                    .liquidGlass()
                Button { searching = true } label: { Image(systemName: "magnifyingglass") }
                    .liquidGlass()
                Button { showFileMenu = true } label: { Image(systemName: "square.and.arrow.up") }
                    .liquidGlass()
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.regularMaterial)
    }

    private var bottomTabs: some View {
        HStack(spacing: 10) {
            ForEach(PlannerTab.allCases) { item in
                Button {
                    tab = item
                } label: {
                    Text(item.rawValue)
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                        .frame(height: 46)
                        .background(tab == item ? Color(red: 0.85, green: 0.47, blue: 0.34) : .thinMaterial, in: Capsule())
                        .foregroundStyle(tab == item ? .white : .primary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(10)
        .liquidGlass()
    }

    private func togglePlan(_ id: String) {
        var state = store.snapshot.states[id] ?? BoothState()
        if state.planned {
            state.planned = false
            state.planOrder = Int.max
        } else {
            state.planned = true
            state.planOrder = (store.snapshot.states.values.map(\.planOrder).max() ?? -1) + 1
        }
        store.snapshot.states[id] = state
        store.save()
    }

    private func saveName(_ name: String, note: String, id: String) {
        var state = store.snapshot.states[id] ?? BoothState()
        state.customName = name.isEmpty || name == itemName(id) ? nil : name
        state.note = note
        store.snapshot.states[id] = state
        store.save()
    }

    private func itemName(_ id: String) -> String {
        (project.booths.first { $0.id == id } ?? store.snapshot.customBooths.first { $0.id == id }?.asBooth)?.name ?? ""
    }

    private func addBooth(hallId: String, code: String, name: String, note: String, planned: Bool) {
        let booth = CustomBooth(
            id: "custom-\(Int(Date().timeIntervalSince1970 * 1000))",
            hallId: hallId,
            boothCode: code,
            name: name,
            note: note,
            planned: planned,
            planOrder: planned ? (store.snapshot.states.values.map(\.planOrder).max() ?? -1) + 1 : Int.max
        )
        store.snapshot.customBooths.append(booth)
        store.snapshot.states[booth.id] = booth.asState
        store.save()
    }

    private func moveItems(from source: IndexSet, to destination: Int) {
        var items = items
        items.move(fromOffsets: source, toOffset: destination)
        for (index, item) in items.enumerated() {
            var state = store.snapshot.states[item.booth.id] ?? BoothState()
            state.planned = true
            state.planOrder = index
            store.snapshot.states[item.booth.id] = state
        }
        store.save()
    }
}

private func naturalLess(_ left: String, _ right: String) -> Bool {
    left.localizedStandardCompare(right) == .orderedAscending
}

private extension Array where Element: Hashable {
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}
