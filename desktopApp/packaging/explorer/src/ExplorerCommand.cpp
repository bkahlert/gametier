#include <windows.h>
#include <shlobj.h>
#include <shlwapi.h>
#include <pathcch.h>
#include <winioctl.h>

#include <atomic>
#include <filesystem>
#include <memory>
#include <new>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>
#include "../third_party/nlohmann/json.hpp"

namespace {
constexpr GUID commandId{0x9b6ed281, 0x384a, 0x4dbf, {0x98, 0xc0, 0xd2, 0x6b, 0x6d, 0x41, 0xb3, 0x52}};
HMODULE module = nullptr;
std::atomic<long> objects{0};
std::atomic<long> serverLocks{0};

struct HandleCloser {
    void operator()(void* value) const noexcept {
        if (value != nullptr && value != INVALID_HANDLE_VALUE) CloseHandle(value);
    }
};
using Handle = std::unique_ptr<void, HandleCloser>;

template<class T> struct Release {
    void operator()(T* value) const noexcept { if (value != nullptr) value->Release(); }
};

struct TaskMemFree {
    void operator()(wchar_t* value) const noexcept { CoTaskMemFree(value); }
};

bool integrationEnabled() noexcept {
    DWORD value = 1;
    DWORD bytes = sizeof(value);
    const auto status = RegGetValueW(HKEY_CURRENT_USER, L"Software\\GameTier",
        L"ExplorerIntegrationEnabled", RRF_RT_REG_DWORD, nullptr, &value, &bytes);
    if (status == ERROR_FILE_NOT_FOUND || status == ERROR_PATH_NOT_FOUND) return true;
    return status == ERROR_SUCCESS && value == 1;
}

bool metadata(const std::filesystem::path& path, DWORD& attributes,
              std::filesystem::path* canonical = nullptr) {
    const auto flags = FILE_FLAG_BACKUP_SEMANTICS | (canonical != nullptr ? FILE_FLAG_OPEN_REPARSE_POINT : 0);
    Handle handle(CreateFileW(path.c_str(), FILE_READ_ATTRIBUTES,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
        flags, nullptr));
    if (handle.get() == INVALID_HANDLE_VALUE || GetFileType(handle.get()) != FILE_TYPE_DISK) return false;
    FILE_ATTRIBUTE_TAG_INFO info{};
    if (!GetFileInformationByHandleEx(handle.get(), FileAttributeTagInfo, &info, sizeof(info))) return false;
    attributes = info.FileAttributes;
    if ((attributes & FILE_ATTRIBUTE_DEVICE) != 0) return false;
    if (canonical != nullptr) {
        if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) return false;
        const auto length = GetFinalPathNameByHandleW(handle.get(), nullptr, 0, FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
        if (length == 0 || length >= 32767) return false;
        std::vector<wchar_t> resolved(length + 1);
        const auto written = GetFinalPathNameByHandleW(handle.get(), resolved.data(),
            static_cast<DWORD>(resolved.size()), FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
        if (written == 0 || written >= resolved.size()) return false;
        std::wstring value(resolved.data(), written);
        if (value.compare(0, 8, L"\\\\?\\UNC\\") == 0) value = L"\\\\" + value.substr(8);
        else if (value.compare(0, 4, L"\\\\?\\") == 0) value.erase(0, 4);
        *canonical = value;
    }
    return true;
}

bool unmanagedAncestors(std::filesystem::path ancestor) {
    // Each pass removes one path component; never enumerate descendants or read marker contents.
    for (size_t depth = 0; depth < 32767 && !ancestor.empty(); ++depth) {
        DWORD directoryAttributes = 0;
        if (!metadata(ancestor, directoryAttributes) || (directoryAttributes & FILE_ATTRIBUTE_DIRECTORY) == 0) return false;
        if (_wcsicmp(ancestor.filename().c_str(), L".gametier") == 0) return false;
        const auto marker = ancestor / L".gametier.json";
        if (GetFileAttributesW(marker.c_str()) != INVALID_FILE_ATTRIBUTES) return false;
        if (GetLastError() != ERROR_FILE_NOT_FOUND) return false;
        if (PathCchIsRoot(ancestor.c_str())) return true;
        const auto parent = ancestor.parent_path();
        if (parent == ancestor) return true;
        ancestor = parent;
    }
    return false;
}

#include "ManagedSelection.h"

bool selectionPath(IShellItemArray* items, std::filesystem::path& selected) {
    if (items == nullptr) return false;
    DWORD count = 0;
    if (FAILED(items->GetCount(&count)) || count != 1) return false;
    IShellItem* rawItem = nullptr;
    if (FAILED(items->GetItemAt(0, &rawItem)) || rawItem == nullptr) return false;
    std::unique_ptr<IShellItem, Release<IShellItem>> item(rawItem);
    PWSTR rawPath = nullptr;
    if (FAILED(item->GetDisplayName(SIGDN_FILESYSPATH, &rawPath)) || rawPath == nullptr) return false;
    const std::unique_ptr<wchar_t, TaskMemFree> pathString(rawPath);
    selected = rawPath;
    if (!selected.is_absolute() || selected.native().size() >= 32767) return false;
    for (const auto& component : selected) {
        if (component == L"." || component == L"..") return false;
    }
    selected = selected.lexically_normal();
    if (selected.filename().empty() && selected != selected.root_path()) selected = selected.parent_path();
    return !PathCchIsRoot(selected.c_str()) && safePath(selected);
}

Action availableAction(IShellItemArray* items, std::filesystem::path& selected) {
    if (!integrationEnabled() || !selectionPath(items, selected)) return Action::None;
    IShellItem* rawItem = nullptr;
    if (FAILED(items->GetItemAt(0, &rawItem)) || rawItem == nullptr) return Action::None;
    std::unique_ptr<IShellItem, Release<IShellItem>> item(rawItem);
    SFGAOF attributes = 0;
    if (FAILED(item->GetAttributes(SFGAO_FILESYSTEM | SFGAO_LINK, &attributes)) ||
        (attributes & SFGAO_FILESYSTEM) == 0) return Action::None;
    const auto managed = managedSelection(selected);
    if (managed.managed) return managed.action;
    DWORD fileAttributes = 0;
    std::filesystem::path canonical;
    if (!metadata(selected, fileAttributes, &canonical) || !canonical.is_absolute() || PathCchIsRoot(canonical.c_str())) return Action::None;
    const auto directory = (fileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
    const auto lexicalAncestor = directory ? selected : selected.parent_path();
    const auto canonicalAncestor = directory ? canonical : canonical.parent_path();
    const auto resolved = managedSelection(canonical);
    if (resolved.managed || (attributes & SFGAO_LINK) != 0) return Action::None;
    return unmanagedAncestors(lexicalAncestor) &&
        (canonicalAncestor == lexicalAncestor || unmanagedAncestors(canonicalAncestor)) ? Action::Promote : Action::None;
}

std::filesystem::path applicationPath() {
    std::vector<wchar_t> path(32768);
    const auto length = GetModuleFileNameW(module, path.data(), static_cast<DWORD>(path.size()));
    if (length == 0 || length >= path.size()) return {};
    return std::filesystem::path(std::wstring(path.data(), length)).parent_path() / L"GameTier.exe";
}

std::wstring quoteArgument(const std::wstring& argument) {
    std::wstring quoted = L"\"";
    size_t slashes = 0;
    for (const auto character : argument) {
        if (character == L'\\') {
            ++slashes;
        } else {
            quoted.append(character == L'"' ? slashes * 2 + 1 : slashes, L'\\');
            quoted += character;
            slashes = 0;
        }
    }
    quoted.append(slashes * 2, L'\\');
    quoted += L'"';
    return quoted;
}

class ExplorerCommand final : public IExplorerCommand {
    std::atomic<ULONG> references_{1};
    bool presented_ = false;
    Action presentedAction_ = Action::None;
    std::filesystem::path presentedSelection_;
    bool resolved_ = false;
    Action resolvedAction_ = Action::None;
    std::filesystem::path resolvedSelection_;

    void resolve(Action action, const std::filesystem::path& selection) {
        resolvedSelection_ = selection;
        resolvedAction_ = action;
        resolved_ = true;
    }

    void present(Action action, const std::filesystem::path& selection) {
        presentedSelection_ = selection;
        presentedAction_ = action;
        presented_ = true;
    }
public:
    ExplorerCommand() noexcept { ++objects; }
    ~ExplorerCommand() { --objects; }

    IFACEMETHODIMP QueryInterface(REFIID id, void** output) override {
        if (output == nullptr) return E_POINTER;
        *output = nullptr;
        if (id != IID_IUnknown && id != IID_IExplorerCommand) return E_NOINTERFACE;
        *output = static_cast<IExplorerCommand*>(this);
        AddRef();
        return S_OK;
    }

    IFACEMETHODIMP_(ULONG) AddRef() override { return ++references_; }
    IFACEMETHODIMP_(ULONG) Release() override {
        const auto remaining = --references_;
        if (remaining == 0) delete this;
        return remaining;
    }

    IFACEMETHODIMP GetTitle(IShellItemArray* items, PWSTR* output) override {
        if (output == nullptr) return E_POINTER;
        *output = nullptr;
        try {
            if (items == nullptr) return SHStrDupW(L"Promote with GameTier", output);
            std::filesystem::path selected;
            if (!selectionPath(items, selected)) return E_FAIL;
            Action action;
            if (resolved_ && samePath(selected, resolvedSelection_)) action = resolvedAction_;
            else {
                if (!localInspectionAllowed(selected)) {
                    presented_ = false;
                    return SHStrDupW(L"GameTier", output);
                }
                action = availableAction(items, selected);
                resolve(action, selected);
            }
            present(action, selected);
            if (action == Action::None) return E_FAIL;
            return SHStrDupW(action == Action::Demote ? L"Demote with GameTier" :
                action == Action::RestoreBackup ? L"Restore backup with GameTier" : L"Promote with GameTier", output);
        } catch (...) { return E_FAIL; }
    }

    IFACEMETHODIMP GetIcon(IShellItemArray*, PWSTR* output) override {
        if (output == nullptr) return E_POINTER;
        *output = nullptr;
        try {
            const auto application = applicationPath();
            if (application.empty()) return E_FAIL;
            return SHStrDupW((application.native() + L",0").c_str(), output);
        } catch (...) { return E_OUTOFMEMORY; }
    }

    IFACEMETHODIMP GetToolTip(IShellItemArray* items, PWSTR* output) override {
        if (output == nullptr) return E_POINTER;
        *output = nullptr;
        try {
            if (items == nullptr) return SHStrDupW(L"Review promotion to your game drive", output);
            std::filesystem::path selected;
            if (!selectionPath(items, selected)) return E_FAIL;
            const auto action = presented_ && samePath(selected, presentedSelection_) ? presentedAction_ :
                resolved_ && samePath(selected, resolvedSelection_) ? resolvedAction_ : Action::None;
            if (action == Action::None) return SHStrDupW(L"Review this game with GameTier", output);
            return SHStrDupW(action == Action::Demote ? L"Review returning this game to its original location" :
                action == Action::RestoreBackup ? L"Review restoring this game's source backup" : L"Review promotion to your game drive", output);
        } catch (...) { return E_FAIL; }
    }

    IFACEMETHODIMP GetCanonicalName(GUID* output) override {
        if (output == nullptr) return E_POINTER;
        *output = commandId;
        return S_OK;
    }

    IFACEMETHODIMP GetState(IShellItemArray* items, BOOL okToBeSlow, EXPCMDSTATE* output) override {
        if (output == nullptr) return E_POINTER;
        *output = ECS_HIDDEN;
        if (!okToBeSlow) return E_PENDING;
        try {
            std::filesystem::path selected;
            const auto action = availableAction(items, selected);
            resolve(action, selected);
            if (action != Action::None) *output = ECS_ENABLED;
        } catch (...) { /* Unknown metadata or allocation errors leave the command hidden. */ }
        return S_OK;
    }

    IFACEMETHODIMP Invoke(IShellItemArray* items, IBindCtx*) override {
        try {
            std::filesystem::path selected;
            const auto action = availableAction(items, selected);
            if (action == Action::None) return HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED);
            if (presented_ && (action != presentedAction_ || !samePath(selected, presentedSelection_)))
                return HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED);
            if (!presented_ && resolved_ && (action != resolvedAction_ || !samePath(selected, resolvedSelection_)))
                return HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED);
            const auto application = applicationPath();
            if (application.empty()) return E_FAIL;
            const auto verb = action == Action::Demote ? L" --demote " : action == Action::RestoreBackup ? L" --restore-backup " : L" --promote ";
            auto arguments = quoteArgument(application.native()) + verb + quoteArgument(selected.native());
            STARTUPINFOW startup{};
            startup.cb = sizeof(startup);
            PROCESS_INFORMATION process{};
            if (!CreateProcessW(application.c_str(), arguments.data(), nullptr, nullptr, FALSE, 0,
                nullptr, application.parent_path().c_str(), &startup, &process)) return HRESULT_FROM_WIN32(GetLastError());
            CloseHandle(process.hThread);
            CloseHandle(process.hProcess);
            return S_OK;
        } catch (const std::bad_alloc&) { return E_OUTOFMEMORY; }
        catch (...) { return HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED); }
    }

    IFACEMETHODIMP GetFlags(EXPCMDFLAGS* output) override {
        if (output == nullptr) return E_POINTER;
        *output = ECF_DEFAULT;
        return S_OK;
    }

    IFACEMETHODIMP EnumSubCommands(IEnumExplorerCommand** output) override {
        if (output == nullptr) return E_POINTER;
        *output = nullptr;
        return E_NOTIMPL;
    }
};

