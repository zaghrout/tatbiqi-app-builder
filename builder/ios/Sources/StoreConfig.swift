import UIKit

/// Per-store values, read from tatbiqi.json in the app bundle (written by build_ios.py).
/// Nothing a store owner types is ever compiled as code.
struct StoreConfig {
    var storeURL = URL(string: "https://salla.sa")!
    var primaryColor = UIColor(red: 0.06, green: 0.46, blue: 0.43, alpha: 1)
    var secondaryColor: UIColor?
    var accentColor: UIColor?
    /// Image file in the app bundle shown while the store loads ("" for none).
    var splash = ""
    var whatsappNumber = ""
    var whatsappMessage = ""
    /// The dashboard's /api/app-config/<appId>: add-ons and notifications, read live.
    var configURL: URL?

    var storeHost: String { StoreConfig.stripWWW(storeURL.host?.lowercased() ?? "") }

    static func load() -> StoreConfig {
        var cfg = StoreConfig()
        guard
            let url = Bundle.main.url(forResource: "tatbiqi", withExtension: "json"),
            let data = try? Data(contentsOf: url),
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return cfg }

        if let s = json["storeUrl"] as? String, let u = URL(string: s), u.scheme == "https" {
            cfg.storeURL = u
        }
        if let hex = json["primaryColor"] as? String, let c = UIColor(hex: hex) {
            cfg.primaryColor = c
        }
        cfg.secondaryColor = (json["secondaryColor"] as? String).flatMap { UIColor(hex: $0) }
        cfg.accentColor = (json["accentColor"] as? String).flatMap { UIColor(hex: $0) }
        if let s = json["splash"] as? String, s.range(of: "^[A-Za-z0-9_.-]+$", options: .regularExpression) != nil {
            cfg.splash = s
        }
        cfg.whatsappNumber = (json["whatsappNumber"] as? String) ?? ""
        cfg.whatsappMessage = (json["whatsappMessage"] as? String) ?? ""
        if let s = json["configUrl"] as? String, let u = URL(string: s), u.scheme == "https" {
            cfg.configURL = u
        }
        return cfg
    }

    /// The dashboard's add-ons: {"whatsapp": {"number", "message"} | null, "offer": {...} | null,
    /// "design": {...}}. A missing key leaves the value as it was; null turns the add-on off.
    mutating func apply(remote json: [String: Any]) {
        if let wa = json["whatsapp"] {
            let obj = wa as? [String: Any]
            let number = ((obj?["number"] as? String) ?? "").filter { $0.isASCII && $0.isNumber }
            whatsappNumber = (8...15).contains(number.count) ? number : ""
            whatsappMessage = (obj?["message"] as? String) ?? ""
        }
        if let design = json["design"] as? [String: Any] {
            // null keeps the primary color, as on Android.
            secondaryColor = (design["secondaryColor"] as? String).flatMap { UIColor(hex: $0) }
            accentColor = (design["accentColor"] as? String).flatMap { UIColor(hex: $0) }
        }
    }

    /// Same site as the store, ignoring "www." and subdomains.
    func isStoreHost(_ host: String?) -> Bool {
        guard let host = host, !storeHost.isEmpty else { return false }
        let a = StoreConfig.stripWWW(host.lowercased())
        let b = storeHost
        return a == b || a.hasSuffix("." + b) || b.hasSuffix("." + a)
    }

    static func stripWWW(_ h: String) -> String {
        h.hasPrefix("www.") ? String(h.dropFirst(4)) : h
    }
}

extension UIColor {
    convenience init?(hex: String) {
        let s = hex.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "#", with: "")
        guard s.count == 6, let v = UInt32(s, radix: 16) else { return nil }
        self.init(
            red: CGFloat((v >> 16) & 0xFF) / 255,
            green: CGFloat((v >> 8) & 0xFF) / 255,
            blue: CGFloat(v & 0xFF) / 255,
            alpha: 1
        )
    }

    var hexString: String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(format: "#%02X%02X%02X", Int(round(r * 255)), Int(round(g * 255)), Int(round(b * 255)))
    }

    var isLight: Bool {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return 0.299 * r + 0.587 * g + 0.114 * b > 0.6
    }
}
