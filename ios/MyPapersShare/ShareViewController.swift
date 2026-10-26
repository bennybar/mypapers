import UIKit
import UniformTypeIdentifiers

/**
 * "Share → My Papers" from Mail, Files, Photos or a browser: the shared PDFs and pictures are copied
 * into the app group's inbox, and the app adds them the next time it opens (an extension can't run
 * the app's import itself).
 */
final class ShareViewController: UIViewController {
    static let group = "group.com.bennybar.mypapers"

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
        Task { await receive() }
    }

    private func receive() async {
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        let providers = items.flatMap { $0.attachments ?? [] }
        guard let inbox = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.group)?.appending(path: "Inbox") else {
            return finish("My Papers can't receive files right now.")
        }
        try? FileManager.default.createDirectory(at: inbox, withIntermediateDirectories: true)
        var saved = 0
        for p in providers {
            for type in [UTType.pdf, UTType.image] where p.hasItemConformingToTypeIdentifier(type.identifier) {
                if let url = await copy(p, type: type, to: inbox) { saved += url.isFileURL ? 1 : 0 }
                break
            }
        }
        finish(saved == 0 ? "Nothing to add: My Papers takes PDFs and pictures." : saved == 1 ? "Added to My Papers. Open the app to see it." : "\(saved) added to My Papers. Open the app to see them.")
    }

    /** The shared file, copied under its own name (made unique) into [inbox]. */
    private func copy(_ provider: NSItemProvider, type: UTType, to inbox: URL) async -> URL? {
        await withCheckedContinuation { cont in
            provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                guard let url else { return cont.resume(returning: nil) }
                let name = url.lastPathComponent.isEmpty ? "Shared.\(type.preferredFilenameExtension ?? "pdf")" : url.lastPathComponent
                let dest = inbox.appending(path: "\(UUID().uuidString.prefix(8))-\(name)")
                cont.resume(returning: (try? FileManager.default.copyItem(at: url, to: dest)) != nil ? dest : nil)
            }
        }
    }

    @MainActor
    private func finish(_ message: String) {
        let alert = UIAlertController(title: "My Papers", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.extensionContext?.completeRequest(returningItems: nil)
        })
        present(alert, animated: true)
    }
}
