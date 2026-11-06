namespace fs = std::filesystem;
using Json = nlohmann::json;

enum class Action { None, Promote, Demote, RestoreBackup };
struct Decision { bool managed = false; Action action = Action::None; };
struct PromotionRecord { fs::path original, destination; std::string state; };

fs::path volumeRoot(const fs::path& path) {
    auto root = path.root_path();
    if (root.root_name().native().compare(0, 2, L"\\\\") == 0) {
        const auto relative = path.relative_path();
        if (!relative.empty()) root /= *relative.begin();
    }
    return root;
}

bool samePath(const fs::path& first, const fs::path& second) {
    return CompareStringOrdinal(fs::path(first).make_preferred().c_str(), -1,
        fs::path(second).make_preferred().c_str(), -1, TRUE) == CSTR_EQUAL;
}

bool within(const fs::path& child, const fs::path& parent) {
    auto relative = child.begin();
    for (const auto& part : parent) {
        if (relative == child.end() || !samePath(*relative, part)) return false;
        ++relative;
    }
    return true;
}

bool safePath(const fs::path& path) {
    if (!path.is_absolute() || path.filename().empty() || samePath(path, volumeRoot(path)) ||
        path.native().size() >= 32767 || path != path.lexically_normal() ||
        path.native().find(L'\0') != std::wstring::npos || path.native().compare(0, 4, L"\\\\?\\") == 0 ||
        path.native().compare(0, 4, L"\\\\.\\") == 0) return false;
    for (const auto& component : path.relative_path()) {
        const auto name = component.native();
        if (name.empty() || name == L"." || name == L".." || name.back() == L'.' || name.back() == L' ') return false;
        for (const auto character : name) {
            if (character < 32 || std::wstring(L"<>:\"|?*").find(character) != std::wstring::npos) return false;
        }
        auto base = name.substr(0, name.find(L'.'));
        for (auto& character : base) character = static_cast<wchar_t>(towupper(character));
        if (base == L"CON" || base == L"PRN" || base == L"AUX" || base == L"NUL" ||
            (base.size() == 4 && (base.substr(0, 3) == L"COM" || base.substr(0, 3) == L"LPT") &&
             base[3] >= L'1' && base[3] <= L'9')) return false;
    }
    return true;
}

enum class Entry { Missing, Present, Unknown };
Entry entryInfo(const fs::path& path, FILE_ATTRIBUTE_TAG_INFO& info) {
    Handle handle(CreateFileW(path.c_str(), FILE_READ_ATTRIBUTES, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        nullptr, OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, nullptr));
    if (handle.get() == INVALID_HANDLE_VALUE) {
        const auto error = GetLastError();
        return error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND ? Entry::Missing : Entry::Unknown;
    }
    return GetFileType(handle.get()) == FILE_TYPE_DISK &&
        GetFileInformationByHandleEx(handle.get(), FileAttributeTagInfo, &info, sizeof(info)) ? Entry::Present : Entry::Unknown;
}

bool missing(const fs::path& path) {
    FILE_ATTRIBUTE_TAG_INFO info{};
    return entryInfo(path, info) == Entry::Missing;
}

