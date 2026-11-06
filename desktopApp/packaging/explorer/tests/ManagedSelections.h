struct ManagedFixture {
    Fixture files;
    fs::path drive, original, destination, backup;
    std::wstring device, mapping;
    explicit ManagedFixture(bool createLink = true);
    ~ManagedFixture();
    void index(const std::string& state = "PROMOTED");
    void snapshot(const std::string& state = "PROMOTED");
};

std::string jsonString(const fs::path& value);
void write(const fs::path& path, const std::string& value);
void expectAction(const fs::path& path, const wchar_t* title);
void expectHidden(const fs::path& path);
void expectLaunch(const fs::path& path, const wchar_t* action, const fs::path& capture);

void demotes_verified_original_destination_and_descendants() {
    ManagedFixture fixture;
    for (const auto& path : {fixture.original, fixture.original / L"child/data.bin",
                            fixture.destination, fixture.destination / L"child/data.bin"}) {
        expectAction(path, L"Demote with GameTier");
    }
}

void demotes_backup_game_folder_and_descendants() {
    ManagedFixture fixture;
    fixture.snapshot();
    for (const auto& path : {fixture.backup, fixture.backup / L"data", fixture.backup / L"data/child/snapshot.bin"}) {
        expectAction(path, L"Demote with GameTier");
    }
    expectHidden(fixture.backup.parent_path());
    expectHidden(fixture.backup.parent_path().parent_path());
}

void restores_backup_when_target_missing() {
    ManagedFixture fixture(false);
    fixture.snapshot();
    fs::remove_all(fixture.destination);
    expectAction(fixture.backup, L"Restore backup with GameTier");
    expectAction(fixture.backup / L"data/child/snapshot.bin", L"Restore backup with GameTier");
    expectLaunch(fixture.backup / L"data/child/snapshot.bin", L"--restore-backup", fixture.files.root / L"arguments.bin");
}

void restores_independent_backup_without_valid_index() {
    ManagedFixture fixture(false);
    fixture.snapshot();
    fs::remove_all(fixture.destination);
    const auto marker = fixture.destination.parent_path() / L".gametier.json";
    for (const auto& document : {"broken", "{\"version\":1,\"promotions\":[]}"}) {
        write(marker, document);
        expectAction(fixture.backup, L"Restore backup with GameTier");
    }
    fs::remove(marker);
    expectAction(fixture.backup, L"Restore backup with GameTier");
    fixture.index("DEMOTING");
    expectHidden(fixture.backup);
    write(marker, "{\"version\":1,\"promotions\":[{\"destination\":\"other\",\"origin\":" + jsonString(fixture.original) + ",\"state\":\"PROMOTED\"}]}");
    expectHidden(fixture.backup);
}

void restores_after_destination_parent_removed() {
    ManagedFixture fixture(false);
    fixture.snapshot();
    fs::remove_all(fixture.destination.parent_path());
    expectAction(fixture.backup, L"Restore backup with GameTier");
}

void hides_redirected_destination_parent() {
    ManagedFixture fixture(false);
    fixture.snapshot();
    fs::remove_all(fixture.destination.parent_path());
    const auto empty = fixture.files.directory(L"empty");
    junction(fixture.destination.parent_path(), empty);
    expectHidden(fixture.backup);
}

void hides_interrupted_and_broken_relationships() {
    for (const auto& transferState : {"PROMOTING", "DEMOTING"}) {
        ManagedFixture fixture;
        fixture.index(transferState);
        fixture.snapshot(transferState);
        expectHidden(fixture.original);
        expectHidden(fixture.destination);
        expectHidden(fixture.backup);
        fs::remove_all(fixture.destination);
        expectHidden(fixture.backup);
    }
    ManagedFixture fixture;
    fs::remove(fixture.original);
    junction(fixture.original, fixture.destination);
    expectHidden(fixture.original);
    expectHidden(fixture.destination);
}

