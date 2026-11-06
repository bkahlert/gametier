#include <windows.h>
#include <shlobj.h>
#include <aclapi.h>
#include <sddl.h>
#include <winioctl.h>

#include <filesystem>
#include <cstddef>
#include <cstring>
#include <fstream>
#include <functional>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>

namespace fs = std::filesystem;

namespace {
template<class T> class ComPtr {
    T* value_ = nullptr;
public:
    ComPtr() = default;
    ~ComPtr() { if (value_ != nullptr) value_->Release(); }
    ComPtr(const ComPtr&) = delete;
    ComPtr& operator=(const ComPtr&) = delete;
    ComPtr(ComPtr&& other) noexcept : value_(other.value_) { other.value_ = nullptr; }
    T* Get() const { return value_; }
    T* operator->() const { return value_; }
    T** operator&() { return &value_; }
};
constexpr GUID commandId{0x9b6ed281, 0x384a, 0x4dbf, {0x98, 0xc0, 0xd2, 0x6b, 0x6d, 0x41, 0xb3, 0x52}};
using GetClassObject = HRESULT(STDAPICALLTYPE*)(REFCLSID, REFIID, void**);
using CanUnload = HRESULT(STDAPICALLTYPE*)();
GetClassObject getClassObject = nullptr;
CanUnload canUnload = nullptr;
fs::path application;

void require(bool condition, const char* message);
void checked(HRESULT result);
ComPtr<IExplorerCommand> command();
ComPtr<IShellItemArray> selection(const std::vector<fs::path>& paths);

struct Fixture {
    fs::path root;
    Fixture();
    ~Fixture();
    fs::path directory(const fs::path& name) const;
    fs::path file(const fs::path& name, const std::string& content = "game") const;
};

EXPCMDSTATE state(IExplorerCommand* subject, IShellItemArray* items);
void setEnabled(DWORD enabled);
void junction(const fs::path& link, const fs::path& target);

#include "ManagedSelections.h"

void enables_one_file() {
    Fixture fixture;
    const auto path = fixture.file(L"game.bin");
    const auto items = selection({path});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_ENABLED, "one regular file must be enabled");
}

void enables_one_directory_without_scanning_descendants() {
    Fixture fixture;
    const auto path = fixture.directory(L"game");
    fixture.file(L"game/child/.gametier.json", "not valid json");
    const auto items = selection({path});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_ENABLED, "descendant markers must not hide a parent selection");
}

void hides_empty_selection() {
    const auto subject = command();
    const auto result = state(subject.Get(), nullptr);
    require(result == ECS_HIDDEN, "no selection must be hidden");
}

void hides_multiple_selections() {
    Fixture fixture;
    const auto items = selection({fixture.file(L"one"), fixture.file(L"two")});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_HIDDEN, "multiple selections must be hidden");
}

void defers_filesystem_and_preference_reads() {
    Fixture fixture;
    const auto path = fixture.file(L"removed");
    const auto items = selection({path});
    fs::remove(path);
    setEnabled(0);
    const auto subject = command();
    EXPCMDSTATE output = ECS_ENABLED;
    const auto result = subject->GetState(items.Get(), FALSE, &output);
    setEnabled(1);
    require(result == E_PENDING, "the UI-thread query must defer all inspection");
    require(output == ECS_HIDDEN, "a deferred query must initialize its output");
}