bool ordinary(const fs::path& path, bool directory = false) {
    FILE_ATTRIBUTE_TAG_INFO info{};
    return entryInfo(path, info) == Entry::Present &&
        (info.FileAttributes & (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DEVICE)) == 0 &&
        (!directory || (info.FileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0);
}

bool ordinaryParents(const fs::path& path) {
    auto current = volumeRoot(path);
    if (!ordinary(current, true)) return false;
    for (const auto& part : path.lexically_relative(current)) {
        if (part == L".") continue;
        current /= part;
        if (!ordinary(current, true)) return false;
    }
    return true;
}

bool ordinaryExistingParents(const fs::path& path) {
    auto current = volumeRoot(path);
    if (!ordinary(current, true)) return false;
    for (const auto& part : path.lexically_relative(current)) {
        if (part == L".") continue;
        current /= part;
        FILE_ATTRIBUTE_TAG_INFO info{};
        const auto status = entryInfo(current, info);
        if (status == Entry::Unknown || (status == Entry::Present &&
            ((info.FileAttributes & FILE_ATTRIBUTE_DIRECTORY) == 0 ||
             (info.FileAttributes & (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DEVICE)) != 0))) return false;
    }
    return true;
}

bool symbolicTarget(const fs::path& original, fs::path& target) {
    FILE_ATTRIBUTE_TAG_INFO info{};
    if (entryInfo(original, info) != Entry::Present || info.ReparseTag != IO_REPARSE_TAG_SYMLINK) return false;
    std::error_code error;
    auto linked = fs::read_symlink(original, error);
    if (error) return false;
    target = (linked.is_absolute() ? linked : original.parent_path() / linked).lexically_normal();
    return safePath(target);
}

bool localDrive(const fs::path& path) {
    if (path.root_name().native().compare(0, 2, L"\\\\") == 0) return false;
    const auto root = volumeRoot(path);
    return GetDriveTypeW(root.c_str()) == DRIVE_FIXED || GetDriveTypeW(root.c_str()) == DRIVE_REMOVABLE;
}

bool localInspectionAllowed(const fs::path& path) {
    if (!localDrive(path)) return false;
    auto current = volumeRoot(path);
    for (const auto& part : path.lexically_relative(current)) {
        if (part == L".") continue;
        current /= part;
        FILE_ATTRIBUTE_TAG_INFO info{};
        if (entryInfo(current, info) != Entry::Present) return false;
        if ((info.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            fs::path linked;
            if (!symbolicTarget(current, linked) || !localDrive(linked) ||
                !ordinaryExistingParents(linked.parent_path()) || (!missing(linked) && !ordinary(linked))) return false;
        }
    }
    return true;
}

std::wstring wideString(const Json& value) {
    if (!value.is_string()) throw std::runtime_error("Expected metadata string");
    const auto text = value.get<std::string>();
    if (text.empty()) throw std::runtime_error("Empty metadata path");
    const auto length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), nullptr, 0);
    if (length == 0) throw std::runtime_error("Invalid metadata UTF-8");
    std::wstring result(length, L'\0');
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), result.data(), length) != length)
        throw std::runtime_error("Invalid metadata UTF-8");
    return result;
}

Json readDocument(const fs::path& file) {
    constexpr DWORD maximumBytes = 1024 * 1024;
    Handle handle(CreateFileW(file.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        nullptr, OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS, nullptr));
    FILE_ATTRIBUTE_TAG_INFO info{};
    LARGE_INTEGER size{};
    if (handle.get() == INVALID_HANDLE_VALUE || GetFileType(handle.get()) != FILE_TYPE_DISK ||
        !GetFileInformationByHandleEx(handle.get(), FileAttributeTagInfo, &info, sizeof(info)) ||
        (info.FileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_DEVICE)) != 0 ||
        !GetFileSizeEx(handle.get(), &size) || size.QuadPart <= 0 || size.QuadPart > maximumBytes)
        throw std::runtime_error("Metadata must be a bounded ordinary file");
    std::string text(static_cast<size_t>(size.QuadPart), '\0');
    DWORD read = 0;
    if (!ReadFile(handle.get(), text.data(), static_cast<DWORD>(text.size()), &read, nullptr) || read != text.size())
        throw std::runtime_error("Cannot read metadata");
    char extra = 0;
    if (!ReadFile(handle.get(), &extra, 1, &read, nullptr) || read != 0) throw std::runtime_error("Metadata changed");
    std::vector<std::set<std::string>> keys;
    auto callback = [&keys](int depth, Json::parse_event_t event, Json& parsed) {
        if (depth > 16) throw std::runtime_error("Metadata nesting exceeds schema");
        if (event == Json::parse_event_t::object_start) keys.emplace_back();
        if (event == Json::parse_event_t::key && !keys.back().insert(parsed.get<std::string>()).second)
            throw std::runtime_error("Duplicate metadata property");
        if (event == Json::parse_event_t::object_end) keys.pop_back();
        return true;
    };
    return Json::parse(text, callback);
}

bool fields(const Json& value, std::initializer_list<const char*> expected) {
    if (!value.is_object() || value.size() != expected.size()) return false;
    for (const auto* key : expected) if (!value.contains(key)) return false;
    return true;
}

bool versionOne(const Json& document) {
    return document["version"].is_number_integer() && document["version"] == 1;
}