void hides_unsafe_backup_states() {
    for (const auto& problem : {"return", "snapshot", "occupied", "wrong-link", "bad-record", "record-mismatch"}) {
        ManagedFixture fixture;
        fixture.snapshot();
        fs::remove_all(fixture.destination);
        if (std::string(problem) == "return") fs::create_directory(fixture.backup / L"return");
        if (std::string(problem) == "snapshot") fs::remove_all(fixture.backup / L"data");
        if (std::string(problem) == "occupied") {
            fs::remove(fixture.original);
            write(fixture.original, "occupied");
        }
        if (std::string(problem) == "wrong-link") {
            fs::remove(fixture.original);
            require(CreateSymbolicLinkW(fixture.original.c_str(), fixture.files.root.c_str(),
                SYMBOLIC_LINK_FLAG_DIRECTORY | SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE) != FALSE, "replace original symlink");
        }
        if (std::string(problem) == "bad-record") write(fixture.backup / L"backup.json", "{}");
        if (std::string(problem) == "record-mismatch") {
            write(fixture.backup / L"backup.json", "{\"version\":1,\"original\":" + jsonString(fixture.drive / L"other/game") +
                ",\"destination\":" + jsonString(fixture.destination) + ",\"state\":\"PROMOTED\"}");
        }
        expectHidden(fixture.backup);
    }
}

void hides_invalid_index_documents() {
    const std::vector<std::string> invalid{
        "{}", "{\"version\":1,\"version\":1,\"promotions\":[]}",
        "{\"version\":1,\"promotio\\u006es\":[],\"promotions\":[]}",
        "{\"version\":1,\"promotions\":[],\"extra\":0}",
        "{\"version\":\"1\",\"promotions\":[]}",
        "{\"version\":1.0,\"promotions\":[]}",
        "{\"version\":1,\"promotions\":[],}",
        "{\"version\":1,\"promotions\":[],\"x\":\"\\uD800\"}",
        std::string("{\"version\":1,\"promotions\":[],\"x\":\"") + char(0xff) + "\"}",
        std::string(1024 * 1024 + 1, ' '),
        std::string(1000, '[') + std::string(1000, ']'),
    };
    for (const auto& document : invalid) {
        ManagedFixture fixture;
        write(fixture.destination.parent_path() / L".gametier.json", document);
        expectHidden(fixture.original);
        expectHidden(fixture.destination / L"child/data.bin");
    }
}

void rejects_backup_invalidating_completed_promotion() {
    ManagedFixture fixture;
    fixture.snapshot("DEMOTING");
    expectHidden(fixture.original);
    expectHidden(fixture.destination);
    fixture.snapshot();
    fs::create_directory(fixture.backup / L"return");
    expectHidden(fixture.destination);
    write(fixture.backup / L"backup.json", "{}");
    expectHidden(fixture.destination);
}

void launches_dynamic_actions_and_revalidates() {
    ManagedFixture fixture;
    fixture.snapshot();
    expectLaunch(fixture.original / L"child/data.bin", L"--demote", fixture.files.root / L"demote.bin");
    fs::remove_all(fixture.destination);
    expectLaunch(fixture.backup / L"data/child/snapshot.bin", L"--restore-backup", fixture.files.root / L"restore.bin");
    const auto items = selection({fixture.backup});
    const auto subject = command();
    require(state(subject.Get(), items.Get()) == ECS_ENABLED, "restore must start eligible");
    fs::remove(fixture.original);
    write(fixture.original, "occupied");
    const auto result = subject->Invoke(items.Get(), nullptr);
    require(result == HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED), "invoke must revalidate changed restoration identity");
}