void uses_cached_title_and_tooltip_without_reinspection() {
    Fixture fixture;
    const auto path = fixture.file(L"game");
    const auto items = selection({path});
    const auto subject = command();
    require(state(subject.Get(), items.Get()) == ECS_ENABLED, "fixture must resolve promotion");
    fs::remove(path);
    setEnabled(0);
    PWSTR title = nullptr;
    PWSTR tooltip = nullptr;
    const auto titleResult = subject->GetTitle(items.Get(), &title);
    const auto tooltipResult = subject->GetToolTip(items.Get(), &tooltip);
    setEnabled(1);
    const std::wstring titleText(title == nullptr ? L"" : title);
    const std::wstring tooltipText(tooltip == nullptr ? L"" : tooltip);
    CoTaskMemFree(title);
    CoTaskMemFree(tooltip);
    require(titleResult == S_OK && titleText == L"Promote with GameTier" && tooltipResult == S_OK &&
        tooltipText == L"Review promotion to your game drive", "UI metadata must use the cached decision without reinspection");
    require(subject->Invoke(items.Get(), nullptr) == HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED), "invoke must still revalidate removed selection");
}

void hides_filesystem_root() {
    Fixture fixture;
    const auto items = selection({fixture.root.root_path()});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_HIDDEN, "a volume root must be hidden");
}

void hides_missing_metadata() {
    Fixture fixture;
    const auto path = fixture.file(L"removed");
    const auto items = selection({path});
    fs::remove(path);
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_HIDDEN, "missing selected metadata must fail closed");
}

void hides_selected_directory_marker() {
    Fixture fixture;
    const auto path = fixture.directory(L"game");
    fixture.file(L"game/.gametier.json", "");
    const auto items = selection({path});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_HIDDEN, "an empty marker in the selection must hide it");
}

void hides_parent_marker_regardless_of_contents() {
    Fixture fixture;
    const auto path = fixture.file(L"managed/game.bin");
    fixture.file(L"managed/.gametier.json", "invalid json");
    const auto items = selection({path});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_HIDDEN, "a malformed parent marker must still hide selection");
}

void hides_distant_ancestor_marker() {
    Fixture fixture;
    const auto path = fixture.directory(L"managed/a/b/game");
    fixture.directory(L"managed/.gametier.json");
    const auto items = selection({path});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_HIDDEN, "any marker entry in a distant ancestor must hide selection");
}

void hides_reparse_directory() {
    Fixture fixture;
    const auto target = fixture.directory(L"target");
    const auto path = fixture.root / L"junction";
    junction(path, target);
    const auto items = selection({path});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_HIDDEN, "a junction must be hidden without following it");
}

void hides_backup_storage_and_descendants() {
    Fixture fixture;
    const auto storage = fixture.directory(L".gametier");
    const auto folder = fixture.directory(L".gametier/backups/game");
    const auto file = fixture.file(L".gametier/backups/game/data.rom", "snapshot");
    const auto subject = command();
    for (const auto& path : {storage, folder, file}) {
        const auto items = selection({path});
        const auto result = state(subject.Get(), items.Get());
        require(result == ECS_HIDDEN, "backup storage and all descendants must be hidden without index metadata");
        require(FAILED(subject->Invoke(items.Get(), nullptr)), "backup contents must not launch promotion");
    }
}

void hides_backup_storage_case_variants() {
    Fixture fixture;
    const auto items = selection({fixture.file(L".GAMETIER/backups/game/data.rom")});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_HIDDEN, "backup storage exclusion must be case insensitive");
}

void hides_backup_alias_ancestors() {
    Fixture fixture;
    fixture.file(L".gametier/backup");
    fixture.file(L"ordinary/game");
    junction(fixture.root / L"outside-alias", fixture.root / L".gametier");
    junction(fixture.root / L".gametier/inside-alias", fixture.root / L"ordinary");
    const auto subject = command();
    for (const auto& path : {fixture.root / L"outside-alias/backup", fixture.root / L".gametier/inside-alias/game"}) {
        const auto items = selection({path});
        const auto result = state(subject.Get(), items.Get());
        require(result == ECS_HIDDEN, "neither lexical nor canonical backup ancestry may be bypassed");
    }
}

void enables_similar_names_outside_backup_directories() {
    Fixture fixture;
    const auto subject = command();
    for (const auto& path : {fixture.file(L".gametier-other/game"), fixture.file(L".gametier")}) {
        const auto items = selection({path});
        const auto result = state(subject.Get(), items.Get());
        require(result == ECS_ENABLED, "similar names and ordinary files are not backup directories");
    }
}

