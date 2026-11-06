#pragma once
#include <windows.h>
#include <filesystem>

inline DWORD preserveSettings(const std::filesystem::path& source, const std::filesystem::path& target) {
    const auto attributes = GetFileAttributesW(source.c_str());
    if (attributes == INVALID_FILE_ATTRIBUTES) {
        const auto error = GetLastError();
        return error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND ? ERROR_SUCCESS : error;
    }
    const auto targetAttributes = GetFileAttributesW(target.c_str());
    if (targetAttributes != INVALID_FILE_ATTRIBUTES) {
        return (targetAttributes & FILE_ATTRIBUTE_DIRECTORY) ? ERROR_DIRECTORY : ERROR_SUCCESS;
    }
    std::error_code error;
    std::filesystem::create_directories(target.parent_path(), error);
    if (error) return static_cast<DWORD>(error.value());
    wchar_t temporary[MAX_PATH];
    if (!GetTempFileNameW(target.parent_path().c_str(), L"gts", 0, temporary)) return GetLastError();
    const auto staged = target.parent_path() / L"legacy.preferences_pb";
    DWORD result = ERROR_SUCCESS;
    if (!CopyFileW(source.c_str(), temporary, FALSE) ||
        !MoveFileExW(temporary, staged.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        result = GetLastError();
    }
    DeleteFileW(temporary);
    return result;
}
