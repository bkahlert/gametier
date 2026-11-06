#include "PreserveSettings.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>

namespace fs = std::filesystem;

int main() {
    const auto root = fs::temp_directory_path() / (L"gametier-settings-" + std::to_wstring(GetCurrentProcessId()));
    fs::create_directories(root);
    try {
        const auto source = root / L"GameTier/settings.preferences_pb";
        const auto target = root / L"GameTierData/settings.preferences_pb";
        const auto staged = target.parent_path() / L"legacy.preferences_pb";
        auto expect = [](bool value) { if (!value) throw std::runtime_error("Settings preservation failed"); };
        expect(preserveSettings(source, target) == ERROR_SUCCESS);
        expect(!fs::exists(target.parent_path()));
        fs::create_directories(source.parent_path());
        std::ofstream(source, std::ios::binary) << "default and history";
        expect(preserveSettings(source, target) == ERROR_SUCCESS);
        const auto read = [](const fs::path& file) {
            std::ifstream input(file, std::ios::binary);
            return std::string(std::istreambuf_iterator<char>(input), {});
        };
        expect(read(staged) == "default and history");
        expect(!fs::exists(target));
        expect(fs::exists(source));
        std::ofstream(source, std::ios::binary | std::ios::trunc) << "changed after cancelled upgrade";
        expect(preserveSettings(source, target) == ERROR_SUCCESS);
        expect(read(staged) == "changed after cancelled upgrade");
        std::ofstream(target, std::ios::binary) << "current settings";
        expect(preserveSettings(source, target) == ERROR_SUCCESS);
        expect(read(target) == "current settings");
        const auto blocked = root / L"blocked";
        std::ofstream(blocked) << "retained";
        expect(preserveSettings(source, blocked / L"settings.preferences_pb") != ERROR_SUCCESS);
        expect(preserveSettings(source, target.parent_path()) == ERROR_DIRECTORY);
        const auto lockedTarget = root / L"locked/settings.preferences_pb";
        const auto handle = CreateFileW(source.c_str(), GENERIC_READ, 0, nullptr, OPEN_EXISTING, 0, nullptr);
        expect(handle != INVALID_HANDLE_VALUE);
        const auto result = preserveSettings(source, lockedTarget);
        CloseHandle(handle);
        expect(result == ERROR_SHARING_VIOLATION);
        expect(!fs::exists(lockedTarget));
        fs::remove_all(root);
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        fs::remove_all(root);
        return 1;
    }
}
