import SwiftUI

struct AddProjectSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onSave: (ExpoProject) -> Void

    @State private var title = ""
    @State private var year = ""
    @State private var subtitle = ""
    @State private var note = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("名称", text: $title)
                TextField("时间", text: $year)
                TextField("地点", text: $subtitle)
                TextField("备注", text: $note, axis: .vertical)
            }
            .navigationTitle("添加项目")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        onSave(
                            ExpoProject(
                                id: "custom-\(Int(Date().timeIntervalSince1970 * 1000))",
                                title: title,
                                subtitle: subtitle,
                                year: year,
                                note: note,
                                halls: [],
                                booths: []
                            )
                        )
                        dismiss()
                    }
                    .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .liquidGlass()
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

struct AddBoothSheet: View {
    @Environment(\.dismiss) private var dismiss
    let defaultHallId: String
    let onSave: (String, String, String, String, Bool) -> Void

    @State private var hallId = ""
    @State private var boothCode = ""
    @State private var name = ""
    @State private var note = ""
    @State private var planned = false

    var body: some View {
        NavigationStack {
            Form {
                TextField("展馆", text: $hallId)
                TextField("展台号", text: $boothCode)
                TextField("展台名称", text: $name)
                TextField("备注", text: $note, axis: .vertical)
                Toggle("加入计划", isOn: $planned)
            }
            .navigationTitle("添加展台")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        onSave(
                            hallId.trimmingCharacters(in: .whitespaces).uppercased(),
                            boothCode.trimmingCharacters(in: .whitespaces).uppercased(),
                            name.trimmingCharacters(in: .whitespaces),
                            note,
                            planned
                        )
                        dismiss()
                    }
                    .disabled(hallId.isEmpty || boothCode.isEmpty || name.isEmpty)
                }
            }
            .liquidGlass()
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .onAppear { hallId = defaultHallId }
    }
}

struct HallPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let halls: [Hall]
    let onPick: (Hall) -> Void

    var body: some View {
        NavigationStack {
            List(halls) { hall in
                Button {
                    onPick(hall)
                    dismiss()
                } label: {
                    VStack(alignment: .leading) {
                        Text([hall.id, hall.title].filter { !$0.isEmpty }.joined(separator: " "))
                            .fontWeight(.semibold)
                        if !hall.subtitle.isEmpty {
                            Text(hall.subtitle)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("选择展馆")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
            .liquidGlass()
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

struct FileSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onImport: () -> Void
    let onExport: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Button("导入 JSON") { onImport(); dismiss() }
                Button("导出 JSON") { onExport(); dismiss() }
            }
            .navigationTitle("数据文件")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("关闭") { dismiss() }
                }
            }
            .liquidGlass()
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}
