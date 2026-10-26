import SwiftData
import SwiftUI

@main
struct MyPapersApp: App {
    let container: ModelContainer = {
        do {
            return try ModelContainer(for: Paper.self)
        } catch {
            fatalError("Can't open the papers' store: \(error)")
        }
    }()

    var body: some Scene {
        WindowGroup {
            Text("My Papers")
        }
        .modelContainer(container)
    }
}
