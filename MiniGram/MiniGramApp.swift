import SwiftUI

@main
struct MiniGramApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
        }
    }
}

struct ContentView: View {
    var body: some View {
        InstagramWebView()
            // Protect the top notch/Dynamic Island, extend bottom to screen edge
            .edgesIgnoringSafeArea(.bottom)
    }
}