void enables_regular_file_below_directory_alias() {
    Fixture fixture;
    fixture.file(L"target/game.bin");
    const auto link = fixture.root / L"junction";
    junction(link, fixture.root / L"target");
    const auto items = selection({link / L"game.bin"});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_ENABLED, "a regular file below a parent alias must remain eligible");
}

void hides_marker_on_lexical_ancestor_of_alias() {
    Fixture fixture;
    fixture.file(L"target/game.bin");
    const auto link = fixture.root / L"lexical/junction";
    fixture.directory(L"lexical");
    junction(link, fixture.root / L"target");
    fixture.file(L"lexical/.gametier.json", "broken");
    const auto items = selection({link / L"game.bin"});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_HIDDEN, "a marker on the supplied ancestor chain must hide an alias selection");
}

void hides_marker_on_canonical_ancestor_of_alias() {
    Fixture fixture;
    fixture.file(L"managed/target/game.bin");
    const auto link = fixture.root / L"junction";
    junction(link, fixture.root / L"managed/target");
    fixture.file(L"managed/.gametier.json", "broken");
    const auto items = selection({link / L"game.bin"});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_HIDDEN, "a marker on the resolved ancestor chain must hide an alias selection");
}

void hides_inaccessible_metadata() {
    Fixture fixture;
    const auto path = fixture.file(L"denied/game.bin");
    const auto items = selection({path});
    PSECURITY_DESCRIPTOR descriptor = nullptr;
    require(ConvertStringSecurityDescriptorToSecurityDescriptorW(
        L"D:P(D;;0x80;;;WD)", SDDL_REVISION_1, &descriptor, nullptr) != FALSE,
        "create deny-attributes descriptor");
    require(SetFileSecurityW(path.c_str(), DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
        descriptor) != FALSE, "deny metadata access");
    LocalFree(descriptor);
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    // Restore inherited permissions before fixture cleanup.
    require(SetNamedSecurityInfoW(const_cast<PWSTR>(path.c_str()), SE_FILE_OBJECT,
        DACL_SECURITY_INFORMATION | UNPROTECTED_DACL_SECURITY_INFORMATION,
        nullptr, nullptr, nullptr, nullptr) == ERROR_SUCCESS, "restore metadata access");
    require(result == ECS_HIDDEN, "inaccessible file metadata must fail closed");
}

void hides_inaccessible_ancestor_metadata() {
    Fixture fixture;
    const auto path = fixture.file(L"denied/game/data.bin");
    const auto ancestor = fixture.root / L"denied";
    const auto items = selection({path});
    PSECURITY_DESCRIPTOR descriptor = nullptr;
    require(ConvertStringSecurityDescriptorToSecurityDescriptorW(
        L"D:P(D;;0x80;;;WD)", SDDL_REVISION_1, &descriptor, nullptr) != FALSE,
        "create deny-attributes descriptor");
    require(SetFileSecurityW(ancestor.c_str(), DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
        descriptor) != FALSE, "deny ancestor metadata access");
    LocalFree(descriptor);
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(SetNamedSecurityInfoW(const_cast<PWSTR>(ancestor.c_str()), SE_FILE_OBJECT,
        DACL_SECURITY_INFORMATION | UNPROTECTED_DACL_SECURITY_INFORMATION,
        nullptr, nullptr, nullptr, nullptr) == ERROR_SUCCESS, "restore ancestor metadata access");
    require(result == ECS_HIDDEN, "inaccessible ancestor metadata must fail closed");
}