std::string transferState(const Json& value) {
    if (!value.is_string()) throw std::runtime_error("Missing transfer state");
    const auto state = value.get<std::string>();
    if (state != "PROMOTED" && state != "PROMOTING" && state != "DEMOTING") throw std::runtime_error("Unknown transfer state");
    return state;
}

std::vector<PromotionRecord> readIndex(const fs::path& directory) {
    if (!ordinaryParents(directory)) throw std::runtime_error("Index directory is not ordinary");
    const auto document = readDocument(directory / L".gametier.json");
    if (!fields(document, {"version", "promotions"}) || !versionOne(document) || !document["promotions"].is_array())
        throw std::runtime_error("Invalid index schema");
    std::vector<PromotionRecord> records;
    const auto lessPath = [](const fs::path& first, const fs::path& second) {
        return CompareStringOrdinal(fs::path(first).make_preferred().c_str(), -1,
            fs::path(second).make_preferred().c_str(), -1, TRUE) == CSTR_LESS_THAN;
    };
    std::set<fs::path, decltype(lessPath)> origins(lessPath), destinations(lessPath);
    for (const auto& entry : document["promotions"]) {
        if (!fields(entry, {"destination", "origin", "state"})) throw std::runtime_error("Invalid promotion schema");
        const fs::path name(wideString(entry["destination"]));
        const fs::path original(wideString(entry["origin"]));
        const auto destination = directory / name;
        if (name.has_parent_path() || name.is_absolute() || samePath(name, L".gametier.json") ||
            !safePath(original) || !safePath(destination) || samePath(original, destination)) throw std::runtime_error("Unsafe promotion paths");
        if (!origins.insert(original).second || !destinations.insert(destination).second) throw std::runtime_error("Duplicate promotion identity");
        records.push_back({original, destination, transferState(entry["state"])});
    }
    return records;
}

fs::path backupFolder(const fs::path& original) {
    return volumeRoot(original) / L".gametier/backups" / original.filename();
}

PromotionRecord readBackup(const fs::path& folder) {
    if (!ordinaryParents(folder)) throw std::runtime_error("Backup directory is not ordinary");
    const auto document = readDocument(folder / L"backup.json");
    if (!fields(document, {"version", "original", "destination", "state"}) || !versionOne(document))
        throw std::runtime_error("Invalid backup schema");
    PromotionRecord record{fs::path(wideString(document["original"])), fs::path(wideString(document["destination"])), transferState(document["state"])};
    if (!safePath(record.original) || !safePath(record.destination) || !samePath(folder, backupFolder(record.original)))
        throw std::runtime_error("Invalid backup identity");
    for (const auto& path : {record.original, record.destination}) {
        for (const auto& storage : {volumeRoot(record.original) / L".gametier/backups", volumeRoot(record.destination) / L".gametier/backups"}) {
            if (within(path, storage) || within(storage, path)) throw std::runtime_error("Game overlaps backup storage");
        }
    }
    if (within(record.original, record.destination) || within(record.destination, record.original) ||
        within(record.original, folder) || within(folder, record.original) ||
        within(record.destination, folder) || within(folder, record.destination)) throw std::runtime_error("Overlapping backup identity");
    return record;
}

bool relationship(const PromotionRecord& record) {
    fs::path linked;
    if (!ordinaryParents(record.original.parent_path()) || !ordinaryParents(record.destination.parent_path()) ||
        !ordinary(record.destination) || !symbolicTarget(record.original, linked)) return false;
    DWORD attributes = 0;
    fs::path actual, expected;
    return metadata(linked, attributes, &actual) && metadata(record.destination, attributes, &expected) && samePath(actual, expected);
}

bool matchingIndex(const PromotionRecord& expected) {
    for (const auto& record : readIndex(expected.destination.parent_path())) {
        if (samePath(record.original, expected.original) && samePath(record.destination, expected.destination)) return record.state == "PROMOTED";
    }
    return false;
}

bool restoreIndexAllows(const PromotionRecord& expected) {
    std::vector<PromotionRecord> records;
    try { records = readIndex(expected.destination.parent_path()); }
    catch (...) { return true; }
    for (const auto& record : records) {
        if (samePath(record.original, expected.original) || samePath(record.destination, expected.destination)) {
            return samePath(record.original, expected.original) && samePath(record.destination, expected.destination) && record.state == "PROMOTED";
        }
    }
    return true;
}

