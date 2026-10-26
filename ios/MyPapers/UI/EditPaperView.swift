import SwiftUI

/** Correct what the AI read, or fill a paper in by hand. Corrected fields count as the user's: a re-read leaves them. */
struct EditPaperView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let paperID: String

    @State private var loaded = false
    @State private var title = ""
    @State private var vendor = ""
    @State private var docType = ""
    @State private var hasDate = false
    @State private var date = Date()
    @State private var total = ""
    @State private var currency = ""
    @State private var vat = ""
    @State private var tags: [String] = []
    @State private var tagInput = ""
    @State private var details: [KeyDetail] = []
    @State private var notes = ""
    @State private var start: Snapshot?

    /** The form as it opened, to tell what was changed here. */
    private struct Snapshot: Equatable {
        var title, vendor, docType: String, date: String?, total, currency, vat: String, tags: [String], details: [KeyDetail], notes: String
    }

    private var now: Snapshot {
        Snapshot(title: title, vendor: vendor, docType: docType, date: hasDate ? Day(date).iso : nil, total: total, currency: currency, vat: vat, tags: tags, details: details, notes: notes)
    }

    var body: some View {
        Form {
            Section {
                TextField("Title", text: $title)
                TextField("Vendor", text: $vendor)
                Picker("Type", selection: $docType) {
                    Text("—").tag("")
                    ForEach(documentTypes, id: \.self) { Text($0).tag($0) }
                }
                Toggle("Date", isOn: $hasDate)
                if hasDate { DatePicker("Date", selection: $date, displayedComponents: .date) }
            }
            Section("Amount") {
                TextField("Total", text: $total).keyboardType(.decimalPad)
                TextField("Currency", text: $currency).textInputAutocapitalization(.characters)
                TextField("VAT", text: $vat).keyboardType(.decimalPad)
                if !total.isEmpty && Format.parseAmount(total) == nil { Text("Not an amount").foregroundStyle(.red).font(.footnote) }
            }
            Section("Tags") {
                if !tags.isEmpty {
                    FlowLayout(spacing: 6) {
                        ForEach(tags, id: \.self) { t in
                            Button { tags.removeAll { $0 == t } } label: { Label(t, systemImage: "xmark") }.buttonStyle(.glass)
                        }
                    }
                }
                TextField("Add a tag", text: $tagInput).onSubmit(addTag)
            }
            Section {
                ForEach(details.indices, id: \.self) { i in
                    VStack(alignment: .leading) {
                        TextField("Label", text: $details[i].label).font(.caption)
                        TextField("Value", text: $details[i].value, axis: .vertical)
                    }
                }
                .onDelete { details.remove(atOffsets: $0) }
                Button { details.append(KeyDetail(label: "", value: "")) } label: { Label("Add detail", systemImage: "plus") }
            } header: { Text("Key details") } footer: {
                Text("A date labeled “Valid until”, “Renewal date” or “Due date” gets a reminder before it.")
            }
            Section("Notes") { TextField("Notes", text: $notes, axis: .vertical).lineLimit(3...) }
        }
        .navigationTitle("Edit")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") { save(); dismiss() }
                    .disabled(!total.isEmpty && Format.parseAmount(total) == nil || !vat.isEmpty && Format.parseAmount(vat) == nil)
            }
        }
        .onAppear(perform: load)
    }

    private func addTag() {
        let t = tagInput.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: ","))
        if !t.isEmpty, !tags.contains(where: { $0.caseInsensitiveCompare(t) == .orderedSame }) { tags.append(t) }
        tagInput = ""
    }

    private func load() {
        guard !loaded, let p = app.store.live(paperID) else { return }
        loaded = true
        title = p.title; vendor = p.vendor; docType = p.docType
        if let d = Day(iso: p.date) { hasDate = true; date = d.date }
        total = Format.amountInput(p.totalCents)
        currency = p.currency ?? Format.defaultCurrency()
        vat = Format.amountInput(p.vatCents)
        tags = p.tags; details = p.details; notes = p.notes
        start = now
    }

    /** Only what was changed here: the AI may have filled the rest meanwhile. */
    private func save() {
        if !tagInput.isEmpty { addTag() }
        guard let start, let p = app.store.live(paperID) else { return }
        let n = now
        var edited: [String] = []
        if n.title != start.title { p.title = title.trimmingCharacters(in: .whitespaces); edited.append("title") }
        if n.vendor != start.vendor { p.vendor = vendor.trimmingCharacters(in: .whitespaces); edited.append("vendor") }
        if n.docType != start.docType { p.docType = docType; edited.append("docType") }
        if n.date != start.date { p.date = n.date; edited.append("date") }
        if n.total != start.total { p.totalCents = Format.parseAmount(total); edited.append("total") }
        if n.currency != start.currency { p.currency = currency.trimmingCharacters(in: .whitespaces).isEmpty ? nil : currency.trimmingCharacters(in: .whitespaces).uppercased(); edited.append("currency") }
        if n.vat != start.vat { p.vatCents = Format.parseAmount(vat); edited.append("vat") }
        if n.tags != start.tags { p.tags = tags; edited.append("tags") }
        if n.details != start.details {
            p.details = details.map { KeyDetail(label: $0.label.trimmingCharacters(in: .whitespaces), value: $0.value.trimmingCharacters(in: .whitespaces)) }
                .filter { !$0.label.isEmpty && !$0.value.isEmpty }
            edited.append("details")
        }
        if n.notes != start.notes { p.notes = notes.trimmingCharacters(in: .whitespacesAndNewlines) }
        p.edited = Array(Set(p.edited + edited)).sorted()
        if n != start { app.store.update(p) }
    }
}