void hides_shell_shortcut() {
    Fixture fixture;
    const auto target = fixture.file(L"target.bin");
    const auto path = fixture.root / L"shortcut.lnk";
    ComPtr<IShellLinkW> link;
    checked(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&link)));
    checked(link->SetPath(target.c_str()));
    ComPtr<IPersistFile> persist;
    checked(link->QueryInterface(IID_PPV_ARGS(&persist)));
    checked(persist->Save(path.c_str(), TRUE));
    const auto items = selection({path});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_HIDDEN, "shell shortcuts must not be promoted as regular files");
}

void respects_disabled_preference() {
    Fixture fixture;
    const auto items = selection({fixture.file(L"game")});
    const auto subject = command();
    setEnabled(0);
    const auto result = state(subject.Get(), items.Get());
    setEnabled(1);
    require(result == ECS_HIDDEN, "disabled preference must hide a valid selection");
}

void hides_invalid_preference() {
    Fixture fixture;
    const auto items = selection({fixture.file(L"game")});
    const auto subject = command();
    const wchar_t invalid[] = L"enabled";
    require(RegSetKeyValueW(HKEY_CURRENT_USER, L"Software\\GameTier", L"ExplorerIntegrationEnabled",
        REG_SZ, invalid, sizeof(invalid)) == ERROR_SUCCESS, "set invalid isolated preference");
    const auto result = state(subject.Get(), items.Get());
    setEnabled(1);
    require(result == ECS_HIDDEN, "an unreadable preference must fail closed");
}

void hides_unknown_preference_value() {
    Fixture fixture;
    const auto items = selection({fixture.file(L"game")});
    const auto subject = command();
    setEnabled(2);
    const auto result = state(subject.Get(), items.Get());
    setEnabled(1);
    require(result == ECS_HIDDEN, "an unknown DWORD preference must fail closed");
}

void leaves_source_unchanged() {
    Fixture fixture;
    const auto path = fixture.file(L"game/data.bin", "untouched payload");
    const auto items = selection({path.parent_path()});
    const auto subject = command();
    const auto before = fs::last_write_time(path);
    const auto result = state(subject.Get(), items.Get());
    const auto after = fs::last_write_time(path);
    std::ifstream input(path, std::ios::binary);
    const std::string content{std::istreambuf_iterator<char>(input), {}};
    require(result == ECS_ENABLED, "source fixture must be eligible");
    require(before == after && content == "untouched payload", "menu inspection must not mutate source");
    require(std::distance(fs::directory_iterator(path.parent_path()), fs::directory_iterator{}) == 1,
        "menu inspection must not create probe links or other entries");
}

void revalidates_on_invoke() {
    Fixture fixture;
    const auto path = fixture.file(L"game/data");
    const auto items = selection({path});
    const auto subject = command();
    require(state(subject.Get(), items.Get()) == ECS_ENABLED, "fixture must start eligible");
    fixture.file(L"game/.gametier.json", "broken");
    const auto result = subject->Invoke(items.Get(), nullptr);
    require(result == HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED), "invoke must reject a newly managed source");
}

void rejects_invoke_when_disabled() {
    Fixture fixture;
    const auto items = selection({fixture.file(L"game/data")});
    const auto subject = command();
    setEnabled(0);
    const auto result = subject->Invoke(items.Get(), nullptr);
    setEnabled(1);
    require(result == HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED), "invoke must recheck integration preference");
}

void reports_application_launch_failure() {
    Fixture fixture;
    const auto items = selection({fixture.file(L"game/data")});
    const auto subject = command();
    const auto hiddenApplication = application.parent_path() / L"GameTier.hidden";
    fs::rename(application, hiddenApplication);
    const auto result = subject->Invoke(items.Get(), nullptr);
    fs::rename(hiddenApplication, application);
    require(result == HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND), "missing launcher must report the process error");
}