Action backupAction(const fs::path& folder) {
    const auto record = readBackup(folder);
    if (record.state != "PROMOTED" || !ordinary(folder / L"data") || !missing(folder / L"return") ||
        !ordinaryParents(record.original.parent_path())) return Action::None;
    if (missing(record.destination)) {
        if (!ordinaryExistingParents(record.destination.parent_path()) || !restoreIndexAllows(record)) return Action::None;
        fs::path linked;
        if (!missing(record.original) && (!symbolicTarget(record.original, linked) || !samePath(linked, record.destination))) return Action::None;
        return Action::RestoreBackup;
    }
    return matchingIndex(record) && relationship(record) ? Action::Demote : Action::None;
}

Action promotionAction(const PromotionRecord& record) {
    if (record.state != "PROMOTED" || !relationship(record)) return Action::None;
    const auto backup = backupFolder(record.original);
    if (missing(backup)) return Action::Demote;
    const auto stored = readBackup(backup);
    if (!samePath(stored.original, record.original) || !samePath(stored.destination, record.destination)) return Action::None;
    return backupAction(backup);
}

bool ordinaryDescendants(fs::path selected, const fs::path& root) {
    while (!samePath(selected, root)) {
        if (!within(selected, root) || !ordinary(selected)) return false;
        selected = selected.parent_path();
    }
    return true;
}

Decision managedSelection(const fs::path& selected) {
    FILE_ATTRIBUTE_TAG_INFO selectedInfo{};
    if (entryInfo(selected, selectedInfo) != Entry::Present) return {true, Action::None};
    const auto storage = volumeRoot(selected) / L".gametier/backups";
    if (within(selected, storage)) {
        auto folder = selected;
        while (!folder.empty() && !samePath(folder.parent_path(), storage) && !samePath(folder, storage)) folder = folder.parent_path();
        if (folder.empty() || samePath(folder, storage) || !ordinary(selected) ||
            !ordinaryParents((selectedInfo.FileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0 ? selected : selected.parent_path())) return {true, Action::None};
        return {true, backupAction(folder)};
    }
    std::vector<fs::path> ancestors;
    auto current = selected;
    while (!current.empty()) {
        ancestors.push_back(current);
        const auto parent = current.parent_path();
        if (PathCchIsRoot(current.c_str()) || parent == current) break;
        current = parent;
    }
    bool unsafeAlias = false;
    for (auto ancestor = ancestors.rbegin(); ancestor != ancestors.rend(); ++ancestor) {
        current = *ancestor;
        FILE_ATTRIBUTE_TAG_INFO info{};
        const auto status = entryInfo(current, info);
        if (status == Entry::Unknown || (current == selected && status != Entry::Present)) return {true, Action::None};
        if ((info.FileAttributes & FILE_ATTRIBUTE_DEVICE) != 0 ||
            ((info.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0 && info.ReparseTag != IO_REPARSE_TAG_SYMLINK)) unsafeAlias = true;
        if (samePath(current.filename(), L".gametier") && (info.FileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) return {true, Action::None};
        if (status == Entry::Present && info.ReparseTag == IO_REPARSE_TAG_SYMLINK) {
            fs::path linked;
            if (!symbolicTarget(current, linked)) return {true, Action::None};
            const auto backup = backupFolder(current);
            if (!missing(backup)) {
                const auto record = readBackup(backup);
                if (unsafeAlias || !ordinaryDescendants(selected, current) || !samePath(record.original, current) ||
                    !samePath(record.destination, linked)) return {true, Action::None};
                return {true, backupAction(backup)};
            }
            for (const auto& record : readIndex(linked.parent_path())) {
                if (samePath(record.original, current) && samePath(record.destination, linked))
                    return {true, unsafeAlias || !ordinaryDescendants(selected, current) ? Action::None : promotionAction(record)};
            }
            return {true, Action::None};
        }
        if (status == Entry::Present && (info.FileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0 && !missing(current / L".gametier.json")) {
            for (const auto& record : readIndex(current)) {
                if (within(selected, record.destination)) return {true, unsafeAlias || !ordinaryDescendants(selected, record.destination) ? Action::None : promotionAction(record)};
            }
            return {true, Action::None};
        }
    }
    return {};
}
