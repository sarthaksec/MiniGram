import SwiftUI

@main
struct MiniGramApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark) // Instagram dark mode default
        }
    }
}

struct ContentView: View {
    var body: some View {
        InstagramWebView()
            .ignoresSafeArea()
    }
}