void launches_adjacent_application_with_exact_unicode_path() {
    Fixture fixture;
    const auto path = fixture.file(L"game space \u00e4\u65e5/data & payload.bin");
    const auto capture = fixture.root / L"arguments.bin";
    const auto eventName = L"Local\\GameTierExplorerTest-" + std::to_wstring(GetCurrentProcessId());
    const auto event = CreateEventW(nullptr, TRUE, FALSE, eventName.c_str());
    require(event != nullptr, "create launch completion event");
    SetEnvironmentVariableW(L"GAMETIER_TEST_CAPTURE", capture.c_str());
    SetEnvironmentVariableW(L"GAMETIER_TEST_EVENT", eventName.c_str());
    const auto items = selection({path});
    ComPtr<IShellItem> selected;
    checked(items->GetItemAt(0, &selected));
    PWSTR rawPath = nullptr;
    checked(selected->GetDisplayName(SIGDN_FILESYSPATH, &rawPath));
    const std::wstring selectedPath(rawPath);
    CoTaskMemFree(rawPath);
    require(fs::equivalent(fs::path(selectedPath), path), "shell selection must identify the fixture");
    require(selectedPath.find(L"game space \u00e4\u65e5\\data & payload.bin") != std::wstring::npos,
        "shell selection must retain Unicode, spaces and ampersand");
    const auto subject = command();
    const auto result = subject->Invoke(items.Get(), nullptr);
    const auto completed = WaitForSingleObject(event, 10000);
    CloseHandle(event);
    SetEnvironmentVariableW(L"GAMETIER_TEST_CAPTURE", nullptr);
    SetEnvironmentVariableW(L"GAMETIER_TEST_EVENT", nullptr);
    require(result == S_OK, "invoke must start adjacent application");
    require(completed == WAIT_OBJECT_0, "launched application must finish argument capture");
    std::ifstream input(capture, std::ios::binary);
    const std::string bytes{std::istreambuf_iterator<char>(input), {}};
    const std::wstring expected = L"--promote\n" + selectedPath;
    const std::string expectedBytes(reinterpret_cast<const char*>(expected.data()), expected.size() * sizeof(wchar_t));
    if (bytes != expectedBytes) {
        std::cerr << "Expected UTF-16 bytes:";
        for (const unsigned char byte : expectedBytes) std::cerr << ' ' << std::hex << static_cast<unsigned>(byte);
        std::cerr << "\nCaptured UTF-16 bytes:";
        for (const unsigned char byte : bytes) std::cerr << ' ' << std::hex << static_cast<unsigned>(byte);
        std::cerr << std::dec << '\n';
    }
    require(bytes == expectedBytes, "application must receive exactly --promote and the absolute Unicode path");
}

void keeps_dll_loaded_while_com_objects_exist() {
    require(canUnload() == S_OK, "no objects should remain between tests");
    const auto subject = command();
    const auto whileAlive = canUnload();
    require(whileAlive == S_FALSE, "a live command must keep the DLL loaded");
}

void rejects_aggregation_and_unknown_classes() {
    ComPtr<IClassFactory> factory;
    checked(getClassObject(commandId, IID_PPV_ARGS(&factory)));
    void* instance = reinterpret_cast<void*>(1);
    const auto aggregation = factory->CreateInstance(factory.Get(), IID_IExplorerCommand, &instance);
    require(aggregation == CLASS_E_NOAGGREGATION && instance == nullptr, "aggregation must be rejected safely");
    const auto unknown = getClassObject(GUID_NULL, IID_IClassFactory, &instance);
    require(unknown == CLASS_E_CLASSNOTAVAILABLE && instance == nullptr, "unknown CLSID must be rejected safely");
    checked(factory->LockServer(TRUE));
    require(canUnload() == S_FALSE, "server lock must prevent unloading");
    checked(factory->LockServer(FALSE));
}