class ClassFactory final : public IClassFactory {
    std::atomic<ULONG> references_{1};
public:
    ClassFactory() noexcept { ++objects; }
    ~ClassFactory() { --objects; }
    IFACEMETHODIMP QueryInterface(REFIID id, void** output) override {
        if (output == nullptr) return E_POINTER;
        *output = nullptr;
        if (id != IID_IUnknown && id != IID_IClassFactory) return E_NOINTERFACE;
        *output = static_cast<IClassFactory*>(this);
        AddRef();
        return S_OK;
    }
    IFACEMETHODIMP_(ULONG) AddRef() override { return ++references_; }
    IFACEMETHODIMP_(ULONG) Release() override {
        const auto remaining = --references_;
        if (remaining == 0) delete this;
        return remaining;
    }
    IFACEMETHODIMP CreateInstance(IUnknown* outer, REFIID id, void** output) override {
        if (output == nullptr) return E_POINTER;
        *output = nullptr;
        if (outer != nullptr) return CLASS_E_NOAGGREGATION;
        const auto instance = new (std::nothrow) ExplorerCommand();
        if (instance == nullptr) return E_OUTOFMEMORY;
        const auto result = instance->QueryInterface(id, output);
        instance->Release();
        return result;
    }
    IFACEMETHODIMP LockServer(BOOL lock) override {
        if (lock) ++serverLocks;
        else {
            auto current = serverLocks.load();
            do {
                if (current == 0) return E_UNEXPECTED;
            } while (!serverLocks.compare_exchange_weak(current, current - 1));
        }
        return S_OK;
    }
};
}

extern "C" HRESULT STDAPICALLTYPE DllGetClassObject(REFCLSID id, REFIID interfaceId, void** output) {
    if (output == nullptr) return E_POINTER;
    *output = nullptr;
    if (id != commandId) return CLASS_E_CLASSNOTAVAILABLE;
    const auto factory = new (std::nothrow) ClassFactory();
    if (factory == nullptr) return E_OUTOFMEMORY;
    const auto result = factory->QueryInterface(interfaceId, output);
    factory->Release();
    return result;
}

extern "C" HRESULT STDAPICALLTYPE DllCanUnloadNow() {
    return objects.load() == 0 && serverLocks.load() == 0 ? S_OK : S_FALSE;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) module = instance;
    return TRUE;
}
