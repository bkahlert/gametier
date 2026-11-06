#include <windows.h>
#include <fstream>
#include <filesystem>
#include <string>

int wmain(int argc, wchar_t** argv) {
    wchar_t capture[32768]{};
    wchar_t eventName[256]{};
    if (argc != 3 || GetEnvironmentVariableW(L"GAMETIER_TEST_CAPTURE", capture, 32768) == 0 ||
        GetEnvironmentVariableW(L"GAMETIER_TEST_EVENT", eventName, 256) == 0) return 1;
    const std::wstring arguments = std::wstring(argv[1]) + L"\n" + argv[2];
    std::ofstream output(std::filesystem::path(capture), std::ios::binary);
    output.write(reinterpret_cast<const char*>(arguments.data()),
        static_cast<std::streamsize>(arguments.size() * sizeof(wchar_t)));
    output.close();
    const auto event = OpenEventW(EVENT_MODIFY_STATE, FALSE, eventName);
    if (event == nullptr) return 2;
    SetEvent(event);
    CloseHandle(event);
    return 0;
}
