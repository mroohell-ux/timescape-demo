import SwiftUI

struct FlowSelectorView: View {
    var flows: [CardFlow]
    var selectedIndex: Int
    var onSelect: (Int) -> Void
    var onRename: (CardFlow, String) -> Void
    var onDelete: (CardFlow) -> Void

    @State private var editingFlow: CardFlow?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                ForEach(Array(flows.enumerated()), id: \.element.id) { index, flow in
                    let isSelected = index == selectedIndex
                    FlowChipView(
                        flow: flow,
                        isSelected: isSelected,
                        onTap: { onSelect(index) },
                        onLongPress: {
                            editingFlow = flow
                        },
                        onDelete: { onDelete(flow) }
                    )
                }
            }
            .padding(.horizontal, 16)
        }
        .sheet(item: $editingFlow) { flow in
            FlowRenameSheet(name: flow.name) { result in
                switch result {
                case .rename(let name):
                    onRename(flow, name)
                case .delete:
                    onDelete(flow)
                case .cancel:
                    break
                }
                editingFlow = nil
            }
        }
    }
}

private struct FlowChipView: View {
    var flow: CardFlow
    var isSelected: Bool
    var onTap: () -> Void
    var onLongPress: () -> Void
    var onDelete: () -> Void

    @State private var showContext = false

    var body: some View {
        Button(action: onTap) {
            Text(flow.name)
                .font(.system(size: 16, weight: .medium))
                .padding(.vertical, 8)
                .padding(.horizontal, 16)
                .background(isSelected ? Color.white.opacity(0.2) : Color.white.opacity(0.05))
                .foregroundStyle(Color.white)
                .clipShape(Capsule())
                .overlay(
                    Capsule().stroke(Color.white.opacity(isSelected ? 0.8 : 0.3), lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
        .simultaneousGesture(
            LongPressGesture(minimumDuration: 0.4).onEnded { _ in
                showContext = true
                onLongPress()
            }
        )
        .contextMenu {
            Button("Rename", action: onLongPress)
            Divider()
            Button(role: .destructive, action: onDelete) {
                Label("Delete", systemImage: "trash")
            }
        }
    }
}

private struct FlowRenameSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    var onComplete: (FlowRenameAction) -> Void

    init(name: String, onComplete: @escaping (FlowRenameAction) -> Void) {
        _name = State(initialValue: name)
        self.onComplete = onComplete
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Flow Name", text: $name)
            }
            .navigationTitle("Edit Flow")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                        onComplete(.cancel)
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        dismiss()
                        onComplete(.rename(name.trimmingCharacters(in: .whitespacesAndNewlines)))
                    }
                }
                ToolbarItem(placement: .bottomBar) {
                    Button(role: .destructive) {
                        dismiss()
                        onComplete(.delete)
                    } label: {
                        Label("Delete Flow", systemImage: "trash")
                    }
                }
            }
        }
    }
}

enum FlowRenameAction {
    case rename(String)
    case delete
    case cancel
}