void reports_command_metadata_and_rejects_null_outputs() {
    const auto subject = command();
    PWSTR title = nullptr;
    const auto titleResult = subject->GetTitle(nullptr, &title);
    require(titleResult == S_OK && title != nullptr && std::wstring(title) == L"Promote with GameTier",
        "command must expose its visible action");
    CoTaskMemFree(title);
    GUID canonical{};
    checked(subject->GetCanonicalName(&canonical));
    require(canonical == commandId, "canonical verb must identify the registered command");
    EXPCMDFLAGS flags = ECF_HASSUBCOMMANDS;
    checked(subject->GetFlags(&flags));
    require(flags == ECF_DEFAULT, "command must expose a single action");
    IEnumExplorerCommand* children = reinterpret_cast<IEnumExplorerCommand*>(1);
    const auto childResult = subject->EnumSubCommands(&children);
    require(childResult == E_NOTIMPL && children == nullptr, "command must have no child actions");
    require(subject->GetState(nullptr, TRUE, nullptr) == E_POINTER &&
        subject->GetTitle(nullptr, nullptr) == E_POINTER && subject->GetIcon(nullptr, nullptr) == E_POINTER &&
        subject->GetToolTip(nullptr, nullptr) == E_POINTER && subject->GetCanonicalName(nullptr) == E_POINTER &&
        subject->GetFlags(nullptr) == E_POINTER && subject->EnumSubCommands(nullptr) == E_POINTER &&
        subject->QueryInterface(IID_IUnknown, nullptr) == E_POINTER,
        "invalid COM outputs must be rejected without dereferencing null");
    void* unsupported = reinterpret_cast<void*>(1);
    const auto interfaceResult = subject->QueryInterface(IID_IClassFactory, &unsupported);
    require(interfaceResult == E_NOINTERFACE && unsupported == nullptr, "unsupported interfaces must clear output");
}

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void checked(HRESULT result) {
    if (FAILED(result)) throw std::runtime_error("HRESULT " + std::to_string(static_cast<unsigned long>(result)));
}

ComPtr<IExplorerCommand> command() {
    ComPtr<IClassFactory> factory;
    checked(getClassObject(commandId, IID_PPV_ARGS(&factory)));
    ComPtr<IExplorerCommand> result;
    checked(factory->CreateInstance(nullptr, IID_PPV_ARGS(&result)));
    return result;
}

ComPtr<IShellItemArray> selection(const std::vector<fs::path>& paths) {
    std::vector<PIDLIST_ABSOLUTE> ids;
    for (const auto& path : paths) {
        PIDLIST_ABSOLUTE id = nullptr;
        auto shellPath = path;
        shellPath.make_preferred();
        checked(SHParseDisplayName(shellPath.c_str(), nullptr, &id, 0, nullptr));
        ids.push_back(id);
    }
    ComPtr<IShellItemArray> result;
    const auto status = SHCreateShellItemArrayFromIDLists(static_cast<UINT>(ids.size()),
        const_cast<PCIDLIST_ABSOLUTE*>(ids.data()), &result);
    for (const auto id : ids) CoTaskMemFree(id);
    checked(status);
    return result;
}

Fixture::Fixture() {
    wchar_t temp[MAX_PATH + 1]{};
    require(GetTempPathW(MAX_PATH, temp) != 0, "get temporary path");
    GUID id{};
    checked(CoCreateGuid(&id));
    wchar_t name[40]{};
    StringFromGUID2(id, name, 40);
    root = fs::path(temp) / (L"GameTierExplorer-" + std::wstring(name));
    fs::create_directory(root);
}

Fixture::~Fixture() {
    std::error_code error;
    fs::remove_all(root, error);
}

fs::path Fixture::directory(const fs::path& name) const {
    const auto path = (root / name).make_preferred();
    fs::create_directories(path);
    return path;
}

fs::path Fixture::file(const fs::path& name, const std::string& content) const {
    const auto path = (root / name).make_preferred();
    fs::create_directories(path.parent_path());
    std::ofstream output(path, std::ios::binary);
    output << content;
    require(output.good(), "create fixture file");
    return path;
}