void rejects_changed_menu_action() {
    ManagedFixture fixture;
    fixture.snapshot();
    const auto items = selection({fixture.backup});
    const auto subject = command();
    require(state(subject.Get(), items.Get()) == ECS_ENABLED, "demotion must start eligible");
    PWSTR title = nullptr;
    checked(subject->GetTitle(items.Get(), &title));
    const std::wstring displayed(title);
    CoTaskMemFree(title);
    require(displayed == L"Demote with GameTier", "menu must display demotion before change");
    fs::remove_all(fixture.destination);
    require(state(subject.Get(), items.Get()) == ECS_ENABLED, "state may requery availability after title is rendered");
    const auto result = subject->Invoke(items.Get(), nullptr);
    require(result == HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED), "stale demotion must not launch backup restoration");
    expectAction(fixture.backup, L"Restore backup with GameTier");
}

void rejects_alias_to_another_managed_game() {
    ManagedFixture first;
    ManagedFixture second;
    fs::remove(first.original);
    require(CreateSymbolicLinkW(first.original.c_str(), second.destination.c_str(),
        SYMBOLIC_LINK_FLAG_DIRECTORY | SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE) != FALSE, "redirect first game's link");
    expectHidden(first.original);
    expectHidden(first.original / L"child/data.bin");
    expectAction(second.original, L"Demote with GameTier");
    fs::remove(first.original);
    junction(first.original, second.destination);
    expectHidden(first.original / L"child/data.bin");
}

void identifies_whole_game_beneath_embedded_markers() {
    ManagedFixture fixture;
    fixture.snapshot();
    write(fixture.destination / L"child/.gametier.json", "ordinary game content");
    write(fixture.destination / L"child/.gametier/save.bin", "ordinary game content");
    write(fixture.backup / L"data/child/.gametier.json", "ordinary snapshot content");
    write(fixture.backup / L"data/child/.gametier/save.bin", "ordinary snapshot content");
    for (const auto& path : {fixture.destination / L"child/.gametier/save.bin",
                            fixture.original / L"child/.gametier/save.bin",
                            fixture.backup / L"data/child/.gametier/save.bin"}) {
        expectAction(path, L"Demote with GameTier");
    }
}

void rejects_managed_reparse_metadata_and_descendants() {
    for (const auto& problem : {"index", "backup", "snapshot", "descendant", "original-parent"}) {
        ManagedFixture fixture;
        fixture.snapshot();
        if (std::string(problem) == "index" || std::string(problem) == "backup") {
            const auto file = std::string(problem) == "index" ? fixture.destination.parent_path() / L".gametier.json" : fixture.backup / L"backup.json";
            const auto payload = fixture.files.root / L"metadata.json";
            fs::rename(file, payload);
            require(CreateSymbolicLinkW(file.c_str(), payload.c_str(), SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE) != FALSE,
                "create reparse metadata fixture");
        }
        if (std::string(problem) == "snapshot") {
            fs::remove_all(fixture.backup / L"data");
            junction(fixture.backup / L"data", fixture.destination);
        }
        if (std::string(problem) == "original-parent") {
            fs::rename(fixture.original.parent_path(), fixture.drive / L"games-real");
            junction(fixture.original.parent_path(), fixture.drive / L"games-real");
        }
        if (std::string(problem) == "descendant") {
            junction(fixture.destination / L"alias", fixture.destination / L"child");
            expectHidden(fixture.destination / L"alias/data.bin");
            expectHidden(fixture.original / L"alias/data.bin");
            continue;
        }
        expectHidden(fixture.original);
        expectHidden(fixture.destination);
        expectHidden(fixture.backup);
    }
}

void rejects_invalid_index_records() {
    ManagedFixture fixture;
    const auto entry = "{\"destination\":" + jsonString(fixture.destination.filename()) + ",\"origin\":" + jsonString(fixture.original) + ",\"state\":\"PROMOTED\"}";
    std::vector<std::string> documents{
        "{\"version\":1,\"promotions\":[" + entry + "," + entry + "]}",
        "{\"version\":1,\"promotions\":[{\"destination\":\"../game\",\"origin\":" + jsonString(fixture.original) + ",\"state\":\"PROMOTED\"}]}",
        "{\"version\":1,\"promotions\":[{\"destination\":" + jsonString(fixture.destination.filename()) + ",\"origin\":\"relative\",\"state\":\"PROMOTED\"}]}",
        "{\"version\":1,\"promotions\":[{\"destination\":" + jsonString(fixture.destination.filename()) + ",\"origin\":" + jsonString(fixture.original) + ",\"state\":\"unknown\"}]}",
        "{\"version\":1,\"promotions\":[{\"destination\":" + jsonString(fixture.destination.filename()) + ",\"origin\":" + jsonString(fixture.original) + ",\"state\":\"PROMOTED\",\"extra\":0}]}",
    };
    for (const auto& document : documents) {
        write(fixture.destination.parent_path() / L".gametier.json", document);
        expectHidden(fixture.original);
        expectHidden(fixture.destination);
    }
}

