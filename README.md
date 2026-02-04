# LocalGitMirror 🚀

**Stealth Git Bridge for Seamless Work-Home Synchronization**

LocalGitMirror is a self-hosted tool that bridges the gap between your restricted work environment and your personal development setup. It mimics a standard Git remote, allowing you to push code from work, edit it at home with your favorite tools (and AI), and pull it back—all without cloud services.

![Dashboard Preview](frontend/public/favicon.ico)

## 🔥 Features

*   **Stealth Sync**: Acts as a standard Git server. Your work PC just sees a remote repo.
*   **Auto-Update**: Pushing to the server automatically updates physical files in your workspace (`updateInstead` logic).
*   **VS Code-like UI**: A clean, dark-mode web interface to manage repositories and files.
*   **One-Click Commit**: "Prepare for Work" button automatically stages and commits your home changes for retrieval.
*   **Zero Dependencies**: Runs locally on your machine. No cloud, no tracking.

## 🚀 Quick Start

### 1. Start the Server (Home PC)
Simply double-click **`start.bat`** (Windows).
*   **Web UI**: [http://localhost:8000](http://localhost:8000)
*   **Git Server**: Port `8081`

### 2. Configure Work PC
In your project folder on your work computer:

```bash
# Add your home PC as a remote (replace IP with your home IP)
git remote add home git://192.168.1.X:8081/my-project
```

### 3. Workflow

**From Work to Home:**
```bash
git push home main
```
*Files immediately appear in `storage/my-project` on your home PC.*

**At Home:**
1.  Open the folder in Cursor / VS Code.
2.  Edit files, use AI tools, compile, etc.
3.  Go to the Web UI -> Click **"Prepare for Work"**.

**From Home to Work:**
```bash
git pull home main
```

## 🛠️ Configuration

*   **Storage Path**: By default, projects are stored in the `storage/` folder. You can change this in the `.env` file or UI settings.
*   **Ports**: Default Web: 8000, Git: 8081.

## 🏗️ Architecture

*   **Backend**: Python (FastAPI) + GitPython
*   **Frontend**: Vue 3 + Vite + TailwindCSS
*   **Protocol**: Native Git Protocol (Daemon)

---
*Built for the modern developer who values freedom and efficiency.*