EXPCMDSTATE state(IExplorerCommand* subject, IShellItemArray* items) {
    EXPCMDSTATE output = ECS_DISABLED;
    checked(subject->GetState(items, TRUE, &output));
    return output;
}

void setEnabled(DWORD enabled) {
    require(RegSetKeyValueW(HKEY_CURRENT_USER, L"Software\\GameTier", L"ExplorerIntegrationEnabled",
        REG_DWORD, &enabled, sizeof(enabled)) == ERROR_SUCCESS, "set isolated preference");
}

void junction(const fs::path& link, const fs::path& target) {
    fs::create_directory(link);
    auto preferredTarget = target;
    preferredTarget.make_preferred();
    const auto substitute = L"\\??\\" + preferredTarget.native();
    const auto print = preferredTarget.native();
    struct MountPoint {
        DWORD tag;
        WORD dataLength;
        WORD reserved;
        WORD substituteOffset;
        WORD substituteLength;
        WORD printOffset;
        WORD printLength;
        wchar_t names[1];
    };
    const auto nameBytes = (substitute.size() + print.size() + 2) * sizeof(wchar_t);
    std::vector<unsigned char> storage(offsetof(MountPoint, names) + nameBytes);
    auto* data = reinterpret_cast<MountPoint*>(storage.data());
    data->tag = IO_REPARSE_TAG_MOUNT_POINT;
    data->dataLength = static_cast<WORD>(storage.size() - 8);
    data->substituteLength = static_cast<WORD>(substitute.size() * sizeof(wchar_t));
    data->printOffset = static_cast<WORD>((substitute.size() + 1) * sizeof(wchar_t));
    data->printLength = static_cast<WORD>(print.size() * sizeof(wchar_t));
    memcpy(data->names, substitute.c_str(), (substitute.size() + 1) * sizeof(wchar_t));
    memcpy(reinterpret_cast<unsigned char*>(data->names) + data->printOffset,
        print.c_str(), (print.size() + 1) * sizeof(wchar_t));
    const auto handle = CreateFileW(link.c_str(), GENERIC_WRITE, 0, nullptr, OPEN_EXISTING,
        FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    require(handle != INVALID_HANDLE_VALUE, "open junction fixture");
    DWORD returned = 0;
    const auto result = DeviceIoControl(handle, FSCTL_SET_REPARSE_POINT, data,
        static_cast<DWORD>(storage.size()), nullptr, 0, &returned, nullptr);
    CloseHandle(handle);
    require(result != FALSE, "create junction without symlink privilege");
}
}