void rejects_invalid_backup_documents() {
    ManagedFixture fixture(false);
    fixture.snapshot();
    fs::remove_all(fixture.destination);
    const std::vector<std::string> documents{
        "{}", "{\"version\":1,\"original\":\"x\",\"original\":\"x\",\"destination\":\"y\",\"state\":\"PROMOTED\"}",
        "{\"version\":1,\"original\":\"\\uD800\",\"destination\":\"y\",\"state\":\"PROMOTED\"}",
        std::string("{\"version\":1,\"original\":\"") + char(0xff) + "\",\"destination\":\"y\",\"state\":\"PROMOTED\"}",
        std::string(1024 * 1024 + 1, ' '), std::string(1000, '[') + std::string(1000, ']'),
    };
    for (const auto& document : documents) {
        write(fixture.backup / L"backup.json", document);
        expectHidden(fixture.backup / L"data/child/snapshot.bin");
    }
}

std::string jsonString(const fs::path& value) {
    const auto text = value.native();
    const auto size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), nullptr, 0, nullptr, nullptr);
    require(size > 0, "encode fixture JSON path");
    std::string utf8(size, '\0');
    WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()), utf8.data(), size, nullptr, nullptr);
    std::string escaped = "\"";
    for (const auto character : utf8) {
        if (character == '\\' || character == '"') escaped += '\\';
        escaped += character;
    }
    return escaped + "\"";
}

void write(const fs::path& path, const std::string& value) {
    fs::create_directories(path.parent_path());
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    output << value;
    require(output.good(), "write managed fixture");
}

ManagedFixture::ManagedFixture(bool createLink) {
    for (wchar_t letter = L'Z'; letter >= L'D'; --letter) {
        device = std::wstring(1, letter) + L":";
        wchar_t target[32768]{};
        if (QueryDosDeviceW(device.c_str(), target, 32768) == 0 && GetLastError() == ERROR_FILE_NOT_FOUND) break;
        device.clear();
    }
    require(!device.empty(), "find spare isolated fixture drive");
    mapping = L"\\??\\" + files.root.native();
    require(DefineDosDeviceW(DDD_RAW_TARGET_PATH | DDD_NO_BROADCAST_SYSTEM, device.c_str(), mapping.c_str()) != FALSE,
        "map isolated fixture volume");
    struct Cleanup {
        const std::wstring& device;
        const std::wstring& mapping;
        bool active = true;
        ~Cleanup() {
            if (active) DefineDosDeviceW(DDD_REMOVE_DEFINITION | DDD_EXACT_MATCH_ON_REMOVE | DDD_RAW_TARGET_PATH | DDD_NO_BROADCAST_SYSTEM,
                device.c_str(), mapping.c_str());
        }
    } cleanup{device, mapping};
    drive = device + L"\\";
    original = drive / L"games/game space \u00e4\u65e5";
    destination = drive / L"promoted/game space \u00e4\u65e5";
    backup = drive / L".gametier/backups/game space \u00e4\u65e5";
    write(destination / L"child/data.bin", "live payload");
    fs::create_directories(original.parent_path());
    if (createLink && !CreateSymbolicLinkW(original.c_str(), destination.c_str(),
        SYMBOLIC_LINK_FLAG_DIRECTORY | SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE)) {
        const auto error = GetLastError();
        throw std::runtime_error("create managed original symlink: " + std::to_string(error));
    }
    index();
    cleanup.active = false;
}

