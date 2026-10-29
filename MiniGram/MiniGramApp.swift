import SwiftUI

@main
struct MiniGramApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
                .background(Color.black)
        }
    }
}

struct ContentView: View {
    var body: some View {
        InstagramWebView()
            // Fluid edge-to-edge for modern OLED screens (iPhone 14 Pro, 15, 15 Pro, 16 series)
            .ignoresSafeArea()
    }
}
