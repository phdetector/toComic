import Foundation

struct Hall: Identifiable, Codable, Hashable {
    let id: String
    let title: String
    let subtitle: String
    let accent: Int
}

struct Booth: Identifiable, Codable, Hashable {
    let id: String
    let hallId: String
    let boothCode: String
    let name: String
    let logoUrl: String?
}

struct BoothState: Codable, Hashable {
    var note: String = ""
    var customName: String?
    var planned: Bool = false
    var planOrder: Int = Int.max
}

struct CustomBooth: Identifiable, Codable, Hashable {
    let id: String
    var hallId: String
    var boothCode: String
    var name: String
    var note: String = ""
    var planned: Bool = false
    var planOrder: Int = Int.max

    var asBooth: Booth {
        Booth(id: id, hallId: hallId, boothCode: boothCode, name: name, logoUrl: nil)
    }

    var asState: BoothState {
        BoothState(note: note, customName: nil, planned: planned, planOrder: planOrder)
    }
}

struct PlannerSnapshot: Codable {
    var states: [String: BoothState]
    var customBooths: [CustomBooth]

    init(states: [String: BoothState] = [:], customBooths: [CustomBooth] = []) {
        self.states = states
        self.customBooths = customBooths
    }
}

struct BoothUi: Identifiable, Hashable {
    let booth: Booth
    var state: BoothState

    var id: String { booth.id }

    var displayName: String {
        state.customName?.isEmpty == false ? state.customName! : booth.name
    }

    var displayCode: String {
        "\(booth.hallId)-\(booth.boothCode)"
    }
}

struct ExpoProject: Identifiable, Codable {
    let id: String
    var title: String
    var subtitle: String
    var year: String
    var note: String
    var halls: [Hall]
    var booths: [Booth]
}
