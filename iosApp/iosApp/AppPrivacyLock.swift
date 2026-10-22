import LocalAuthentication
import SwiftUI

final class AppPrivacyLockCoordinator: ObservableObject {
    static let shared = AppPrivacyLockCoordinator()
    @Published private(set) var authenticating = false
    @Published private(set) var status = ""
    private var unlocked = false
    private var windows: [UIWindow] = []
    private var previousWindows: [UIWindow] = []
    private var generation = 0
    private var observers: [NSObjectProtocol] = []
    private var enabled: Bool { UserDefaults.standard.bool(forKey: "azoz_app_lock_enabled") }

    func start() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.cover() })
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.unlocked = false
            self?.generation += 1
            self?.status = ""
        })
        observers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.refresh() })
        observers.append(center.addObserver(forName: UserDefaults.didChangeNotification, object: nil, queue: .main) { [weak self] _ in self?.refresh() })
        refresh()
    }

    private func cover() {
        guard enabled else { return }
        // A separate window also covers native sheets presented above the Compose root.
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            guard !windows.contains(where: { $0.windowScene === scene }) else { continue }
            if let previous = scene.windows.first(where: \.isKeyWindow) { previousWindows.append(previous) }
            let window = UIWindow(windowScene: scene)
            window.windowLevel = .alert + 1
            window.rootViewController = UIHostingController(rootView: AppPrivacyLockScreen(coordinator: self))
            window.backgroundColor = .black
            window.makeKeyAndVisible()
            windows.append(window)
        }
    }

    private func uncover() {
        windows.forEach { $0.isHidden = true; $0.rootViewController = nil }
        windows.removeAll()
        previousWindows.forEach { $0.makeKey() }
        previousWindows.removeAll()
    }

    private func refresh() {
        if !enabled { unlocked = false; uncover(); return }
        if unlocked && UIApplication.shared.applicationState == .active { uncover(); return }
        cover()
        if UIApplication.shared.applicationState == .active && status.isEmpty { authenticate() }
    }

    func authenticate() {
        guard !authenticating, UIApplication.shared.applicationState == .active else { return }
        let context = LAContext()
        context.localizedCancelTitle = "إلغاء"
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            status = "فعّل رمز قفل الجهاز لاستخدام هذه الميزة"
            return
        }
        authenticating = true
        let capturedGeneration = generation
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "فتح Nuvio") { success, _ in
            DispatchQueue.main.async {
                self.authenticating = false
                if success && self.generation == capturedGeneration {
                    self.unlocked = true
                    self.status = ""
                    if UIApplication.shared.applicationState == .active { self.uncover() }
                } else { self.status = "لم يتم فتح القفل" }
            }
        }
    }
}

private struct AppPrivacyLockScreen: View {
    @ObservedObject var coordinator: AppPrivacyLockCoordinator
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 20) {
                Image(systemName: "lock.fill").font(.system(size: 40))
                Text("التطبيق مقفل").font(.title2)
                Button("فتح القفل") { coordinator.authenticate() }.disabled(coordinator.authenticating)
                if coordinator.authenticating { ProgressView() }
                Text(coordinator.status).font(.caption)
            }.padding().foregroundStyle(.white)
        }.environment(\.layoutDirection, .rightToLeft)
    }
}

struct AppPrivacyLockSettings: View {
    @AppStorage("azoz_app_lock_enabled") private var enabled = false
    @State private var status = ""
    @State private var checking = false
    var body: some View {
        Section("الخصوصية") {
            Toggle("قفل التطبيق بالوجه أو رمز الجهاز", isOn: Binding(get: { enabled }, set: { requested in
                guard requested else { enabled = false; return }
                let context = LAContext()
                var error: NSError?
                guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
                    status = "يتطلب تفعيل رمز قفل الجهاز"
                    return
                }
                checking = true
                context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: "تفعيل قفل التطبيق") { success, _ in
                    DispatchQueue.main.async {
                        checking = false
                        if success { enabled = true; status = "" }
                        else { status = "لم يتم تفعيل القفل" }
                    }
                }
            })).disabled(checking)
            if !status.isEmpty { Text(status) }
        }
    }
}
