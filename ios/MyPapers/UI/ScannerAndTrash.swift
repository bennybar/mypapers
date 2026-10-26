import PDFKit
import SwiftUI
import VisionKit

/** The system document scanner (edge detection, cropping, several pages) → one PDF file. */
struct DocumentScanner: UIViewControllerRepresentable {
    /** The scan as a PDF in a temp folder; nil when cancelled or it failed. */
    let onDone: (URL?) -> Void

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let vc = VNDocumentCameraViewController()
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: VNDocumentCameraViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let onDone: (URL?) -> Void
        init(onDone: @escaping (URL?) -> Void) { self.onDone = onDone }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
            let pdf = PDFDocument()
            for i in 0..<scan.pageCount {
                // JPEG inside, so a page isn't megabytes of raw pixels.
                let image = scan.imageOfPage(at: i)
                guard let data = image.jpegData(compressionQuality: 0.85), let jpeg = UIImage(data: data), let page = PDFPage(image: jpeg) else { continue }
                pdf.insert(page, at: pdf.pageCount)
            }
            let url = FileManager.default.temporaryDirectory.appending(path: "scan-\(UUID().uuidString).pdf")
            onDone(pdf.write(to: url) ? url : nil)
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) { onDone(nil) }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) { onDone(nil) }
    }
}

/** Deleted papers, restorable for 30 days, then gone for good. */
struct TrashView: View {
    @Environment(AppModel.self) private var app
    @State private var confirmEmpty = false

    var body: some View {
        let _ = app.store.version
        let papers = app.store.trash()
        List {
            if papers.isEmpty {
                ContentUnavailableView("The trash is empty", systemImage: "trash")
            } else {
                Section {
                    ForEach(papers) { p in
                        VStack(alignment: .leading) {
                            Text(p.displayTitle)
                            Text(p.subtitle).font(.caption).foregroundStyle(.secondary)
                        }
                        .swipeActions(edge: .leading) { Button("Restore") { app.store.restore(p.id) }.tint(.accentColor) }
                        .swipeActions(edge: .trailing) { Button("Delete forever", role: .destructive) { app.store.delete(p.id) } }
                        .contextMenu {
                            Button("Restore") { app.store.restore(p.id) }
                            Button("Delete forever", role: .destructive) { app.store.delete(p.id) }
                        }
                    }
                } footer: {
                    Text(papers.count == 1 ? "1 paper is deleted for good 30 days after it was moved here." : "Papers are deleted for good 30 days after they were moved here.")
                }
            }
        }
        .navigationTitle("Trash")
        .toolbar {
            if !papers.isEmpty { ToolbarItem(placement: .topBarTrailing) { Button("Empty", role: .destructive) { confirmEmpty = true } } }
        }
        .confirmationDialog("Delete all \(papers.count) for good?", isPresented: $confirmEmpty, titleVisibility: .visible) {
            Button("Delete forever", role: .destructive) { app.store.emptyTrash() }
        } message: { Text("They're removed from this phone and your Google Drive.") }
    }
}
