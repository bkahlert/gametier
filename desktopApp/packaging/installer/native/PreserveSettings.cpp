#include "PreserveSettings.h"
#include <msi.h>
#include <msiquery.h>
#include <shlobj.h>

extern "C" UINT __stdcall PreserveSettings(MSIHANDLE installer) {
    PWSTR localAppData = nullptr;
    if (FAILED(SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &localAppData))) return ERROR_INSTALL_FAILURE;
    const std::filesystem::path root(localAppData);
    CoTaskMemFree(localAppData);
    const auto result = preserveSettings(root / L"GameTier/settings.preferences_pb", root / L"GameTierData/settings.preferences_pb");
    if (result == ERROR_SUCCESS) return ERROR_SUCCESS;
    const auto record = MsiCreateRecord(1);
    MsiRecordSetStringW(record, 0, L"GameTier could not preserve settings before upgrading. Windows error: [1]");
    MsiRecordSetInteger(record, 1, static_cast<int>(result));
    MsiProcessMessage(installer, INSTALLMESSAGE_ERROR, record);
    MsiCloseHandle(record);
    return ERROR_INSTALL_FAILURE;
}
