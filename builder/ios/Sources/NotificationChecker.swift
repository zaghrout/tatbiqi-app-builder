import BackgroundTasks
import UIKit
import UserNotifications

/// Store notifications without Firebase, like NotificationJob.java on Android.
///
/// Reads the store's sent notifications from <configUrl>/notifications each time the
/// app opens and, when iOS allows it (it decides how often, usually a few times a day),
/// in the background. The ones this phone hasn't shown yet become local notifications.
/// Tapping one opens its link inside the app.
///
/// On the first check after install only the newest notification is shown, so a new
/// customer isn't greeted by a pile of old ones.
enum NotificationChecker {
    /// Key in a notification's userInfo for the link it opens.
    static let urlKey = "tatbiqi.openUrl"
    /// Posted with the URL as object when a notification is tapped.
    static let openURL = Notification.Name("tatbiqi.openUrl")
    /// A tapped notification's link, kept until the store screen is ready to open it.
    static var pendingURL: URL?

    private static let seenKey = "notifSeen"
    private static let initKey = "notifInit"
    private static let maxSeen = 50
    private static let maxPerRun = 3

    static var taskID: String { (Bundle.main.bundleIdentifier ?? "app") + ".notifications" }

    /// Must run before the app finishes launching.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskID, using: nil) { task in
            schedule()
            let request = check { task.setTaskCompleted(success: $0) }
            task.expirationHandler = { request?.cancel() }
        }
    }

    /// Asks iOS for the next background check; it runs it when it sees fit.
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskID)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// iOS shows the question only once; later calls return the customer's answer.
    static func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            if granted { check() }
        }
    }

    @discardableResult
    static func check(completion: ((Bool) -> Void)? = nil) -> URLSessionDataTask? {
        guard let base = StoreConfig.load().configURL else {
            completion?(false)
            return nil
        }
        var request = URLRequest(url: base.appendingPathComponent("notifications"),
                                 cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let task = URLSession.shared.dataTask(with: request) { data, response, _ in
            guard let data = data, (response as? HTTPURLResponse)?.statusCode == 200,
                  let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let list = json["notifications"] as? [[String: Any]]
            else {
                completion?(false)
                return
            }
            UNUserNotificationCenter.current().getNotificationSettings { settings in
                // Nothing would show; keep them for when the customer allows notifications.
                if settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional {
                    show(list)
                }
                completion?(true)
            }
        }
        task.resume()
        return task
    }

    private static func show(_ list: [[String: Any]]) {
        let defaults = UserDefaults.standard
        let firstRun = !defaults.bool(forKey: initKey)
        var seen = defaults.stringArray(forKey: seenKey) ?? []

        // The list is newest first.
        let fresh = list.filter { n in
            guard let id = n["id"] as? String,
                  id.range(of: "^[A-Za-z0-9_-]{1,64}$", options: .regularExpression) != nil
            else { return false }
            return !seen.contains(id)
        }
        // Show the newest few, oldest of them first so they stack in order.
        let shown = Array(fresh.prefix(firstRun ? 1 : maxPerRun))
        for n in shown.reversed() {
            let title = (n["title"] as? String) ?? ""
            let body = (n["body"] as? String) ?? ""
            if title.isEmpty && body.isEmpty { continue }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            if let link = n["link"] as? String, link.hasPrefix("https://") {
                content.userInfo = [urlKey: link]
            }
            let id = n["id"] as? String ?? UUID().uuidString
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
        }
        seen.append(contentsOf: fresh.compactMap { $0["id"] as? String })
        if seen.count > maxSeen { seen.removeFirst(seen.count - maxSeen) }
        defaults.set(seen, forKey: seenKey)
        defaults.set(true, forKey: initKey)
    }
}