int wmain(int argc, wchar_t** argv) {
    if (argc != 2) return 2;
    application = fs::path(argv[1]).parent_path() / L"GameTier.exe";
    checked(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED));
    const auto hivePath = L"Software\\GameTierExplorerTests\\" + std::to_wstring(GetCurrentProcessId());
    HKEY hive = nullptr;
    require(RegCreateKeyExW(HKEY_CURRENT_USER, hivePath.c_str(), 0, nullptr, 0,
        KEY_ALL_ACCESS, nullptr, &hive, nullptr) == ERROR_SUCCESS, "create isolated registry hive");
    require(RegOverridePredefKey(HKEY_CURRENT_USER, hive) == ERROR_SUCCESS, "isolate preferences from user settings");
    const auto library = LoadLibraryW(argv[1]);
    require(library != nullptr, "load command DLL");
    getClassObject = reinterpret_cast<GetClassObject>(GetProcAddress(library, "DllGetClassObject"));
    canUnload = reinterpret_cast<CanUnload>(GetProcAddress(library, "DllCanUnloadNow"));
    require(getClassObject != nullptr && canUnload != nullptr, "find COM exports");
    const std::vector<std::pair<const char*, std::function<void()>>> tests{
        {"managed original, destination and descendants", demotes_verified_original_destination_and_descendants},
        {"managed backup folder and descendants", demotes_backup_game_folder_and_descendants},
        {"restore missing target", restores_backup_when_target_missing},
        {"independent backup restoration", restores_independent_backup_without_valid_index},
        {"removed destination parent", restores_after_destination_parent_removed},
        {"redirected destination parent", hides_redirected_destination_parent},
        {"interrupted and broken relationships", hides_interrupted_and_broken_relationships},
        {"unsafe backup restoration", hides_unsafe_backup_states},
        {"invalid index documents", hides_invalid_index_documents},
        {"unsafe backup blocks demotion", rejects_backup_invalidating_completed_promotion},
        {"dynamic launch and restoration revalidation", launches_dynamic_actions_and_revalidates},
        {"changed menu action", rejects_changed_menu_action},
        {"alias to another game", rejects_alias_to_another_managed_game},
        {"whole game beneath embedded markers", identifies_whole_game_beneath_embedded_markers},
        {"managed reparse paths and metadata", rejects_managed_reparse_metadata_and_descendants},
        {"invalid index records", rejects_invalid_index_records},
        {"invalid backup documents", rejects_invalid_backup_documents},
        {"one file", enables_one_file},
        {"one directory, bounded inspection", enables_one_directory_without_scanning_descendants},
        {"empty selection", hides_empty_selection},
        {"multiple selections", hides_multiple_selections},
        {"deferred inspection", defers_filesystem_and_preference_reads},
        {"cached title and tooltip", uses_cached_title_and_tooltip_without_reinspection},
        {"volume root", hides_filesystem_root},
        {"missing metadata", hides_missing_metadata},
        {"selected marker", hides_selected_directory_marker},
        {"parent marker", hides_parent_marker_regardless_of_contents},
        {"ancestor marker", hides_distant_ancestor_marker},
        {"backup storage and descendants", hides_backup_storage_and_descendants},
        {"backup storage case variants", hides_backup_storage_case_variants},
        {"backup alias ancestors", hides_backup_alias_ancestors},
        {"similar backup names", enables_similar_names_outside_backup_directories},
        {"junction selection", hides_reparse_directory},
        {"directory alias ancestor", enables_regular_file_below_directory_alias},
        {"lexical alias marker", hides_marker_on_lexical_ancestor_of_alias},
        {"canonical alias marker", hides_marker_on_canonical_ancestor_of_alias},
        {"inaccessible metadata", hides_inaccessible_metadata},
        {"inaccessible ancestor metadata", hides_inaccessible_ancestor_metadata},
        {"shell shortcut", hides_shell_shortcut},
        {"disabled preference", respects_disabled_preference},
        {"invalid preference", hides_invalid_preference},
        {"unknown preference", hides_unknown_preference_value},
        {"unchanged inputs", leaves_source_unchanged},
        {"invoke revalidation", revalidates_on_invoke},
        {"disabled invocation", rejects_invoke_when_disabled},
        {"application launch failure", reports_application_launch_failure},
        {"Unicode application launch", launches_adjacent_application_with_exact_unicode_path},
        {"COM object lifetime", keeps_dll_loaded_while_com_objects_exist},
        {"COM factory", rejects_aggregation_and_unknown_classes},
        {"command metadata", reports_command_metadata_and_rejects_null_outputs},
    };
    int failures = 0;
    for (const auto& [name, test] : tests) {
        try { test(); std::cout << "PASS " << name << '\n'; }
        catch (const std::exception& error) { ++failures; std::cerr << "FAIL " << name << ": " << error.what() << '\n'; }
    }
    require(canUnload() == S_OK, "all COM references must be released");
    FreeLibrary(library);
    RegOverridePredefKey(HKEY_CURRENT_USER, nullptr);
    RegCloseKey(hive);
    RegDeleteTreeW(HKEY_CURRENT_USER, hivePath.c_str());
    CoUninitialize();
    return failures == 0 ? 0 : 1;
}