/** Dates, amounts, vendors and "show only": everything beyond the type and tag chips (the sort has its own menu). */
struct FilterSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let vendors: [String]
    @State private var min = ""
    @State private var max = ""

    var body: some View {
        @Bindable var lib = app.library
        NavigationStack {
            Form {
                Section("Date") {
                    Picker("Date", selection: $lib.filters.date) {
                        Text("Any time").tag(DateWindow?.none)
                        Text("This month").tag(DateWindow?.some(.thisMonth))
                        Text("Last month").tag(DateWindow?.some(.lastMonth))
                        Text("This year").tag(DateWindow?.some(.thisYear))
                        Text("Last year").tag(DateWindow?.some(.lastYear))
                        Text("Pick dates…").tag(DateWindow?.some(.custom))
                    }
                    if lib.filters.date == .custom {
                        DatePicker("From", selection: Binding(get: { lib.filters.from?.date ?? Date() }, set: { lib.filters.from = Day($0) }), displayedComponents: .date)
                        DatePicker("To", selection: Binding(get: { lib.filters.to?.date ?? Date() }, set: { lib.filters.to = Day($0) }), displayedComponents: .date)
                    }
                }
                Section("Amount") {
                    TextField("At least", text: $min).keyboardType(.decimalPad).onChange(of: min) { _, v in lib.filters.minCents = Format.parseAmount(v) }
                    TextField("At most", text: $max).keyboardType(.decimalPad).onChange(of: max) { _, v in lib.filters.maxCents = Format.parseAmount(v) }
                }
                if !vendors.isEmpty {
                    Section("Vendor") {
                        ForEach(vendors.prefix(30), id: \.self) { v in
                            Toggle(v, isOn: Binding(get: { lib.filters.vendors.contains(v) }, set: { on in if on { lib.filters.vendors.insert(v) } else { lib.filters.vendors.remove(v) } }))
                        }
                    }
                }
                Section("Show only") {
                    Toggle("Missing amount, date or vendor", isOn: $lib.filters.missingInfo)
                    Toggle("Possible duplicates", isOn: $lib.filters.duplicates)
                    Toggle("Not read by AI", isOn: $lib.filters.unread)
                    Toggle("Expiring soon", isOn: $lib.filters.expiring)
                }
            }
            .navigationTitle("Filter")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // The order isn't a filter: it stays.
                    Button("Clear all") { min = ""; max = ""; lib.filters = Filters(sort: lib.filters.sort) }
                }
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .onAppear {
                min = Format.amountInput(lib.filters.minCents)
                max = Format.amountInput(lib.filters.maxCents)
            }
        }
    }
}