ManagedFixture::~ManagedFixture() {
    DefineDosDeviceW(DDD_REMOVE_DEFINITION | DDD_EXACT_MATCH_ON_REMOVE | DDD_RAW_TARGET_PATH | DDD_NO_BROADCAST_SYSTEM,
        device.c_str(), mapping.c_str());
}

void ManagedFixture::index(const std::string& state) {
    write(destination.parent_path() / L".gametier.json", "{\"version\":1,\"promotions\":[{\"destination\":" +
        jsonString(destination.filename()) + ",\"origin\":" + jsonString(original) + ",\"state\":\"" + state + "\"}]}");
}

void ManagedFixture::snapshot(const std::string& state) {
    write(backup / L"data/child/snapshot.bin", "snapshot payload");
    write(backup / L"backup.json", "{\"version\":1,\"original\":" + jsonString(original) +
        ",\"destination\":" + jsonString(destination) + ",\"state\":\"" + state + "\"}");
}

void expectAction(const fs::path& path, const wchar_t* title) {
    const auto items = selection({path});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_ENABLED, "verified managed selection must be enabled");
    PWSTR actual = nullptr;
    checked(subject->GetTitle(items.Get(), &actual));
    const std::wstring value(actual);
    CoTaskMemFree(actual);
    require(value == title, "managed selection must expose its available action");
    PWSTR tooltip = nullptr;
    checked(subject->GetToolTip(items.Get(), &tooltip));
    const std::wstring description(tooltip);
    CoTaskMemFree(tooltip);
    require(description.find(L"promotion to") == std::wstring::npos, "managed tooltip must describe its own action");
}

void expectHidden(const fs::path& path) {
    const auto items = selection({path});
    const auto subject = command();
    const auto result = state(subject.Get(), items.Get());
    require(result == ECS_HIDDEN, "unsafe managed selection must be hidden");
    require(subject->Invoke(items.Get(), nullptr) == HRESULT_FROM_WIN32(ERROR_ACCESS_DENIED), "unsafe selection must not launch");
}

void expectLaunch(const fs::path& path, const wchar_t* action, const fs::path& capture) {
    const auto eventName = L"Local\\GameTierManagedTest-" + std::to_wstring(GetCurrentProcessId());
    const auto event = CreateEventW(nullptr, TRUE, FALSE, eventName.c_str());
    require(event != nullptr, "create managed launch event");
    SetEnvironmentVariableW(L"GAMETIER_TEST_CAPTURE", capture.c_str());
    SetEnvironmentVariableW(L"GAMETIER_TEST_EVENT", eventName.c_str());
    const auto items = selection({path});
    ComPtr<IShellItem> item;
    checked(items->GetItemAt(0, &item));
    PWSTR selected = nullptr;
    checked(item->GetDisplayName(SIGDN_FILESYSPATH, &selected));
    const std::wstring expected = std::wstring(action) + L"\n" + selected;
    CoTaskMemFree(selected);
    const auto subject = command();
    const auto result = subject->Invoke(items.Get(), nullptr);
    const auto completed = result == S_OK ? WaitForSingleObject(event, 10000) : WAIT_FAILED;
    CloseHandle(event);
    SetEnvironmentVariableW(L"GAMETIER_TEST_CAPTURE", nullptr);
    SetEnvironmentVariableW(L"GAMETIER_TEST_EVENT", nullptr);
    require(result == S_OK && completed == WAIT_OBJECT_0, "managed invoke must launch adjacent application");
    std::ifstream input(capture, std::ios::binary);
    const std::string bytes{std::istreambuf_iterator<char>(input), {}};
    require(bytes == std::string(reinterpret_cast<const char*>(expected.data()), expected.size() * sizeof(wchar_t)),
        "managed launch must preserve action and original selected Unicode descendant");
}
