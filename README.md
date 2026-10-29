# MiniGram 📸✨

A distraction-free, mindful Instagram wrapper for iOS built with **SwiftUI** and **WebKit**.

## 🎯 Features (Option B)
- ✅ **Stories Carousel**: Full access to your friends' active Stories at the top.
- ✅ **Direct Messages (DMs)**: Message friends without distraction.
- ✅ **Profile & Notifications**: Full access to your account and notifications.
- 🚫 **Home Feed Posts**: Blocked and hidden completely. No infinite scrolling.
- 🚫 **Reels Section**: Blocked both in the navigation bar and via internal client-side navigation.
- 🚫 **Explore / Discover Tab**: Blocked to prevent rabbit holes.
- 🔄 **Pull-to-Refresh**: Native pull down to refresh Stories and DMs.
- 🔒 **Zero Credential Risk**: Uses native iOS WebKit cookie store; your login credentials never touch app code.

---

## 🚀 How to Build & Install on iPhone 15 from Windows

Because this project uses GitHub Actions with a macOS runner, **you do not need a Mac**.

### Step 1: Push this code to a new GitHub repository
Run the following in PowerShell inside `D:\programs\breakit`:
```powershell
git branch -M main
git add .
git commit -m "Initial commit for MiniGram"
```
Create an empty private (or public) repository on GitHub named `MiniGram`, then connect and push:
```powershell
git remote add origin https://github.com/<YOUR_USERNAME>/MiniGram.git
git push -u origin main
```

### Step 2: Download the built `.ipa` from GitHub Actions
1. Go to your GitHub repository in your browser.
2. Click the **Actions** tab at the top.
3. You will see the **Build MiniGram iOS App** workflow running on macOS.
4. When it turns green (approx. 2-3 minutes), click on the run.
5. Under **Artifacts** at the bottom, download **`MiniGram-iOS.zip`**.
6. Unzip it to get **`MiniGram.ipa`**.

---

### Step 3: Install onto iPhone 15 using Sideloadly (Windows)
1. Download and install **[Sideloadly](https://sideloadly.io/)** (free, trusted Windows iOS sideloading tool).
2. Connect your **iPhone 15** to your Windows PC using your USB-C cable (tap "Trust this Computer" on your iPhone if prompted).
3. Open **Sideloadly**:
   - Drag and drop `MiniGram.ipa` into the large icon box on the left.
   - Enter your **Apple ID email** in the Apple ID box.
   - Click **Start** (it will prompt for your Apple ID password to sign the app with your free personal developer certificate).
4. Sideloadly will sign and install **MiniGram** directly onto your iPhone 15!

---

### Step 4: First-time iOS Trust & Developer Mode
On your iPhone 15:
1. Go to **Settings > General > VPN & Device Management**.
2. Tap your Apple ID under "Developer App" and tap **Trust**.
3. If on iOS 17: Go to **Settings > Privacy & Security > Developer Mode** and turn it **ON** (your phone will prompt a quick restart).
4. Launch **MiniGram** from your Home Screen! Log into Instagram once, and you are set.
