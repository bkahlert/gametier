// Include the private classifier to check UNC path rules without a live network share.
#include "../src/ExplorerCommand.cpp"

#include <iostream>

int main() {
    const bool local = samePath(volumeRoot(L"C:\\games\\game"), L"C:\\") &&
        samePath(backupFolder(L"C:\\games\\game"), L"C:\\.gametier\\backups\\game");
    const bool remote = samePath(volumeRoot(L"\\\\server\\share\\games\\game"), L"\\\\server\\share") &&
        samePath(backupFolder(L"\\\\server\\share\\games\\game"), L"\\\\server\\share\\.gametier\\backups\\game") &&
        !safePath(L"\\\\server\\share");
    const bool boundaries = within(L"C:\\games\\game\\child", L"c:\\GAMES\\game") &&
        !within(L"C:\\games\\game-other", L"C:\\games\\game");
    if (!local || !remote || !boundaries) {
        std::cerr << "FAIL Windows and UNC path identities\n";
        return 1;
    }
    std::cout << "PASS Windows and UNC path identities\n";
    return 0;
}
