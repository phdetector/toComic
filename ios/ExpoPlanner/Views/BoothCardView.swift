import SwiftUI

struct BoothCardView: View {
    let item: BoothUi
    let onTogglePlan: () -> Void
    let onSave: (String, String) -> Void

    @State private var editing = false
    @State private var name = ""
    @State private var note = ""
    @State private var heartScale: CGFloat = 1

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Text(item.displayCode)
                    .font(.system(.body, design: .monospaced, weight: .bold))
                    .padding(.horizontal, 14)
                    .frame(height: 38)
                    .background(Color(red: 0.85, green: 0.47, blue: 0.34).opacity(0.16), in: Capsule())
                    .foregroundStyle(Color(red: 0.85, green: 0.47, blue: 0.34))

                if editing {
                    TextField("名称", text: $name)
                        .textFieldStyle(.roundedBorder)
                } else {
                    Text(item.displayName)
                        .font(.headline)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Button {
                    if editing {
                        onSave(name, note)
                    }
                    editing.toggle()
                } label: {
                    Image(systemName: editing ? "checkmark" : "pencil")
                }
                .buttonStyle(.plain)

                Button {
                    withAnimation(.spring(response: 0.3, dampingFraction: 1)) {
                        heartScale = 1.15
                        onTogglePlan()
                    }
                    withAnimation(.spring(response: 0.3, dampingFraction: 1).delay(0.1)) {
                        heartScale = 1
                    }
                } label: {
                    Image(systemName: item.state.planned ? "heart.fill" : "heart")
                        .foregroundStyle(item.state.planned ? Color(red: 0.78, green: 0.35, blue: 0.32) : Color.secondary)
                        .scaleEffect(heartScale)
                }
                .buttonStyle(.plain)
            }

            HStack(spacing: 12) {
                AsyncImage(url: item.booth.logoUrl.flatMap(URL.init)) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFit()
                    } else {
                        Text(String(item.displayName.prefix(8)))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(width: 72, height: 42)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 8))

                if editing {
                    TextEditor(text: $note)
                        .frame(maxHeight: .infinity)
                        .overlay(
                            RoundedRectangle(cornerRadius: 8)
                                .strokeBorder(.quaternary, lineWidth: 1)
                        )
                } else {
                    Text(note.isEmpty ? "备注" : note)
                        .font(.subheadline)
                        .foregroundStyle(note.isEmpty ? AnyShapeStyle(.secondary) : AnyShapeStyle(.primary))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        .padding(12)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
                }
            }
        }
        .padding(12)
        .frame(height: 216)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .strokeBorder(.quaternary, lineWidth: 1)
        )
        .onAppear {
            name = item.displayName
            note = item.state.note
        }
    }
}
