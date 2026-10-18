import UIKit

/// Per-store values, read from tatbiqi.json in the app bundle (written by build_ios.py).
/// Nothing a store owner types is ever compiled as code.
struct StoreConfig {
    var storeURL = URL(string: "https://salla.sa")!
    var primaryColor = UIColor(red: 0.06, green: 0.46, blue: 0.43, alpha: 1)
    var whatsappNumber = ""
    var whatsappMessage = ""

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
        cfg.whatsappNumber = (json["whatsappNumber"] as? String) ?? ""
        cfg.whatsappMessage = (json["whatsappMessage"] as? String) ?? ""
        return cfg
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

    var isLight: Bool {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return 0.299 * r + 0.587 * g + 0.114 * b > 0.6
    }
}
