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
            // Top stays safely below the notch/Dynamic Island, bottom extends to edge
            .edgesIgnoringSafeArea(.bottom)
    }
}
